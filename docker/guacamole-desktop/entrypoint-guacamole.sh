#!/bin/bash
# Sidecar entrypoint: Guacamole gateway only (guacd + Tomcat).
# Runs as UID 1000 (image default). No desktop services.
# Connects to VNC at 127.0.0.1:5901 via shared Pod network namespace.

# Start guacd (Guacamole protocol daemon) on localhost:4822
guacd -b 127.0.0.1 -l 4822 -L info -f &
echo "guacd started"

sleep 1

# Start Tomcat (foreground; serves Guacamole web app on port 8080)
echo "Starting Tomcat (Guacamole) on port 8080..."
exec ${CATALINA_HOME}/bin/catalina.sh run
