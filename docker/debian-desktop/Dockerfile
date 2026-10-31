# A Debian (unstable) desktop for k8s-pups, to try fcitx5-slm-ime on Debian: MATE on TigerVNC, shown in the browser by
# Guacamole, as the Remote Desktop tool (docker/guacamole-desktop) does on Ubuntu. Debian has no guacd, so the first
# stage builds it from the Guacamole source (VNC only). Tool Pods cannot reach the internet, so the Debian packages of
# fcitx5-slm-ime (built with dpkg-buildpackage on Debian unstable) are put into the image.

# ---- Stage 1: guacd from source ----
FROM debian:sid AS guacd
ARG DEBIAN_FRONTEND=noninteractive
ARG GUAC_VERSION=1.6.0
RUN apt-get update && apt-get install -y --no-install-recommends \
        build-essential pkg-config libtool-bin wget ca-certificates \
        libcairo2-dev libjpeg62-turbo-dev libpng-dev uuid-dev libvncserver-dev libwebp-dev libssl-dev \
    && rm -rf /var/lib/apt/lists/*
RUN wget -q "https://archive.apache.org/dist/guacamole/${GUAC_VERSION}/source/guacamole-server-${GUAC_VERSION}.tar.gz" \
        -O /tmp/guac.tar.gz && \
    tar -xzf /tmp/guac.tar.gz -C /tmp && cd /tmp/guacamole-server-${GUAC_VERSION} && \
    # GCC of Debian unstable warns where the 1.6.0 source has not caught up, and the Makefiles add -Werror: -Wno-error
    # in CFLAGS comes after it on the command line
    ./configure CFLAGS="-O2 -Wno-error" --prefix=/opt/guacd --disable-guacenc --disable-guaclog --without-rdp --without-ssh --without-telnet \
        --without-kubernetes --without-pulse --without-libavcodec --without-libavformat --without-libavutil \
        --without-libswscale && \
    make -j"$(nproc)" && make install

# ---- Stage 2: the desktop ----
FROM debian:sid
ARG DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
        mate-desktop-environment-core mate-terminal caja pluma \
        locales fonts-noto-cjk fcitx5 fcitx5-config-qt fcitx5-frontend-gtk3 fcitx5-frontend-qt5 \
        tigervnc-standalone-server openjdk-21-jre-headless \
        libcairo2 libjpeg62-turbo libpng16-16t64 libuuid1 libvncclient1 libwebp7 \
        dbus-x11 x11-xserver-utils x11-xkb-utils xdotool imagemagick procps wget unzip ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# ---- guacd from stage 1 ----
COPY --from=guacd /opt/guacd /opt/guacd
RUN echo /opt/guacd/lib > /etc/ld.so.conf.d/guacd.conf && ldconfig && ln -s /opt/guacd/sbin/guacd /usr/local/sbin/guacd

# ---- fcitx5-slm-ime (the engine and the model) ----
COPY fcitx5-slm-ime_*.deb slm-ime-model_*.deb /tmp/debs/
RUN apt-get update && apt-get install -y --no-install-recommends /tmp/debs/*.deb && \
    rm -rf /var/lib/apt/lists/* /tmp/debs

# ---- glycin, the image loaders of GTK, in a Pod (see docker/fedora-desktop/Dockerfile) ----
RUN printf '#!/bin/sh\necho "bwrap: No permissions to create a new namespace" >&2\nexit 1\n' > /usr/local/bin/bwrap && \
    chmod 755 /usr/local/bin/bwrap

# ---- Japanese locale, JST ----
RUN sed -i 's/^# *ja_JP.UTF-8 UTF-8/ja_JP.UTF-8 UTF-8/' /etc/locale.gen && locale-gen && \
    ln -sf /usr/share/zoneinfo/Asia/Tokyo /etc/localtime && echo Asia/Tokyo > /etc/timezone
ENV LANG=ja_JP.UTF-8
ENV LANGUAGE=ja_JP:ja
ENV LC_ALL=ja_JP.UTF-8

# ---- Non-root user (UID 1000) ----
RUN useradd -u 1000 -m -d /home/user -s /bin/bash user

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

# ---- Guacamole web app, the version of the guacd built above ----
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
