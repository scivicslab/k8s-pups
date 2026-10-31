package com.scivicslab.k8spups.plugin;

/**
 * Tool plugin for a Fedora 44 remote desktop, to try software on Fedora 44 (fcitx5-slm-ime is installed).
 *
 * The image (docker/fedora-desktop) is built as the Remote Desktop image is: MATE on TigerVNC, guacd and Tomcat with the
 * Guacamole web app on port 8080, all in one container as UID 1000. Everything but the name, the description and the
 * image is that of {@link GuacamolePlugin}.
 */
public class FedoraDesktopPlugin extends GuacamolePlugin {

    @Override
    public String name() {
        return "fedora-desktop";
    }

    @Override
    public String displayName() {
        return "Fedora 44 Desktop";
    }

    @Override
    public String description() {
        return "Browser-based Fedora 44 desktop (MATE) via Apache Guacamole, with the Japanese input method fcitx5-slm-ime.";
    }

    @Override
    public String containerImage() {
        return "${REGISTRY}/fedora-desktop:0.1.2-2610311325";
    }
}
