FROM ubuntu:24.04

# Ubuntu, and GraalVM on top of it. Both are fixed by the coding standard
# (JavaCodingConvention_260921_oo01). GraalVM publishes an Oracle Linux image; it is not used,
# because the base distribution is Ubuntu everywhere. GraalVM is the JDK because native-image is
# the reason it was chosen and GraalPy may be used from the same runtime, so a plain JDK image is
# not a substitute either.
#
# git so "Build Snapshot" works inside the pod (SnapshotBuildService clones from GitHub and runs
# mvn install). openssh-client so users can clone/push their own repos over SSH (git@github.com)
# from the pod. iproute2 provides `ss`, which JvmBackend uses to map a port to its PID for tool
# adoption / free-port detection; without it every port scan fails with "Cannot run program ss".
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      git openssh-client iproute2 curl ca-certificates \
 && rm -rf /var/lib/apt/lists/*

# GraalVM Community. The version tracks the one the tools are developed on (sdkman 25-graalce):
# a tool that builds on the developer's machine has to build here too, and javac's behaviour
# differs between JDK versions.
ENV GRAALVM_VERSION=25.0.0
RUN curl -fsSL "https://github.com/graalvm/graalvm-ce-builds/releases/download/jdk-${GRAALVM_VERSION}/graalvm-community-jdk-${GRAALVM_VERSION}_linux-x64_bin.tar.gz" \
      -o /tmp/graalvm.tar.gz \
 && mkdir -p /opt/graalvm \
 && tar -xzf /tmp/graalvm.tar.gz -C /opt/graalvm --strip-components=1 \
 && rm /tmp/graalvm.tar.gz
ENV JAVA_HOME=/opt/graalvm
ENV PATH=${JAVA_HOME}/bin:${PATH}

# Maven from the Apache distribution: Ubuntu noble ships 3.8.7, and the developer's machine runs
# 3.9.11.
ENV MAVEN_VERSION=3.9.11
RUN curl -fsSL "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz" \
      -o /tmp/maven.tar.gz \
 && tar -xzf /tmp/maven.tar.gz -C /opt \
 && ln -s "/opt/apache-maven-${MAVEN_VERSION}/bin/mvn" /usr/local/bin/mvn \
 && rm /tmp/maven.tar.gz

# The image tag, baked in at build time so the running portal can show its exact build in the header.
# kaniko-build.sh passes it with --build-arg IMAGE_TAG=<tag>.
ARG IMAGE_TAG=dev
ENV AI_WORKSPACE_IMAGE_TAG=${IMAGE_TAG}

# The AI Workspace discovers tools in $HOME/works and the k8s-pups pod mounts the user's persistent
# NFS at /home/devteam/works, so HOME must be /home/devteam for the two to coincide. Own /home/devteam
# with uid 1000 (the pod runs as uid 1000) so ~/.m2 and other $HOME writes work; the NFS mount overlays
# only the works subdir at runtime.
ENV HOME=/home/devteam
# Persist Maven's dependency cache on the NFS-backed ~/works (the only persistent mount in the pod),
# so Build Snapshot doesn't re-download everything after each Pod re-creation.
ENV AI_WORKSPACE_SNAPSHOT_MAVEN_REPO_LOCAL=/home/devteam/works/.m2/repository
RUN mkdir -p /home/devteam/works && chown -R 1000:1000 /home/devteam

# Only the portal itself is in the image. The tools it runs (chat-ui, turing-workflow-editor,
# html-saurus, code-raptor, ...) are acquired by the portal at runtime with Download Latest or
# Build Snapshot, into the NFS-backed ~/works. Baking them in would pin their versions to this
# image and force a rebuild for every tool update.
COPY ai-workspace.jar  /app/ai-workspace.jar
COPY pod-entrypoint.sh /app/pod-entrypoint.sh
RUN chmod +x /app/pod-entrypoint.sh

# user.dir = ~/works so SnapshotBuildService (default works-dir ${user.dir}) installs where the portal
# scans; the CMD/portal jar is referenced by absolute path since CWD is now the works dir.
WORKDIR /home/devteam/works

EXPOSE 28000

ENTRYPOINT ["/app/pod-entrypoint.sh"]
