FROM ubuntu:24.04

# The document portal of html-saurus (portal mode): it serves every Docusaurus project it finds, with search, in the
# browser. The Pod mounts the user's storage and the storage of the user's groups under ~/works; the entrypoint copies the
# sources of the projects it finds there to a place the Pod can write and serves the copy.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates \
 && rm -rf /var/lib/apt/lists/*

ENV LANG=C.UTF-8

# GraalVM Community, at the version the controller is developed on, as the coding standard fixes it
# (JavaCodingConvention_260921_oo01, DockerfileConvention_260923_oo01).
ENV GRAALVM_VERSION=25.0.0
RUN curl -fsSL "https://github.com/graalvm/graalvm-ce-builds/releases/download/jdk-${GRAALVM_VERSION}/graalvm-community-jdk-${GRAALVM_VERSION}_linux-x64_bin.tar.gz" \
      -o /tmp/graalvm.tar.gz \
 && mkdir -p /opt/graalvm \
 && tar -xzf /tmp/graalvm.tar.gz -C /opt/graalvm --strip-components=1 \
 && rm /tmp/graalvm.tar.gz
ENV JAVA_HOME=/opt/graalvm
ENV PATH=${JAVA_HOME}/bin:${PATH}

# The portal server of html-saurus: the jar that serves every Docusaurus project under one directory.
ENV HTML_SAURUS_VERSION=2.6.0
RUN mkdir -p /opt/html-saurus \
 && curl -fsSL "https://github.com/scivicslab/html-saurus/releases/download/v${HTML_SAURUS_VERSION}/html-saurus-portal-server-${HTML_SAURUS_VERSION}.jar" \
      -o /opt/html-saurus/html-saurus-portal-server.jar

COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod 755 /usr/local/bin/entrypoint.sh \
 && mkdir -p /home/devteam/works /home/devteam/portal \
 && chown -R 1000:1000 /home/devteam

ENV HOME=/home/devteam
WORKDIR /home/devteam
USER 1000
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
