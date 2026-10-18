#!/bin/sh
# Start chat-ui-with-audit-trail on port 8080 with the web-tools and shell plugins.
# CHAT_UI_SERVERS is the OpenAI-compatible server (the GPU broker base URL, http://host:port, no /v1).
# The H2 database of the conversation records (chat-ui-iolog-*.mv.db) is created in the working directory,
# which is the user's ~/works, so the records stay with the user.
exec java \
  -Dquarkus.http.host=0.0.0.0 \
  -Dquarkus.http.port=8080 \
  -Duser.home=/home/devteam \
  -Dchat-ui.servers="${CHAT_UI_SERVERS:-http://localhost:28005}" \
  ${CHAT_UI_DEFAULT_MODEL:+-Dchat-ui.default-model="$CHAT_UI_DEFAULT_MODEL"} \
  -Dchat-ui.plugins=/opt/chat-ui/chat-ui-with-audit-trail-plugin-web-tools.jar,/opt/chat-ui/chat-ui-with-audit-trail-plugin-shell.jar \
  -jar /opt/chat-ui/chat-ui-with-audit-trail.jar
