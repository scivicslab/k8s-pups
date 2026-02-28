#!/bin/bash
# Sidecar entrypoint: Desktop only (VNC + MATE).
# Runs as LDAP UID with NFS home directory mounted.

# Ensure current UID/GID exist in /etc/passwd and /etc/group.
# Required for dbus-launch and MATE, which resolve the current user via NSS.
# In sidecar mode the container runs as a LDAP UID (e.g. 1002) that does not
# exist in the image's /etc/passwd.
CURRENT_UID=$(id -u)
CURRENT_GID=$(id -g)
if ! id -un "$CURRENT_UID" >/dev/null 2>&1; then
    echo "desktop:x:${CURRENT_UID}:${CURRENT_GID}:Desktop User:${HOME}:/bin/bash" >> /etc/passwd
    echo "Added passwd entry for UID ${CURRENT_UID}"
fi
if ! getent group "$CURRENT_GID" >/dev/null 2>&1; then
    echo "desktop:x:${CURRENT_GID}:" >> /etc/group
    echo "Added group entry for GID ${CURRENT_GID}"
fi

# Fix PVC-mounted home directory permissions.
# Directories from previous sessions may be owned by a different user,
# preventing MATE components from writing.
for dir in .config .cache .local .dbus .gvfs; do
    target="$HOME/$dir"
    if [ -d "$target" ] && [ ! -w "$target" ]; then
        rmdir "$target" 2>/dev/null && mkdir -p "$target" \
            || echo "Warning: could not fix permissions on $target"
    fi
done
mkdir -p "$HOME/.config" "$HOME/.cache" "$HOME/.local/share"

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
# cd $HOME so terminal windows open in the user's home directory (NFS mount)
cd "$HOME"
DISPLAY=:${DISPLAY_NUM} \
LANG=ja_JP.UTF-8 \
LC_ALL=ja_JP.UTF-8 \
GTK_IM_MODULE=fcitx \
QT_IM_MODULE=fcitx \
XMODIFIERS=@im=fcitx \
    dbus-launch --exit-with-session mate-session &
echo "MATE session started"

# Keep container alive while background processes are running
wait
