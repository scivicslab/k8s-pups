# A Fedora desktop for k8s-pups, to try fcitx5-slm-ime on Fedora: MATE on TigerVNC, shown in the browser by Guacamole
# (guacd + Tomcat 9), as the Remote Desktop tool (docker/guacamole-desktop) does on Ubuntu. Tool Pods cannot reach the
# internet, so the RPMs of fcitx5-slm-ime (built by packaging/fedora/fcitx5-slm-ime.spec) are put into the image.
FROM fedora:44

# ---- Translations: the Fedora container image installs English only (%_install_langs); the desktop is Japanese ----
RUN rm -f /etc/rpm/macros.image-language-conf && \
    sed -i '/^tsflags=nodocs/d' /etc/dnf/dnf.conf 2>/dev/null; true

# ---- Desktop, VNC, Guacamole daemon, Japanese ----
RUN dnf -y install --setopt=install_weak_deps=False \
        mate-session-manager mate-panel marco caja mate-terminal pluma mate-settings-daemon \
        mate-notification-daemon mate-control-center mate-themes mate-icon-theme mate-backgrounds \
        tigervnc-x11-server guacd libguac-client-vnc java-25-openjdk-headless \
        fcitx5 fcitx5-gtk fcitx5-qt fcitx5-configtool \
        google-noto-sans-cjk-fonts glibc-langpack-ja langpacks-ja \
        dbus-x11 setxkbmap xset xdotool ImageMagick procps-ng wget unzip \
    && dnf clean all

# ---- fcitx5-slm-ime (the engine and the model) ----
COPY fcitx5-slm-ime-*.rpm slm-ime-model-*.rpm /tmp/rpms/
RUN dnf -y install /tmp/rpms/*.rpm && dnf clean all && rm -rf /tmp/rpms

# ---- glycin, the image loaders of GTK, in a Pod ----
# glycin runs every loader inside bwrap, which cannot create namespaces in a k8s Pod ("Failed to make / slave:
# Permission denied"); then no icon loads and the MATE panel and settings daemon abort. glycin runs the loaders
# without its sandbox only when bwrap fails with a message it knows, so this bwrap, first in PATH, fails with one of
# them. The Pod is the sandbox of this one-user desktop.
RUN printf '#!/bin/sh\necho "bwrap: No permissions to create a new namespace" >&2\nexit 1\n' > /usr/local/bin/bwrap && \
    chmod 755 /usr/local/bin/bwrap

# ---- Japanese locale, JST ----
ENV LANG=ja_JP.UTF-8
ENV LANGUAGE=ja_JP:ja
ENV LC_ALL=ja_JP.UTF-8
RUN ln -sf /usr/share/zoneinfo/Asia/Tokyo /etc/localtime

# ---- Non-root user (UID 1000) ----
RUN useradd -u 1000 -m -d /home/user user

# ---- Tomcat 9 (tarball; Guacamole uses javax.servlet, which Tomcat 10+ does not have) ----
ARG TOMCAT_VERSION=9.0.102
RUN wget -q "https://archive.apache.org/dist/tomcat/tomcat-9/v${TOMCAT_VERSION}/bin/apache-tomcat-${TOMCAT_VERSION}.tar.gz" \
        -O /tmp/tomcat.tar.gz && \
    tar -xzf /tmp/tomcat.tar.gz -C /opt && \
    mv /opt/apache-tomcat-${TOMCAT_VERSION} /opt/tomcat && \
    rm -f /tmp/tomcat.tar.gz && rm -rf /opt/tomcat/webapps/*
ENV CATALINA_HOME=/opt/tomcat
RUN sed -i 's|Connector port="8080" protocol="HTTP/1.1"|Connector port="8080" protocol="HTTP/1.1" maxHttpHeaderSize="65536"|' \
    /opt/tomcat/conf/server.xml

# ---- Guacamole web app, the version of Fedora 44's guacd (1.6.0) ----
ARG GUAC_VERSION=1.6.0
RUN mkdir -p /opt/tomcat/webapps/ROOT && \
    wget -q "https://archive.apache.org/dist/guacamole/${GUAC_VERSION}/binary/guacamole-${GUAC_VERSION}.war" \
        -O /tmp/guacamole.war && \
    unzip -q /tmp/guacamole.war -d /opt/tomcat/webapps/ROOT && \
    rm /tmp/guacamole.war
ENV GUACAMOLE_HOME=/etc/guacamole
COPY guacamole.properties /etc/guacamole/guacamole.properties
COPY user-mapping.xml /etc/guacamole/user-mapping.xml
COPY auto-login.js /opt/tomcat/webapps/ROOT/auto-login.js
RUN sed -i 's|</head>|<script src="auto-login.js"></script></head>|' /opt/tomcat/webapps/ROOT/index.html

# ---- Ownership; the Pod may run as another UID ----
RUN chown -R user:user /opt/tomcat /etc/guacamole /home/user && \
    chmod -R o+rX /opt/tomcat && chmod -R o+rwX /opt/tomcat/logs /opt/tomcat/temp /opt/tomcat/work /etc/guacamole

# ---- fcitx5 default profile: SLM IME ----
RUN mkdir -p /etc/skel/.config/fcitx5
COPY fcitx5-profile /etc/skel/.config/fcitx5/profile

# ---- An entry for an arbitrary UID (readOnlyRootFilesystem=false) ----
RUN chmod 666 /etc/passwd /etc/group

COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

EXPOSE 8080
CMD ["/usr/local/bin/entrypoint.sh"]
