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
exec npx docusaurus start --config "$WRAPPER" --host 0.0.0.0 --port 3000
