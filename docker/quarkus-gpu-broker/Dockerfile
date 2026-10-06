FROM ubuntu:24.04

# Ubuntu with GraalVM on top, as the coding standard fixes both
# (JavaCodingConvention_260921_oo01, DockerfileConvention_260923_oo01). This image previously
# started from eclipse-temurin:21-jre-noble, which is neither, and whose Java 21 could not have
# run this jar once the project moved to release 25.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates \
 && rm -rf /var/lib/apt/lists/*

# The JVM takes the encoding for file names (sun.jnu.encoding) from the locale. The Ubuntu base
# has no LANG set, which is the POSIX locale, i.e. ASCII: any path containing a Japanese name then
# fails with InvalidPathException. C.UTF-8 is built into glibc, so no locale package is needed.
ENV LANG=C.UTF-8

# GraalVM Community, at the version the broker is developed on (sdkman 25-graalce).
ENV GRAALVM_VERSION=25.0.0
RUN curl -fsSL "https://github.com/graalvm/graalvm-ce-builds/releases/download/jdk-${GRAALVM_VERSION}/graalvm-community-jdk-${GRAALVM_VERSION}_linux-x64_bin.tar.gz" \
      -o /tmp/graalvm.tar.gz \
 && mkdir -p /opt/graalvm \
 && tar -xzf /tmp/graalvm.tar.gz -C /opt/graalvm --strip-components=1 \
 && rm /tmp/graalvm.tar.gz
ENV JAVA_HOME=/opt/graalvm
ENV PATH=${JAVA_HOME}/bin:${PATH}

# The broker is an uber-jar. Per-endpoint overrides (broker.capabilities, internal addresses) are
# not baked in: Quarkus reads config/application.yaml relative to the working directory, so the
# deployment mounts that file at /app/config/application.yaml. broker.nodes comes from the
# environment (BROKER_NODES) for the same reason.
WORKDIR /app
COPY target/quarkus-gpu-broker-*.jar /app/quarkus-gpu-broker.jar

# The status history (broker.history.file) defaults to a path under the home directory; the
# deployment points it at a mounted volume instead. Run unprivileged.
USER 1000

EXPOSE 28005

CMD ["java", "-Dquarkus.http.port=28005", "-jar", "/app/quarkus-gpu-broker.jar"]
