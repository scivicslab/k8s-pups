#!/bin/bash
# Entrypoint for Docusaurus builder container.
# Expects:
#   DOCUSAURUS_PATH - relative path under /workspace (e.g. doc_SCIVICS003)
#   BUILD_BASE_URL  - base URL for the built site (e.g. /docs/doc_SCIVICS003/)
#
# The build output is copied to /output/$DOCUSAURUS_PATH/

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
OUTPUT_DIR="/output/${DOCUSAURUS_PATH}"
BASE_URL="${BUILD_BASE_URL:-/docs/${DOCUSAURUS_PATH}/}"

if [ ! -d "$PROJECT_DIR" ]; then
    echo "ERROR: Directory does not exist: $PROJECT_DIR" >&2
    exit 1
fi

cd "$PROJECT_DIR"

if [ ! -d "node_modules" ]; then
    echo "ERROR: node_modules not found in $PROJECT_DIR. Run 'yarn install' on the host first." >&2
    exit 1
fi

# Detect original config file
if [ -f "docusaurus.config.ts" ]; then
    ORIG_CONFIG="./docusaurus.config.ts"
elif [ -f "docusaurus.config.js" ]; then
    ORIG_CONFIG="./docusaurus.config.js"
else
    echo "ERROR: No docusaurus.config.ts or docusaurus.config.js found." >&2
    exit 1
fi

# Create wrapper config that overrides baseUrl for the build
WRAPPER=".docusaurus.build.mjs"
cat > "$WRAPPER" <<EOF
import originalConfig from '${ORIG_CONFIG}';
const config = typeof originalConfig === 'function' ? await originalConfig() : originalConfig;
export default { ...config, url: 'https://192.168.5.25', baseUrl: '${BASE_URL}' };
EOF

trap "rm -f '${PROJECT_DIR}/${WRAPPER}'" EXIT

echo "========================================"
echo "Building Docusaurus: ${DOCUSAURUS_PATH}"
echo "  baseUrl: ${BASE_URL}"
echo "  output:  ${OUTPUT_DIR}"
echo "========================================"

# Build (disable set -e temporarily to capture exit code)
set +e
npx docusaurus build --config "$WRAPPER" --out-dir build
BUILD_EXIT=$?
set -e

if [ $BUILD_EXIT -ne 0 ]; then
    echo "ERROR: Build failed with exit code $BUILD_EXIT" >&2
    echo "Build failed. Container will stay alive for log inspection."
    exec python3 -m http.server 3000 --directory /tmp 2>/dev/null || sleep infinity
fi

echo "Build succeeded. Copying to output directory..."

# Copy build output to NFS
rm -rf "${OUTPUT_DIR:?}/"
mkdir -p "$OUTPUT_DIR"
cp -a build/* "$OUTPUT_DIR/"

echo "========================================"
echo "BUILD COMPLETE"
echo "Site available at: https://192.168.5.25${BASE_URL}"
echo "========================================"

# --- OpenSearch Indexing ---
INDEX_MODE="${INDEX_MODE:-none}"
INDEX_STATUS="skipped"
OPENSEARCH_URL="http://opensearch.opensearch-dev.svc:9200"
SITEMAP_URL="http://docusaurus-sites.docusaurus-sites.svc/docs/${DOCUSAURUS_PATH}/sitemap.xml"
SAU3_JAR="/opt/sau3/sau3.jar"

if [ "$INDEX_MODE" != "none" ] && [ -f "$SAU3_JAR" ]; then
    # Create SAU3 config file for this project
    SAU3_CONF="/tmp/sau3_${DOCUSAURUS_PATH}.conf"
    cat > "$SAU3_CONF" <<CONFEOF
[index]
docusaurus_ja

[opensearch]
url=${OPENSEARCH_URL}

[sitemap urls]
${SITEMAP_URL}
CONFEOF

    echo ""
    echo "========================================"
    echo "Indexing: mode=${INDEX_MODE}"
    echo "  sitemap: ${SITEMAP_URL}"
    echo "========================================"

    if [ "$INDEX_MODE" = "full" ]; then
        echo "Running full re-index..."
        java -jar "$SAU3_JAR" sau index -c "$SAU3_CONF" && INDEX_STATUS="full index complete" || INDEX_STATUS="index failed"
    elif [ "$INDEX_MODE" = "update" ]; then
        echo "Running incremental update (last 7 days)..."
        java -jar "$SAU3_JAR" sau update -c "$SAU3_CONF" -d 7 && INDEX_STATUS="update complete" || INDEX_STATUS="update failed"
    else
        echo "Unknown INDEX_MODE: ${INDEX_MODE}. Skipping."
    fi

    echo "Index result: ${INDEX_STATUS}"
fi

# Batch mode: exit cleanly so k8s-pups auto-cleans the session
echo "All tasks finished. Exiting."
exit 0
