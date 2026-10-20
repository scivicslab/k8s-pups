#!/bin/bash
# Start the three processes of the code-raptor tool and stop the Pod's container when any of them ends.
#   code-raptor  127.0.0.1:8081  search over /home/devteam/works
#   code-server  127.0.0.1:8443  the code editor, served under /editor/ by nginx
#   nginx        0.0.0.0:8080    the one port the controller proxies
# CODE_RAPTOR_BROKER_URL and CODE_RAPTOR_MODEL select the model of the AI Chat tab (GPU broker base URL, no /v1).
# CODE_RAPTOR_EDITOR_URL is where code-raptor's links to the editor point, relative to its page (default: editor/).

java \
  -Dquarkus.http.host=127.0.0.1 \
  -Dquarkus.http.port=8081 \
  -Duser.home=/home/devteam \
  -Dcode.raptor.works-dir=/home/devteam/works \
  -Dcode.raptor.editor-url="${CODE_RAPTOR_EDITOR_URL:-editor/}" \
  ${CODE_RAPTOR_BROKER_URL:+-Dcode.raptor.chat.broker-url="$CODE_RAPTOR_BROKER_URL"} \
  ${CODE_RAPTOR_MODEL:+-Dcode.raptor.chat.model="$CODE_RAPTOR_MODEL"} \
  -jar /opt/code-raptor/code-raptor.jar &

# Access is decided by the controller (login and ownership of the session), so the editor has no password of its own.
# Downloads and uploads through the editor are closed: the data of the session must not leave through its screen.
code-server \
  --bind-addr 127.0.0.1:8443 \
  --auth none \
  --user-data-dir /home/devteam/.local/share/code-server \
  --extensions-dir /home/devteam/.local/share/code-server/extensions \
  --disable-telemetry \
  --disable-update-check \
  --disable-file-downloads \
  --disable-file-uploads \
  --disable-getting-started-override \
  /home/devteam/works &

nginx -c /etc/nginx/pod-nginx.conf -g 'daemon off;' &

# Wait for the first of the three to end, then stop the others with it (the Pod restarts a container that exits).
wait -n
status=$?
kill 0 2>/dev/null
exit $status
