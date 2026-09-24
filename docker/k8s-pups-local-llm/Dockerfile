FROM ubuntu:24.04

# The controller for the Local LLM deployment. Its own image, not a tag of the other one: Quarkus
# fixes quarkus.http.root-path when the application is built, so /local-llm is baked into the
# artifacts this copies. An image built for /pups answers 404 to every /local-llm path, including
# the health checks Kubernetes uses, and is restarted in a loop.

# Ubuntu with GraalVM on top, as the coding standard fixes both
# (JavaCodingConvention_260921_oo01, DockerfileConvention_260923_oo01). This image previously
# started from registry.access.redhat.com/ubi8/openjdk-21, which is neither.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates \
 && rm -rf /var/lib/apt/lists/*

# GraalVM Community, at the version the controller is developed on (sdkman 25-graalce).
ENV GRAALVM_VERSION=25.0.0
RUN curl -fsSL "https://github.com/graalvm/graalvm-ce-builds/releases/download/jdk-${GRAALVM_VERSION}/graalvm-community-jdk-${GRAALVM_VERSION}_linux-x64_bin.tar.gz" \
      -o /tmp/graalvm.tar.gz \
 && mkdir -p /opt/graalvm \
 && tar -xzf /tmp/graalvm.tar.gz -C /opt/graalvm --strip-components=1 \
 && rm /tmp/graalvm.tar.gz
ENV JAVA_HOME=/opt/graalvm
ENV PATH=${JAVA_HOME}/bin:${PATH}

ENV LANGUAGE='en_US:en'

# uid 185 is what the previous image ran as, and what the Vault agent's injected secret files
# under /vault/secrets are readable by. Keep it so the controller still reads them.
RUN groupadd -g 185 quarkus && useradd -u 185 -g 185 -m -d /home/quarkus quarkus

# The Quarkus fast-jar: its four directories have to stay side by side, and quarkus-run.jar
# names the others by relative path.
COPY --chown=185:185 target/quarkus-app/lib/     /deployments/lib/
COPY --chown=185:185 target/quarkus-app/*.jar    /deployments/
COPY --chown=185:185 target/quarkus-app/app/     /deployments/app/
COPY --chown=185:185 target/quarkus-app/quarkus/ /deployments/quarkus/

EXPOSE 8080
USER 185

# No run-java.sh here: that script belongs to the Red Hat base image. The two settings it was
# given are passed on the command line instead. quarkus.http.host makes the server listen on
# every interface, which a Pod needs; the log manager has to be set before any logging happens.
ENTRYPOINT ["java", \
  "-Dquarkus.http.host=0.0.0.0", \
  "-Djava.util.logging.manager=org.jboss.logmanager.LogManager", \
  "-jar", "/deployments/quarkus-run.jar"]
