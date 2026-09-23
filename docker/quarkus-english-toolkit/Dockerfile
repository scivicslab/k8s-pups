FROM ubuntu:24.04

# Ubuntu, and GraalVM on top of it, as the coding standard fixes them (JavaCodingConvention_260921_oo01,
# DockerfileConvention_260923_oo01). The GraalVM-published Oracle Linux image is not used because the
# base distribution is Ubuntu everywhere; a Temurin JRE is not a substitute either, because the JDK is
# GraalVM for native-image and GraalPy, and because the libraries this tool ships (gpu-broker-client)
# are compiled for the developer's JDK 25: on a JRE 21 they fail at class load, which is how the
# Temurin-based image of this tool broke every GPU-broker call inside a Pod.
#
# curl and ca-certificates fetch the GraalVM archive below and are kept for the entrypoint's own use.
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      curl ca-certificates python3-pip \
 && rm -rf /var/lib/apt/lists/*

# yt-dlp downloads a video once into the user's storage, so the transcript server reads a file
# instead of asking YouTube again on every re-import and on each of the GPU broker's retries
# (MediaDownloads). The pre-release is taken deliberately: YouTube's bot check is what the
# downloader keeps up with, and the newest stable is 2026.8.19 while the fixes since are in the
# development builds. No ffmpeg: MediaDownloads asks for a format that already carries both
# video and audio, so nothing is merged.
RUN pip3 install --break-system-packages --no-cache-dir --pre -U yt-dlp

# GraalVM Community, the version the tool is built with on the developer's machine (sdkman 25-graalce).
ENV GRAALVM_VERSION=25.0.0
RUN curl -fsSL "https://github.com/graalvm/graalvm-ce-builds/releases/download/jdk-${GRAALVM_VERSION}/graalvm-community-jdk-${GRAALVM_VERSION}_linux-x64_bin.tar.gz" \
      -o /tmp/graalvm.tar.gz \
 && mkdir -p /opt/graalvm \
 && tar -xzf /tmp/graalvm.tar.gz -C /opt/graalvm --strip-components=1 \
 && rm /tmp/graalvm.tar.gz
ENV JAVA_HOME=/opt/graalvm
ENV PATH=${JAVA_HOME}/bin:${PATH}

# The image tag, baked in so a running tool can say which build it is. kaniko-build.sh passes it.
ARG IMAGE_TAG=dev
ENV ENGLISH_TOOLKIT_IMAGE_TAG=${IMAGE_TAG}

# Only the tool itself: the Quarkus fast-jar layout (quarkus-run.jar plus its lib/, app/, quarkus/).
# Dictionaries come from an NFS mount and the user's data from the Pod's storage at runtime.
COPY quarkus-app/       /app/
COPY pod-entrypoint.sh  /app/pod-entrypoint.sh
RUN chmod +x /app/pod-entrypoint.sh && chown -R 1000:1000 /app

WORKDIR /app
EXPOSE 28200

ENTRYPOINT ["/app/pod-entrypoint.sh"]
