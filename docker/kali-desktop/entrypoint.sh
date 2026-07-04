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

# Set X11 root window to a visible color immediately so the VNC frame is never
# all-black while MATE initializes.
DISPLAY=:${DISPLAY_NUM} xsetroot -solid '#1a1a2e'

# Start a persistent dbus session. Without --exit-with-session, dbus outlives
# mate-session: if MATE crashes and restarts, it reconnects to the same bus.
eval $(dbus-launch --sh-syntax)
export DBUS_SESSION_BUS_ADDRESS
echo "dbus started: $DBUS_SESSION_BUS_ADDRESS"

export DISPLAY=:${DISPLAY_NUM}

# Set up the MATE panel layout via gsettings (no dconf-cli required; dconf-service
# is already running at this point and gsettings writes through it).
# The ubuntu-mate package installs a schema override that sets default-layout='familiar',
# which adds brisk-menu/firefox/etc. applets we don't have installed.  Setting
# toplevel-id-list and object-id-list here before mate-session prevents mate-panel
# from falling back to that layout file.
gsettings set org.mate.panel toplevel-id-list "['top-panel', 'bottom-panel']"
gsettings set org.mate.panel object-id-list "['menu-bar', 'notification-area', 'clock', 'show-desktop', 'window-list', 'workspace-switcher']"

gsettings set org.mate.panel.toplevel:/org/mate/panel/toplevels/top-panel/ expand true
gsettings set org.mate.panel.toplevel:/org/mate/panel/toplevels/top-panel/ orientation 'top'
gsettings set org.mate.panel.toplevel:/org/mate/panel/toplevels/top-panel/ size 24

gsettings set org.mate.panel.toplevel:/org/mate/panel/toplevels/bottom-panel/ expand true
gsettings set org.mate.panel.toplevel:/org/mate/panel/toplevels/bottom-panel/ orientation 'bottom'
gsettings set org.mate.panel.toplevel:/org/mate/panel/toplevels/bottom-panel/ size 24

gsettings set org.mate.panel.object:/org/mate/panel/objects/menu-bar/ object-type 'menu-bar'
gsettings set org.mate.panel.object:/org/mate/panel/objects/menu-bar/ toplevel-id 'top-panel'
gsettings set org.mate.panel.object:/org/mate/panel/objects/menu-bar/ position 0
gsettings set org.mate.panel.object:/org/mate/panel/objects/menu-bar/ panel-right-stick false
gsettings set org.mate.panel.object:/org/mate/panel/objects/menu-bar/ locked true

gsettings set org.mate.panel.object:/org/mate/panel/objects/notification-area/ object-type 'applet'
gsettings set org.mate.panel.object:/org/mate/panel/objects/notification-area/ applet-iid 'NotificationAreaAppletFactory::NotificationArea'
gsettings set org.mate.panel.object:/org/mate/panel/objects/notification-area/ toplevel-id 'top-panel'
gsettings set org.mate.panel.object:/org/mate/panel/objects/notification-area/ position 10
gsettings set org.mate.panel.object:/org/mate/panel/objects/notification-area/ panel-right-stick true
gsettings set org.mate.panel.object:/org/mate/panel/objects/notification-area/ locked true

gsettings set org.mate.panel.object:/org/mate/panel/objects/clock/ object-type 'applet'
gsettings set org.mate.panel.object:/org/mate/panel/objects/clock/ applet-iid 'ClockAppletFactory::ClockApplet'
gsettings set org.mate.panel.object:/org/mate/panel/objects/clock/ toplevel-id 'top-panel'
gsettings set org.mate.panel.object:/org/mate/panel/objects/clock/ position 0
gsettings set org.mate.panel.object:/org/mate/panel/objects/clock/ panel-right-stick true
gsettings set org.mate.panel.object:/org/mate/panel/objects/clock/ locked true

gsettings set org.mate.panel.object:/org/mate/panel/objects/show-desktop/ object-type 'applet'
gsettings set org.mate.panel.object:/org/mate/panel/objects/show-desktop/ applet-iid 'WnckletFactory::ShowDesktopApplet'
gsettings set org.mate.panel.object:/org/mate/panel/objects/show-desktop/ toplevel-id 'bottom-panel'
gsettings set org.mate.panel.object:/org/mate/panel/objects/show-desktop/ position 0
gsettings set org.mate.panel.object:/org/mate/panel/objects/show-desktop/ panel-right-stick false

gsettings set org.mate.panel.object:/org/mate/panel/objects/window-list/ object-type 'applet'
gsettings set org.mate.panel.object:/org/mate/panel/objects/window-list/ applet-iid 'WnckletFactory::WindowListApplet'
gsettings set org.mate.panel.object:/org/mate/panel/objects/window-list/ toplevel-id 'bottom-panel'
gsettings set org.mate.panel.object:/org/mate/panel/objects/window-list/ position 10
gsettings set org.mate.panel.object:/org/mate/panel/objects/window-list/ panel-right-stick false

gsettings set org.mate.panel.object:/org/mate/panel/objects/workspace-switcher/ object-type 'applet'
gsettings set org.mate.panel.object:/org/mate/panel/objects/workspace-switcher/ applet-iid 'WnckletFactory::WorkspaceSwitcherApplet'
gsettings set org.mate.panel.object:/org/mate/panel/objects/workspace-switcher/ toplevel-id 'bottom-panel'
gsettings set org.mate.panel.object:/org/mate/panel/objects/workspace-switcher/ position 0
gsettings set org.mate.panel.object:/org/mate/panel/objects/workspace-switcher/ panel-right-stick true

# Disable screensaver (both MATE and GNOME schemas; neither may be installed,
# so suppress errors).  Also disable GNOME idle activation in case something
# in the MATE session honours the GNOME schema.
gsettings set org.gnome.desktop.screensaver idle-activation-enabled false 2>/dev/null || true

# Dark solid background (no Kali wallpaper in Ubuntu base)
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

# Disable X11-level DPMS and screensaver after MATE (and mate-settings-daemon) has
# fully initialized. mate-settings-daemon may re-enable DPMS during its own startup,
# so applying xset here (post-init) ensures the final state is always disabled.
DISPLAY=:${DISPLAY_NUM} xset s off
DISPLAY=:${DISPLAY_NUM} xset s noblank
DISPLAY=:${DISPLAY_NUM} xset -dpms

# Background loop: re-apply DPMS/screensaver disable every 60 seconds.
# Guards against any component that might re-enable blanking after startup.
(while true; do
    sleep 60
    DISPLAY=:${DISPLAY_NUM} xset s off 2>/dev/null || true
    DISPLAY=:${DISPLAY_NUM} xset s noblank 2>/dev/null || true
    DISPLAY=:${DISPLAY_NUM} xset -dpms 2>/dev/null || true
    DISPLAY=:${DISPLAY_NUM} pkill -f mate-screensaver 2>/dev/null || true
done) &

sleep 1

# Start guacd (Guacamole protocol daemon) on localhost:4822
guacd -b 127.0.0.1 -l 4822 -L info -f &
echo "guacd started"

sleep 1

# Start Tomcat (foreground; serves Guacamole web app on port 8080)
echo "Starting Tomcat (Guacamole) on port 8080..."
exec ${CATALINA_HOME}/bin/catalina.sh run
