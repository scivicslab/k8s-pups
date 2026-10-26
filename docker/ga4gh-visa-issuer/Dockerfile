FROM ubuntu:24.04

# ga4gh-visa-issuer: the Visa issuer and the Passport verification harness for the Passport verification
# in namespace k8s-pups-with-audit (Ga4ghPassportImplementations_261026_oo01).
# The Quarkus application (fast-jar layout) is copied into quarkus-app/ by hand before the build; it is not
# kept in the repository. Ubuntu with GraalVM on top, as the coding standard fixes both.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates \
 && rm -rf /var/lib/apt/lists/*

ENV GRAALVM_VERSION=25.0.0
RUN curl -fsSL "https://github.com/graalvm/graalvm-ce-builds/releases/download/jdk-${GRAALVM_VERSION}/graalvm-community-jdk-${GRAALVM_VERSION}_linux-x64_bin.tar.gz" \
      -o /tmp/graalvm.tar.gz \
 && mkdir -p /opt/graalvm \
 && tar -xzf /tmp/graalvm.tar.gz -C /opt/graalvm --strip-components=1 \
 && rm /tmp/graalvm.tar.gz
ENV JAVA_HOME=/opt/graalvm
ENV PATH=${JAVA_HOME}/bin:${PATH}

COPY quarkus-app/ /opt/ga4gh-visa-issuer/
RUN chown -R 1000:1000 /opt/ga4gh-visa-issuer

USER 1000
WORKDIR /opt/ga4gh-visa-issuer
EXPOSE 8080
ENTRYPOINT ["java", "-Dquarkus.http.host=0.0.0.0", "-Djava.util.logging.manager=org.jboss.logmanager.LogManager", "-jar", "/opt/ga4gh-visa-issuer/quarkus-run.jar"]
