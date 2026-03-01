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

# Apply Ubuntu MATE theme and wallpaper before session starts
export DISPLAY=:${DISPLAY_NUM}
gsettings set org.mate.background picture-filename /usr/share/backgrounds/ubuntu-mate-noble/numbat_wallpaper_dimmed_3480x2160.jpg
gsettings set org.mate.interface gtk-theme Yaru-MATE-dark
gsettings set org.mate.Marco.general theme Yaru-MATE-dark
gsettings set org.mate.interface icon-theme Yaru-MATE-dark

# Start MATE desktop session with Japanese locale and fcitx5-mozc IME
# cd $HOME so terminal windows open in the user's home directory (PVC mount)
cd "$HOME"
DISPLAY=:${DISPLAY_NUM} \
LANG=ja_JP.UTF-8 \
LC_ALL=ja_JP.UTF-8 \
GTK_IM_MODULE=fcitx \
QT_IM_MODULE=fcitx \
XMODIFIERS=@im=fcitx \
    dbus-launch --exit-with-session mate-session &
echo "MATE session started"

sleep 2

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
