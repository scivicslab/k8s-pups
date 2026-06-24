FROM kalilinux/kali-rolling

ARG DEBIAN_FRONTEND=noninteractive

# ---- Desktop + VNC + Java + guacamole build deps ----
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        xfce4 \
        xfce4-terminal \
        xfce4-goodies \
        tigervnc-standalone-server \
        default-jre-headless \
        unzip \
        wget \
        curl \
        procps \
        dbus-x11 \
        x11-xserver-utils \
        adwaita-icon-theme \
        kali-themes \
        hicolor-icon-theme \
        libgdk-pixbuf2.0-bin \
        shared-mime-info \
        build-essential \
        libcairo2-dev \
        libjpeg62-turbo-dev \
        libpng-dev \
        uuid-dev \
        libvncserver-dev \
        libssl-dev \
        libwebsockets-dev \
        libpango1.0-dev \
        libssh2-1-dev \
        libtelnet-dev \
    && update-mime-database /usr/share/mime \
    && rm -rf /var/lib/apt/lists/*

# ---- Build guacamole-server (guacd) from source ----
ARG GUAC_VERSION=1.5.4
RUN wget -q \
        "https://downloads.apache.org/guacamole/${GUAC_VERSION}/source/guacamole-server-${GUAC_VERSION}.tar.gz" \
        -O /tmp/guac-server.tar.gz && \
    tar -xzf /tmp/guac-server.tar.gz -C /tmp && \
    cd /tmp/guacamole-server-${GUAC_VERSION} && \
    ./configure --disable-guacenc && \
    make -j$(nproc) && \
    make install && \
    ldconfig && \
    rm -rf /tmp/guacamole-server-${GUAC_VERSION} /tmp/guac-server.tar.gz

# ---- Kali security tools ----
# --fix-missing: skip packages that fail to download from a mirror and continue
RUN apt-get update && \
    apt-get install -y --no-install-recommends --fix-missing \
        nmap \
        wireshark \
        tcpdump \
        netcat-traditional \
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
        burpsuite \
        metasploit-framework \
        hashcat \
        wordlists \
    && rm -rf /var/lib/apt/lists/*

# ---- Timezone (JST) ----
RUN ln -sf /usr/share/zoneinfo/Asia/Tokyo /etc/localtime && \
    echo "Asia/Tokyo" > /etc/timezone

# ---- Tomcat 9 (tarball; Guacamole 1.5.x requires javax.servlet = Tomcat 9) ----
ARG TOMCAT_VERSION=9.0.102
RUN wget -q \
        "https://archive.apache.org/dist/tomcat/tomcat-9/v${TOMCAT_VERSION}/bin/apache-tomcat-${TOMCAT_VERSION}.tar.gz" \
        -O /tmp/tomcat.tar.gz && \
    tar -xzf /tmp/tomcat.tar.gz -C /opt && \
    mv /opt/apache-tomcat-${TOMCAT_VERSION} /opt/tomcat && \
    rm -f /tmp/tomcat.tar.gz && \
    rm -rf /opt/tomcat/webapps/*

ENV CATALINA_HOME=/opt/tomcat

RUN sed -i 's|Connector port="8080" protocol="HTTP/1.1"|Connector port="8080" protocol="HTTP/1.1" maxHttpHeaderSize="65536"|' \
    /opt/tomcat/conf/server.xml

# ---- Guacamole web app ----
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
