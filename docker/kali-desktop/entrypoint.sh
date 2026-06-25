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

# Disable X11-level screensaver and DPMS immediately after Xvnc starts.
DISPLAY=:${DISPLAY_NUM} xset s off
DISPLAY=:${DISPLAY_NUM} xset -dpms

# Set X11 root window to a visible color immediately so the VNC frame is never
# all-black while MATE initializes.
DISPLAY=:${DISPLAY_NUM} xsetroot -solid '#1a1a2e'

# Start a persistent dbus session. Without --exit-with-session, dbus outlives
# mate-session: if MATE crashes and restarts, it reconnects to the same bus.
eval $(dbus-launch --sh-syntax)
export DBUS_SESSION_BUS_ADDRESS
echo "dbus started: $DBUS_SESSION_BUS_ADDRESS"

export DISPLAY=:${DISPLAY_NUM}

# Dark solid background (no Kali wallpaper in Ubuntu base; ubuntu-mate-wallpapers
# are available but we keep the security-oriented dark theme)
gsettings set org.mate.background picture-options 'none' 2>/dev/null || true
gsettings set org.mate.background color-shading-type solid 2>/dev/null || true
gsettings set org.mate.background primary-color '#1a1a2e' 2>/dev/null || true

# Start MATE desktop session.
# mate-session starts its required components automatically:
#   marco (window manager), mate-panel, caja (file manager + desktop), mate-settings-daemon
# Ubuntu 24.04's MATE packages handle missing UDisks2 gracefully (warning only),
# unlike Kali rolling which crashes and respawns caja at ~17 instances/second.
cd "$HOME"
DISPLAY=:${DISPLAY_NUM} mate-session &
echo "MATE session started"

sleep 5

# Kill components that crash or disrupt in a container environment.
# mate-screensaver: activates after idle and blacks out the VNC framebuffer.
# mate-power-manager: depends on UPower which is absent in containers; crashes the MATE session.
DISPLAY=:${DISPLAY_NUM} pkill -f mate-screensaver 2>/dev/null || true
DISPLAY=:${DISPLAY_NUM} pkill -f mate-power-manager 2>/dev/null || true
echo "Disabled screensaver and power manager"

sleep 1

# Start guacd (Guacamole protocol daemon) on localhost:4822
guacd -b 127.0.0.1 -l 4822 -L info -f &
echo "guacd started"

sleep 1

# Start Tomcat (foreground; serves Guacamole web app on port 8080)
echo "Starting Tomcat (Guacamole) on port 8080..."
exec ${CATALINA_HOME}/bin/catalina.sh run
