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
for f in .env SOUL.md; do
  [ -e "$HERMES_HOME/$f" ] || cp "$INSTALL/$f" "$HERMES_HOME/$f"
done
# The settings of Hermes Agent are written here at every start, so that a change of the GPU broker or the model in the
# Settings screen reaches the next session, and so that the tools that talk to the outside stay closed. Only the shell
# (terminal), the files (file), the task list (todo), questions to the user (clarify) and the search of the past sessions
# (session_search) are left: no web search, browser, image or voice service, skill hub, connection to another service,
# sub-agent, scheduled job or screen control. The update check and the sending of usage figures are off. The Pod also has
# no way out to the network (PupsWithAuditDataExfiltration_261019_oo01); this closes the tools one more time, where they start.
{
  if [ -n "${HERMES_BASE_URL:-}" ]; then
    printf 'model:\n  default: "%s"\n  provider: custom\n  base_url: "%s"\n  api_key: "local"\n' "${HERMES_MODEL:-}" "$HERMES_BASE_URL"
  fi
  cat <<'YAML'
platform_toolsets:
  cli: [terminal, file, todo, clarify, session_search]
agent:
  disabled_toolsets: [web, browser, code_execution, vision, video, image_gen, video_gen, x_search, tts, stt, skills, kanban, memory, context_engine, connections, delegation, cronjob, yuanbao, computer_use, a2a]
updates:
  check: false
telemetry:
  shared_metrics:
    enabled: false
    send: false
YAML
} > "$HERMES_HOME/config.yaml"
cp /etc/motd-terminal /tmp/motd 2>/dev/null
# rendererType=dom draws the text as page elements, so that it can be selected, searched and read by a test.
exec ttyd -W -p 8080 -t rendererType=dom -t disableLeaveAlert=true -t fontSize=15 \
  bash -c 'cat /tmp/motd; exec bash -l'
