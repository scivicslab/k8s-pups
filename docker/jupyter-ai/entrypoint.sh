#!/bin/bash
# Start JupyterLab with the base URL of the k8s-pups session and the Jupyter AI assistant pointed at an OpenAI-compatible server.
# PUPS_SESSION_PATH is injected by k8s-pups (e.g. /session/abc123/).
# JUPYTER_AI_BASE_URL is the OpenAI-compatible base URL (ending in /v1); JUPYTER_AI_MODEL is the default model id (may be empty).

# Ensure the current UID/GID exist in /etc/passwd and /etc/group: the container runs as the tool Pod ID set by the controller.
CURRENT_UID=$(id -u)
CURRENT_GID=$(id -g)
if ! id -un "$CURRENT_UID" >/dev/null 2>&1; then
    echo "jovyan:x:${CURRENT_UID}:${CURRENT_GID}:Jupyter User:${HOME}:/bin/bash" >> /etc/passwd
fi
if ! getent group "$CURRENT_GID" >/dev/null 2>&1; then
    echo "jovyan:x:${CURRENT_GID}:" >> /etc/group
fi

BASE_URL="${PUPS_SESSION_PATH:-/}"

# Server-level configuration of the Jupyter AI extension, written to a runtime directory that is writable.
CONF_DIR=/tmp/jupyter-config
mkdir -p "$CONF_DIR"
python3 - <<'PY'
import json, os
base = os.environ.get("JUPYTER_AI_BASE_URL", "")
model = os.environ.get("JUPYTER_AI_MODEL", "")
cfg = {"AiExtension": {}}
if base and model:
    mid = "openrouter:" + model
    cfg["AiExtension"] = {
        "default_language_model": mid,
        "default_api_keys": {"OPENROUTER_API_KEY": "not-needed"},
        "model_parameters": {mid: {"openai_api_base": base}},
    }
json.dump(cfg, open("/tmp/jupyter-config/jupyter_jupyter_ai_config.json", "w"))
PY

export JUPYTER_CONFIG_PATH="$CONF_DIR"
exec jupyter lab \
    --ip=0.0.0.0 \
    --port=8888 \
    --no-browser \
    --ServerApp.base_url="$BASE_URL" \
    --ServerApp.root_dir=/home/jovyan \
    --IdentityProvider.token='' \
    --ServerApp.password=''
