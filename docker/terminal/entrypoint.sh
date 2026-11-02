#!/bin/bash
# Starts the terminal on the one port that the controller proxies.
# Access is decided by the controller (login and ownership of the session), so the terminal has no password of its own.
#
# Hermes Agent refuses to run when its data directory is not owned by the user that runs it, and a tool Pod runs as the
# number of the cluster, not as the 1000 that owns the install in the image. So HERMES_HOME is a directory made here, owned
# by the running user, that holds the settings and the sessions (they last as long as the session) and links to the large
# install (the code, the tools, the install state) in the image.
# HERMES_BASE_URL and HERMES_MODEL select the local LLM (GPU broker base URL with /v1, and the model name).
INSTALL=/home/devteam/.hermes
export HERMES_HOME=/home/devteam/.hermes-run
mkdir -p "$HERMES_HOME"
for e in hermes-agent tools installs cache skills; do
  [ -e "$HERMES_HOME/$e" ] || ln -s "$INSTALL/$e" "$HERMES_HOME/$e"
done
for f in config.yaml .env SOUL.md; do
  [ -e "$HERMES_HOME/$f" ] || cp "$INSTALL/$f" "$HERMES_HOME/$f"
done
if [ -n "${HERMES_BASE_URL:-}" ]; then
  hermes config set model.provider custom >/dev/null
  hermes config set model.base_url "$HERMES_BASE_URL" >/dev/null
  [ -n "${HERMES_MODEL:-}" ] && hermes config set model.default "$HERMES_MODEL" >/dev/null
fi
cp /etc/motd-terminal /tmp/motd 2>/dev/null
# rendererType=dom draws the text as page elements, so that it can be selected, searched and read by a test.
exec ttyd -W -p 8080 -t rendererType=dom -t disableLeaveAlert=true -t fontSize=15 \
  bash -c 'cat /tmp/motd; exec bash -l'
