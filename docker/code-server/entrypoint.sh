#!/bin/sh
# Starts the code editor on the one port that the controller proxies.
# Access is decided by the controller (login and ownership of the session), so the editor has no password of its own.
# Downloads and uploads through the editor are closed: the data of the session must not leave through its screen.
exec code-server \
  --bind-addr 0.0.0.0:8080 \
  --auth none \
  --user-data-dir /home/devteam/.local/share/code-server \
  --extensions-dir /home/devteam/.local/share/code-server/extensions \
  --disable-telemetry \
  --disable-update-check \
  --disable-file-downloads \
  --disable-file-uploads \
  --disable-getting-started-override \
  /home/devteam/works
