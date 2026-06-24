#!/bin/bash

mkdir -p "$HOME/.config" "$HOME/.cache" "$HOME/.local/share" "$HOME/data"

DISPLAY_NUM=1
VNC_PORT=5901

# Start TigerVNC (Xvnc) — no VNC password (Envoy Gateway handles auth)
Xvnc :${DISPLAY_NUM} \
    -geometry 1920x1080 \
    -depth 24 \
    -SecurityTypes None \
    -rfbport ${VNC_PORT} \
    -localhost yes &
echo "Xvnc started on :${DISPLAY_NUM}"

sleep 3

# Start XFCE4 desktop session
export DISPLAY=:${DISPLAY_NUM}
cd "$HOME"
dbus-launch --exit-with-session startxfce4 &
echo "XFCE4 session started"

sleep 3

# Start guacd (Guacamole protocol daemon) on localhost:4822
guacd -b 127.0.0.1 -l 4822 -L info -f &
echo "guacd started"

sleep 1

# Start Tomcat (foreground; serves Guacamole web app on port 8080)
echo "Starting Tomcat (Guacamole) on port 8080..."
exec ${CATALINA_HOME}/bin/catalina.sh run
