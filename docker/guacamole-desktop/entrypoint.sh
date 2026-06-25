#!/bin/bash

# Fix PVC-mounted home directory permissions.
# When a persistent PVC is mounted at /home/user, directories from previous sessions
# (e.g. JupyterLab) may be owned by root, preventing MATE components from writing.
for dir in .config .cache .local .dbus .gvfs; do
    target="$HOME/$dir"
    if [ -d "$target" ] && [ ! -w "$target" ]; then
        rmdir "$target" 2>/dev/null && mkdir -p "$target" \
            || echo "Warning: could not fix permissions on $target"
    fi
done
mkdir -p "$HOME/.config" "$HOME/.cache" "$HOME/.local/share"

# Copy default fcitx5 profile (Mozc) if not present
if [ ! -f "$HOME/.config/fcitx5/profile" ]; then
    mkdir -p "$HOME/.config/fcitx5"
    cp /etc/skel/.config/fcitx5/profile "$HOME/.config/fcitx5/profile"
    echo "Copied default fcitx5 profile (Mozc)"
fi

DISPLAY_NUM=1
VNC_PORT=5901

# Start TigerVNC (Xvnc) with Japanese keyboard layout
# -SecurityTypes None: no VNC password (Envoy Gateway handles auth)
Xvnc :${DISPLAY_NUM} \
    -geometry 1920x1080 \
    -depth 24 \
    -SecurityTypes None \
    -rfbport ${VNC_PORT} \
    -localhost yes &
echo "Xvnc started on :${DISPLAY_NUM}"

sleep 3

# Set Japanese keyboard layout via xkb
DISPLAY=:${DISPLAY_NUM} setxkbmap -layout jp -option

# Disable X11-level screensaver and DPMS immediately after Xvnc starts.
# xset s off: disables the X11 built-in screensaver (separate from mate-screensaver).
# xset -dpms: disables Display Power Management Signaling on the virtual display.
# Without these, the X11 idle timer triggers at the OS level independently of MATE settings,
# blanking the VNC framebuffer and causing guacd to report a "Guacamole internal error".
DISPLAY=:${DISPLAY_NUM} xset s off
DISPLAY=:${DISPLAY_NUM} xset -dpms

# Start a persistent dbus session. Without --exit-with-session, dbus outlives
# mate-session: if MATE crashes and restarts, it reconnects to the same bus
# instead of orphaning guacd/Xvnc.
eval $(dbus-launch --sh-syntax)
export DBUS_SESSION_BUS_ADDRESS
echo "dbus started: $DBUS_SESSION_BUS_ADDRESS"

# Pre-configure MATE settings via dconf before the session starts.
# gsettings requires DBUS_SESSION_BUS_ADDRESS, which is now set.
export DISPLAY=:${DISPLAY_NUM}

# Theme and wallpaper
gsettings set org.mate.background picture-filename \
    /usr/share/backgrounds/ubuntu-mate-noble/numbat_wallpaper_dimmed_3480x2160.jpg
gsettings set org.mate.interface gtk-theme Yaru-MATE-dark
gsettings set org.mate.Marco.general theme Yaru-MATE-dark
gsettings set org.mate.interface icon-theme Yaru-MATE-dark

# Disable screensaver entirely: in a VNC session, screensaver activation
# causes the VNC framebuffer to go black and confuses guacd, leading to
# "internal error" disconnects.
gsettings set org.mate.screensaver idle-activation-enabled false
gsettings set org.mate.screensaver lock-enabled false

# Disable power management sleep: mate-power-manager cannot communicate with
# UPower in a container (no hardware), causing periodic crashes that kill the
# MATE session and disconnect Guacamole.
gsettings set org.mate.power-manager sleep-display-ac 0 2>/dev/null || true
gsettings set org.mate.power-manager sleep-computer-ac 0 2>/dev/null || true

# Start MATE desktop session with Japanese locale and fcitx5-mozc IME.
# cd $HOME so terminal windows open in the user's home directory (PVC mount).
cd "$HOME"
DISPLAY=:${DISPLAY_NUM} \
LANG=ja_JP.UTF-8 \
LC_ALL=ja_JP.UTF-8 \
GTK_IM_MODULE=fcitx \
QT_IM_MODULE=fcitx \
XMODIFIERS=@im=fcitx \
    mate-session &
echo "MATE session started"

sleep 5

# Kill components that crash in a container environment.
# mate-screensaver: would activate after idle and disrupt the VNC display.
# mate-power-manager: depends on UPower which is absent in containers.
DISPLAY=:${DISPLAY_NUM} pkill -f mate-screensaver 2>/dev/null || true
DISPLAY=:${DISPLAY_NUM} pkill -f mate-power-manager 2>/dev/null || true
echo "Disabled screensaver and power manager"

# Start fcitx5 input method daemon (Mozc)
DISPLAY=:${DISPLAY_NUM} fcitx5 -d --replace 2>/dev/null &
echo "fcitx5 started"

sleep 1

# Start guacd (Guacamole protocol daemon) on localhost:4822
guacd -b 127.0.0.1 -l 4822 -L info -f &
echo "guacd started"

sleep 1

# Start Tomcat (foreground; serves Guacamole web app on port 8080)
echo "Starting Tomcat (Guacamole) on port 8080..."
exec ${CATALINA_HOME}/bin/catalina.sh run
