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
# all-black while MATE initializes. This is visible even before caja renders.
DISPLAY=:${DISPLAY_NUM} xsetroot -solid '#1a1a2e'

# Start a persistent dbus session. Without --exit-with-session, dbus outlives
# mate-session: if MATE crashes and restarts, it reconnects to the same bus.
eval $(dbus-launch --sh-syntax)
export DBUS_SESSION_BUS_ADDRESS
echo "dbus started: $DBUS_SESSION_BUS_ADDRESS"

export DISPLAY=:${DISPLAY_NUM}

# Pre-configure MATE background before the session starts.
# picture-options valid enum: wallpaper|zoom|centered|scaled|stretched|spanned
# ('none' is NOT valid in Kali's MATE 1.26 and silently falls back to default)
gsettings set org.mate.background picture-options 'wallpaper' 2>/dev/null || true
gsettings set org.mate.background picture-filename '/usr/share/backgrounds/kali/kali-cubes-16x9.jpg' 2>/dev/null || true
gsettings set org.mate.background color-shading-type solid 2>/dev/null || true
gsettings set org.mate.background primary-color '#1a1a2e' 2>/dev/null || true

# Disable gdk-pixbuf/glycin bwrap sandboxing.
# Kali's MATE uses glycin image loaders that call "bwrap --unshare-all" to sandbox icon loading.
# In a k8s container, CLONE_NEWUSER is blocked, so bwrap fails.  This crashes marco (window
# manager) and mate-settings-daemon before the desktop renders, leaving only the xsetroot
# background visible.  Setting these env vars tells glycin to load images directly without bwrap.
export GLYCIN_SANDBOX=None
export GDK_PIXBUF_DISABLE_SANDBOX=1

# Start MATE desktop session.
# mate-session starts its required components automatically:
#   marco (window manager), mate-panel, caja (file manager + desktop), mate-settings-daemon
# Do NOT launch caja separately — a duplicate instance exits immediately, removing desktop rendering.
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
