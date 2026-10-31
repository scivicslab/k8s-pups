package com.scivicslab.k8spups.plugin;

/**
 * Tool plugin for a Debian unstable remote desktop, to try software on Debian unstable (fcitx5-slm-ime is installed).
 *
 * The image (docker/debian-desktop) is built as the Remote Desktop image is: MATE on TigerVNC, guacd and Tomcat with the
 * Guacamole web app on port 8080, all in one container as UID 1000. Everything but the name, the description and the
 * image is that of {@link GuacamolePlugin}.
 */
public class DebianDesktopPlugin extends GuacamolePlugin {

    @Override
    public String name() {
        return "debian-desktop";
    }

    @Override
    public String displayName() {
        return "Debian unstable Desktop";
    }

    @Override
    public String description() {
        return "Browser-based Debian unstable desktop (MATE) via Apache Guacamole, with the Japanese input method fcitx5-slm-ime.";
    }

    @Override
    public String containerImage() {
        return "${REGISTRY}/debian-desktop:0.1.0-2610311333";
    }
}
