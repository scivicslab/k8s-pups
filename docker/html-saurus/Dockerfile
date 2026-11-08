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

# The portal server of html-saurus: the jar that serves every Docusaurus project under one directory. The jar is built from
# the html-saurus source (mvn install in html-saurus-portal-server; the -shaded jar) and put in the build context under this
# name before the image is built, with the commit it came from in the file html-saurus-commit (kept in the image as
# /opt/html-saurus/html-saurus-commit). It must be a build that reads HTML_SAURUS_BASE_PATH (html-saurus 2.7.0 and later),
# as the Pod serves the portal below the session path of the tool.
RUN mkdir -p /opt/html-saurus
COPY html-saurus-portal-server.jar html-saurus-commit /opt/html-saurus/

COPY entrypoint.sh projects.sh /usr/local/bin/
RUN chmod 755 /usr/local/bin/entrypoint.sh /usr/local/bin/projects.sh \
 && mkdir -p /home/devteam/works /home/devteam/portal \
 && chown -R 1000:1000 /home/devteam

ENV HOME=/home/devteam
WORKDIR /home/devteam
USER 1000
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
