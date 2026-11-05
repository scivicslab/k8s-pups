#!/bin/sh
# Starts the code editor on the one port that the controller proxies.
# Access is decided by the controller (login and ownership of the session), so the editor has no password of its own.
# Downloads and uploads through the editor are closed: the data of the session must not leave through its screen.
#
# CODE_SERVER_AI_BASE_URL is the OpenAI-compatible base URL of the GPU broker (ending in /v1) and CODE_SERVER_AI_MODEL
# the model; the controller passes both. Continue keeps its settings and chats in CONTINUE_GLOBAL_DIR, a directory of
# the Pod (the image's home is not writable by the user of the Pod).
DATA=/home/devteam/.local/share/code-server
export CONTINUE_GLOBAL_DIR=/home/devteam/.local/continue
mkdir -p "$DATA/User" "$CONTINUE_GLOBAL_DIR"

# Telemetry off: nothing leaves the cluster. The built-in chat (GitHub Copilot) is hidden, with the side bar it sits in.
# Workspace trust is off: the folder is the user's own storage, and in an untrusted folder the editor runs no extension,
# Continue included. Tab completion and next edit are off: they call the model on every keystroke, which a reasoning
# model is too slow for.
cat > "$DATA/User/settings.json" <<'EOF'
{
  "chat.disableAIFeatures": true,
  "workbench.secondarySideBar.defaultVisibility": "hidden",
  "security.workspace.trust.enabled": false,
  "continue.telemetryEnabled": false,
  "continue.enableTabAutocomplete": false,
  "continue.enableNextEdit": false,
  "continue.showInlineTip": false
}
EOF

if [ -n "$CODE_SERVER_AI_BASE_URL" ] && [ -n "$CODE_SERVER_AI_MODEL" ]; then
  cat > "$CONTINUE_GLOBAL_DIR/config.yaml" <<EOF
name: Local LLM
version: 1.0.0
schema: v1
models:
  - name: $CODE_SERVER_AI_MODEL
    provider: openai
    model: $CODE_SERVER_AI_MODEL
    apiBase: $CODE_SERVER_AI_BASE_URL
    apiKey: not-needed
    roles: [chat, edit, apply]
EOF
fi

exec code-server \
  --bind-addr 0.0.0.0:8080 \
  --auth none \
  --user-data-dir "$DATA" \
  --extensions-dir /opt/code-server/extensions \
  --disable-telemetry \
  --disable-update-check \
  --disable-file-downloads \
  --disable-file-uploads \
  --disable-getting-started-override \
  /home/devteam/works
