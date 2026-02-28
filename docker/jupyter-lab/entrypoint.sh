#!/bin/bash
# Start JupyterLab with the base URL matching the k8s-pups session path.
# PUPS_SESSION_PATH is injected by k8s-pups (e.g. /session/abc123/).

# Ensure current UID/GID exist in /etc/passwd and /etc/group.
# In workspace mode, the container runs as a LDAP UID that doesn't exist
# in the image's /etc/passwd. Python's pwd module needs to resolve it.
CURRENT_UID=$(id -u)
CURRENT_GID=$(id -g)
if ! id -un "$CURRENT_UID" >/dev/null 2>&1; then
    echo "jovyan:x:${CURRENT_UID}:${CURRENT_GID}:Jupyter User:${HOME}:/bin/bash" >> /etc/passwd
    echo "Added passwd entry for UID ${CURRENT_UID}"
fi
if ! getent group "$CURRENT_GID" >/dev/null 2>&1; then
    echo "jovyan:x:${CURRENT_GID}:" >> /etc/group
    echo "Added group entry for GID ${CURRENT_GID}"
fi

BASE_URL="${PUPS_SESSION_PATH:-/}"

exec jupyter lab \
    --ip=0.0.0.0 \
    --port=8888 \
    --no-browser \
    --ServerApp.base_url="$BASE_URL" \
    --ServerApp.root_dir=/home/jovyan \
    --IdentityProvider.token='' \
    --ServerApp.password=''
