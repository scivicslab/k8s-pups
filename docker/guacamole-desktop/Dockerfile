FROM ubuntu:24.04

ARG DEBIAN_FRONTEND=noninteractive

# ---- Desktop environment + Guacamole deps + Japanese ----
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        # MATE desktop (core + Ubuntu MATE theming)
        mate-desktop-environment-core \
        mate-terminal \
        caja \
        pluma \
        eom \
        ubuntu-mate-wallpapers \
        ubuntu-mate-themes \
        # Japanese support
        language-pack-ja \
        fonts-noto-cjk \
        fcitx5 \
        fcitx5-mozc \
        fcitx5-config-qt \
        # VNC server
        tigervnc-standalone-server \
        # Guacamole server daemon + VNC client library
        guacd \
        libguac-client-vnc0 \
        # Java runtime for Tomcat
        openjdk-17-jre-headless \
        # WAR extraction
        unzip \
        # Utilities
        dbus-x11 \
        xdg-user-dirs \
        x11-xserver-utils \
        wget \
        curl \
    && rm -rf /var/lib/apt/lists/*

# ---- Japanese locale ----
RUN locale-gen ja_JP.UTF-8 && \
    update-locale LANG=ja_JP.UTF-8

ENV LANG=ja_JP.UTF-8
ENV LANGUAGE=ja_JP:ja
ENV LC_ALL=ja_JP.UTF-8

# ---- Timezone (JST) ----
RUN ln -sf /usr/share/zoneinfo/Asia/Tokyo /etc/localtime && \
    echo "Asia/Tokyo" > /etc/timezone

# ---- Non-root user (UID 1000) ----
# Ubuntu 24.04 already has UID 1000 (ubuntu); rename to user.
RUN usermod -l user ubuntu && \
    groupmod -n user ubuntu && \
    usermod -d /home/user -m user

# ---- Tomcat 9 (tarball; runs as UID 1000) ----
# Guacamole 1.5.x uses javax.servlet (Java EE), which requires Tomcat 9.
# Tomcat 10+ uses jakarta.servlet (Jakarta EE) and is NOT compatible.
ARG TOMCAT_VERSION=9.0.102
RUN wget -q \
        "https://archive.apache.org/dist/tomcat/tomcat-9/v${TOMCAT_VERSION}/bin/apache-tomcat-${TOMCAT_VERSION}.tar.gz" \
        -O /tmp/tomcat.tar.gz && \
    tar -xzf /tmp/tomcat.tar.gz -C /opt && \
    mv /opt/apache-tomcat-${TOMCAT_VERSION} /opt/tomcat && \
    rm -f /tmp/tomcat.tar.gz && \
    rm -rf /opt/tomcat/webapps/*

ENV CATALINA_HOME=/opt/tomcat
ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64

# Increase max HTTP header size to 64KB for OIDC JWT tokens forwarded by Envoy Gateway
RUN sed -i 's|Connector port="8080" protocol="HTTP/1.1"|Connector port="8080" protocol="HTTP/1.1" maxHttpHeaderSize="65536"|' \
    /opt/tomcat/conf/server.xml

# ---- Guacamole web app: extract to directory so we can inject auto-connect page ----
# Version must match Ubuntu 24.04's guacd package (1.5.4)
ARG GUAC_VERSION=1.5.4
RUN mkdir -p /opt/tomcat/webapps/ROOT && \
    wget -q \
        "https://downloads.apache.org/guacamole/${GUAC_VERSION}/binary/guacamole-${GUAC_VERSION}.war" \
        -O /tmp/guacamole.war && \
    unzip -q /tmp/guacamole.war -d /opt/tomcat/webapps/ROOT && \
    rm /tmp/guacamole.war

ENV GUACAMOLE_HOME=/etc/guacamole

# ---- Guacamole configuration + user-mapping (no-password auth) ----
COPY guacamole.properties /etc/guacamole/guacamole.properties
COPY user-mapping.xml /etc/guacamole/user-mapping.xml

# ---- Auto-login: JS file + script tag injection ----
COPY auto-login.js /opt/tomcat/webapps/ROOT/auto-login.js
RUN sed -i 's|</head>|<script src="auto-login.js"></script></head>|' \
    /opt/tomcat/webapps/ROOT/index.html

# ---- Ownership: all files under UID 1000 ----
RUN chown -R user:user /opt/tomcat /etc/guacamole /home/user

# ---- Allow arbitrary UID in sidecar mode ----
# When the desktop container runs as a LDAP UID (e.g. 1002), it needs to add
# a passwd/group entry so dbus and MATE can resolve the current user.
# readOnlyRootFilesystem=false, so these files are writable at runtime.
RUN chmod 666 /etc/passwd /etc/group

# ---- entrypoints ----
# Main entrypoint: all-in-one mode (single container, no workspace)
COPY entrypoint.sh /usr/local/bin/entrypoint.sh
# Sidecar entrypoints: split mode (workspace with NFS mount)
# - entrypoint-desktop.sh: VNC + MATE only (runs as LDAP UID)
# - entrypoint-guacamole.sh: guacd + Tomcat only (runs as UID 1000)
COPY entrypoint-desktop.sh /usr/local/bin/entrypoint-desktop.sh
COPY entrypoint-guacamole.sh /usr/local/bin/entrypoint-guacamole.sh
RUN chmod +x /usr/local/bin/entrypoint.sh \
             /usr/local/bin/entrypoint-desktop.sh \
             /usr/local/bin/entrypoint-guacamole.sh

EXPOSE 8080

# k8s-pups sets readOnlyRootFilesystem=false, runAsUser=1000 for this plugin.
CMD ["/usr/local/bin/entrypoint.sh"]
