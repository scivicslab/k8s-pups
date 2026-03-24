#!/bin/bash
# Entrypoint for Docusaurus runner container.
# Expects:
#   DOCUSAURUS_PATH - relative path under /workspace (e.g. doc_SCIVICS002)
#   PUPS_SESSION_PATH - session base path injected by k8s-pups (e.g. /session/abc123/)

set -euo pipefail

# Add passwd/group entries for arbitrary UID (NFS workspace mode)
CURRENT_UID=$(id -u)
CURRENT_GID=$(id -g)
if ! id -un "$CURRENT_UID" >/dev/null 2>&1; then
    echo "appuser:x:${CURRENT_UID}:${CURRENT_GID}:App User:${HOME}:/bin/bash" >> /etc/passwd
    echo "Added passwd entry for UID ${CURRENT_UID}"
fi
if ! getent group "$CURRENT_GID" >/dev/null 2>&1; then
    echo "appuser:x:${CURRENT_GID}:" >> /etc/group
    echo "Added group entry for GID ${CURRENT_GID}"
fi

if [ -z "${DOCUSAURUS_PATH:-}" ]; then
    echo "ERROR: DOCUSAURUS_PATH is not set." >&2
    exit 1
fi

PROJECT_DIR="/workspace/${DOCUSAURUS_PATH}"

if [ ! -d "$PROJECT_DIR" ]; then
    echo "ERROR: Directory does not exist: $PROJECT_DIR" >&2
    exit 1
fi

cd "$PROJECT_DIR"

# node_modules should already exist on the NFS-mounted workspace.
if [ ! -d "node_modules" ]; then
    echo "ERROR: node_modules not found in $PROJECT_DIR. Run 'yarn install' on the host first." >&2
    exit 1
fi

# Docusaurus requires baseUrl to match the actual serving path.
# Create a wrapper config inside the project directory so that
# module resolution (presets, plugins) works correctly.
BASE_URL="${PUPS_SESSION_PATH:-/}"

# Detect original config file
if [ -f "docusaurus.config.ts" ]; then
    ORIG_CONFIG="./docusaurus.config.ts"
elif [ -f "docusaurus.config.js" ]; then
    ORIG_CONFIG="./docusaurus.config.js"
else
    echo "ERROR: No docusaurus.config.ts or docusaurus.config.js found." >&2
    exit 1
fi

WRAPPER=".docusaurus.pups.mjs"
cat > "$WRAPPER" <<EOF
import originalConfig from '${ORIG_CONFIG}';
const config = typeof originalConfig === 'function' ? await originalConfig() : originalConfig;
export default { ...config, url: 'https://localhost', baseUrl: '${BASE_URL}' };
EOF

# Clean up wrapper on exit
trap "rm -f '${PROJECT_DIR}/${WRAPPER}'" EXIT

echo "Starting Docusaurus at $PROJECT_DIR with baseUrl=$BASE_URL"
# Enable polling for file watching (inotify does not work on NFS)
export WATCHPACK_POLLING=true
export CHOKIDAR_USEPOLLING=true

# --- Auto-recovery for hot-reload crashes ---
# Docusaurus dev server can crash when .docusaurus/ metadata cache gets
# corrupted by a race condition during rapid file edits (hot reload writes
# a JSON file while another compilation reads it mid-write, causing
# "Unterminated string in JSON" SyntaxError).
#
# Strategy:
#   - If the server ran for >= STABLE_THRESHOLD seconds, it was a hot-reload
#     crash. Clear .docusaurus/ cache and restart (unlimited retries, reset counter).
#   - If the server ran for < STABLE_THRESHOLD seconds, it is a startup error
#     (bad config, missing deps, etc.). Retry up to MAX_STARTUP_RETRIES times.
# Disable set -e for the retry loop (non-zero exit is expected on crash)
set +e

MAX_STARTUP_RETRIES=3
STABLE_THRESHOLD=30
startup_failures=0

while true; do
    start_time=$(date +%s)

    # Run without exec so the script stays alive after a crash
    npx docusaurus start --config "$WRAPPER" --host 0.0.0.0 --port 3000
    exit_code=$?

    # Clean exit (SIGTERM from k8s pod deletion) — stop the loop
    if [ $exit_code -eq 0 ] || [ $exit_code -eq 143 ]; then
        echo "Docusaurus exited cleanly (code $exit_code). Shutting down."
        exit 0
    fi

    elapsed=$(( $(date +%s) - start_time ))

    if [ $elapsed -ge $STABLE_THRESHOLD ]; then
        # Hot-reload crash: server was running fine, then crashed.
        # Clear cache and restart. Reset failure counter.
        startup_failures=0
        echo "Docusaurus crashed after ${elapsed}s (exit code $exit_code). Likely a hot-reload cache corruption."
        echo "Clearing .docusaurus/ cache and restarting..."
        rm -rf .docusaurus
        sleep 2
    else
        # Startup failure: server crashed before becoming stable.
        startup_failures=$((startup_failures + 1))
        if [ $startup_failures -ge $MAX_STARTUP_RETRIES ]; then
            echo "ERROR: Docusaurus failed to start $MAX_STARTUP_RETRIES times (exit code $exit_code). Giving up." >&2
            exit 1
        fi
        echo "Docusaurus crashed after ${elapsed}s (exit code $exit_code). Startup failure $startup_failures/$MAX_STARTUP_RETRIES."
        echo "Clearing .docusaurus/ cache and retrying..."
        rm -rf .docusaurus
        sleep 3
    fi
done
