FROM ubuntu:24.04

ARG DEBIAN_FRONTEND=noninteractive

# ---- Desktop + Guacamole core ----
# Ubuntu 24.04 ships guacd 1.5.4 as a native package — no source build needed.
# Ubuntu's MATE packages fail gracefully when UDisks2 is absent in k8s containers
# (prints a warning, continues). Kali rolling packages crash instead, causing
# caja to respawn at ~17 instances/second until PID exhaustion → disconnect.
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        mate-desktop-environment-core \
        mate-terminal \
        caja \
        ubuntu-mate-themes \
        ubuntu-mate-wallpapers \
        tigervnc-standalone-server \
        guacd \
        libguac-client-vnc0 \
        openjdk-17-jre-headless \
        unzip \
        wget \
        curl \
        procps \
        dbus-x11 \
        x11-xserver-utils \
        xdg-user-dirs \
        adwaita-icon-theme \
        hicolor-icon-theme \
        libgdk-pixbuf2.0-bin \
        shared-mime-info \
        fonts-noto-cjk \
    && rm -rf /var/lib/apt/lists/*

# ---- Security tools (Ubuntu 24.04 universe) ----
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        nmap \
        wireshark \
        tcpdump \
        netcat-openbsd \
        net-tools \
        iputils-ping \
        traceroute \
        dnsutils \
        hydra \
        john \
        sqlmap \
        dirb \
        nikto \
        gobuster \
        hashcat \
    && mkdir -p /usr/share/wordlists && \
    ln -sf /usr/share/john/password.lst /usr/share/wordlists/password.lst && \
    rm -rf /var/lib/apt/lists/*

# ---- Metasploit Framework (Rapid7 official installer — standalone Ruby, no apt conflicts) ----
# Installs to /opt/metasploit-framework with its own Ruby runtime.
# Using || true so a transient network failure does not abort the image build.
RUN curl -fsSL \
        https://raw.githubusercontent.com/rapid7/metasploit-omnibus/master/config/templates/metasploit-framework-wrappers/msfupdate.erb \
        -o /tmp/msfinstall && \
    chmod 755 /tmp/msfinstall && \
    /tmp/msfinstall && \
    rm -f /tmp/msfinstall || \
    echo "WARNING: Metasploit install failed; continuing without it"

# ---- bwrap pass-through wrapper (safety: prevents glycin sandboxing in case Ubuntu MATE uses it) ----
# See: 030_operations/020_k8s-pups-local-llm/040_KaliDesktopContainer_260625_oo01
COPY bwrap-wrapper.sh /usr/bin/bwrap
RUN chmod +x /usr/bin/bwrap

# ---- Timezone (JST) ----
RUN ln -sf /usr/share/zoneinfo/Asia/Tokyo /etc/localtime && \
    echo "Asia/Tokyo" > /etc/timezone

# ---- Tomcat 9 (Guacamole 1.5.x requires javax.servlet = Tomcat 9, not 10+) ----
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

RUN sed -i 's|Connector port="8080" protocol="HTTP/1.1"|Connector port="8080" protocol="HTTP/1.1" maxHttpHeaderSize="65536"|' \
    /opt/tomcat/conf/server.xml

# ---- Guacamole web app (must match Ubuntu 24.04's guacd version = 1.5.4) ----
ARG GUAC_VERSION=1.5.4
RUN mkdir -p /opt/tomcat/webapps/ROOT && \
    wget -q \
        "https://downloads.apache.org/guacamole/${GUAC_VERSION}/binary/guacamole-${GUAC_VERSION}.war" \
        -O /tmp/guacamole.war && \
    unzip -q /tmp/guacamole.war -d /opt/tomcat/webapps/ROOT && \
    rm /tmp/guacamole.war

ENV GUACAMOLE_HOME=/etc/guacamole

COPY guacamole.properties /etc/guacamole/guacamole.properties
COPY user-mapping.xml /etc/guacamole/user-mapping.xml

COPY auto-login.js /opt/tomcat/webapps/ROOT/auto-login.js
RUN sed -i 's|</head>|<script src="auto-login.js"></script></head>|' \
    /opt/tomcat/webapps/ROOT/index.html

# ---- entrypoint ----
COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

EXPOSE 8080
CMD ["/usr/local/bin/entrypoint.sh"]
