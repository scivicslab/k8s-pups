FROM ubuntu:24.04

# code-raptor: search over the user's ~/works (symbol search by GNU Global, full-text and Javadoc search).
# The jar is copied into jars/ by the build script from the code-raptor build; it is not kept in the repository.
# Ubuntu with GraalVM on top, as the coding standard fixes both (JavaCodingConvention_260921_oo01).
# global provides gtags and global, which the symbol search runs.
# nginx puts the two screens of the Pod behind the one port the controller proxies (see nginx.conf).
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates git global nginx-light \
 && rm -rf /var/lib/apt/lists/*

# The code editor (code-server). The controller relays its WebSocket (PupsWithAuditWebSocketProxy_261020_oo01).
ENV CODE_SERVER_VERSION=4.141.0
RUN curl -fsSL "https://github.com/coder/code-server/releases/download/v${CODE_SERVER_VERSION}/code-server_${CODE_SERVER_VERSION}_amd64.deb" \
      -o /tmp/code-server.deb \
 && apt-get update \
 && apt-get install -y --no-install-recommends /tmp/code-server.deb \
 && rm -rf /tmp/code-server.deb /var/lib/apt/lists/*

ENV LANG=C.UTF-8

ENV GRAALVM_VERSION=25.0.0
RUN curl -fsSL "https://github.com/graalvm/graalvm-ce-builds/releases/download/jdk-${GRAALVM_VERSION}/graalvm-community-jdk-${GRAALVM_VERSION}_linux-x64_bin.tar.gz" \
      -o /tmp/graalvm.tar.gz \
 && mkdir -p /opt/graalvm \
 && tar -xzf /tmp/graalvm.tar.gz -C /opt/graalvm --strip-components=1 \
 && rm /tmp/graalvm.tar.gz
ENV JAVA_HOME=/opt/graalvm
ENV PATH=${JAVA_HOME}/bin:${PATH}

COPY jars/code-raptor.jar /opt/code-raptor/code-raptor.jar
COPY entrypoint.sh /usr/local/bin/entrypoint.sh
COPY nginx.conf /etc/nginx/pod-nginx.conf
RUN chmod 755 /usr/local/bin/entrypoint.sh \
 && mkdir -p /home/devteam/works \
 && chown -R 1000:1000 /home/devteam

# The indexes (GTAGS and the others) are written under ~/works, which the Pod mounts from the user's storage.
ENV HOME=/home/devteam
WORKDIR /home/devteam/works
USER 1000
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
