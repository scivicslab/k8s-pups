#!/bin/sh
# Start code-raptor on port 8080 over /home/devteam/works.
# CODE_RAPTOR_BROKER_URL and CODE_RAPTOR_MODEL select the model of the AI Chat tab (GPU broker base URL, no /v1).
exec java \
  -Dquarkus.http.host=0.0.0.0 \
  -Dquarkus.http.port=8080 \
  -Duser.home=/home/devteam \
  -Dcode.raptor.works-dir=/home/devteam/works \
  ${CODE_RAPTOR_BROKER_URL:+-Dcode.raptor.chat.broker-url="$CODE_RAPTOR_BROKER_URL"} \
  ${CODE_RAPTOR_MODEL:+-Dcode.raptor.chat.model="$CODE_RAPTOR_MODEL"} \
  -jar /opt/code-raptor/code-raptor.jar
