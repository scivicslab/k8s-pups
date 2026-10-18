FROM ubuntu:24.04

# chat-ui-with-audit-trail (the body) with the web-tools and shell plugin jars: the "Local LLM only"
# start-up configuration of ThreeStartupConfigurations_260912_oo01. The jars are copied into jars/ by the
# build script from the released or snapshot builds; they are not kept in the repository.
#
# Ubuntu with GraalVM on top, as the coding standard fixes both (JavaCodingConvention_260921_oo01).
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates git \
 && rm -rf /var/lib/apt/lists/*

# The JVM takes the encoding for file names from the locale; C.UTF-8 is built into glibc.
ENV LANG=C.UTF-8

ENV GRAALVM_VERSION=25.0.0
RUN curl -fsSL "https://github.com/graalvm/graalvm-ce-builds/releases/download/jdk-${GRAALVM_VERSION}/graalvm-community-jdk-${GRAALVM_VERSION}_linux-x64_bin.tar.gz" \
      -o /tmp/graalvm.tar.gz \
 && mkdir -p /opt/graalvm \
 && tar -xzf /tmp/graalvm.tar.gz -C /opt/graalvm --strip-components=1 \
 && rm /tmp/graalvm.tar.gz
ENV JAVA_HOME=/opt/graalvm
ENV PATH=${JAVA_HOME}/bin:${PATH}

COPY jars/chat-ui-with-audit-trail.jar /opt/chat-ui/chat-ui-with-audit-trail.jar
COPY jars/chat-ui-with-audit-trail-plugin-web-tools.jar /opt/chat-ui/chat-ui-with-audit-trail-plugin-web-tools.jar
COPY jars/chat-ui-with-audit-trail-plugin-shell.jar /opt/chat-ui/chat-ui-with-audit-trail-plugin-shell.jar
COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod 755 /usr/local/bin/entrypoint.sh \
 && mkdir -p /home/devteam/works \
 && chown -R 1000:1000 /home/devteam

# The conversation reads and writes under ~/works, which the Pod mounts from the user's storage.
ENV HOME=/home/devteam
WORKDIR /home/devteam/works
USER 1000
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
