FROM ubuntu:24.04

ARG DEBIAN_FRONTEND=noninteractive

# ---- Optional local apt-mirror for faster builds ----
ARG APT_MIRROR_URL=""
RUN if [ -n "$APT_MIRROR_URL" ]; then \
      rm -f /etc/apt/sources.list.d/ubuntu.sources && \
      echo "deb ${APT_MIRROR_URL}/archive.ubuntu.com/ubuntu noble main restricted universe multiverse" > /etc/apt/sources.list && \
      echo "deb ${APT_MIRROR_URL}/archive.ubuntu.com/ubuntu noble-updates main restricted universe multiverse" >> /etc/apt/sources.list && \
      echo "deb ${APT_MIRROR_URL}/archive.ubuntu.com/ubuntu noble-backports main restricted universe multiverse" >> /etc/apt/sources.list && \
      echo "deb ${APT_MIRROR_URL}/security.ubuntu.com/ubuntu noble-security main restricted universe multiverse" >> /etc/apt/sources.list; \
    fi

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

# ---- package-set-3: Development tools, editors, Python, build essentials ----
RUN apt-get update && \
    apt-get install -y \
        # Network
        cadaver git lftp net-tools xvfb \
        # Editor / Misc
        emacs vim imagemagick tree \
        # GCC / Build
        build-essential gfortran gcc-doc flex bison \
        automake autoconf libtool autogen shtool \
        libc6-dev libarchive-dev cmake clang \
        # Python
        python3 python3-pip python-is-python3 \
        # Python/pyenv build deps
        libffi-dev libssl-dev zlib1g-dev liblzma-dev libbz2-dev \
        libreadline-dev libsqlite3-dev libopencv-dev tk-dev \
    && rm -rf /var/lib/apt/lists/*

# ---- package-set-1: R packages (r-cran-* and r-bioc-*) ----
# Installs all available R CRAN and Bioconductor packages.
# This pulls in ~7000 dependencies including C numerical libraries.
# || true tolerates individual package failures.
RUN apt-get update && \
    apt-cache search '^r-cran-' | awk '{print $1}' | \
        xargs -r apt-get install -y || true && \
    apt-cache search '^r-bioc-' | awk '{print $1}' | \
        xargs -r apt-get install -y || true && \
    rm -rf /var/lib/apt/lists/*

# ---- package-set-2: Genome / biology / bioinformatics packages ----
# Extracted from https://packages.ubuntu.com/noble/science/ by SciencePackageExtractor
COPY package-set-2-names.txt /tmp/package-set-2-names.txt
RUN apt-get update && \
    xargs -a /tmp/package-set-2-names.txt apt-get install -y || true && \
    rm -rf /var/lib/apt/lists/* /tmp/package-set-2-names.txt

# ---- TeX Live (full) ----
RUN apt-get update && \
    apt-get install -y texlive-full texlive-lang-all && \
    rm -rf /var/lib/apt/lists/*

# ---- Container runtime: Apptainer (provides singularity compat) + Docker CLI ----
RUN apt-get update && \
    apt-get install -y software-properties-common && \
    add-apt-repository -y ppa:apptainer/ppa && \
    apt-get update && \
    apt-get install -y apptainer docker.io && \
    rm -rf /var/lib/apt/lists/*

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

# ---- fcitx5 default profile: Mozc as default input method ----
RUN mkdir -p /etc/skel/.config/fcitx5
COPY fcitx5-profile /etc/skel/.config/fcitx5/profile

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
