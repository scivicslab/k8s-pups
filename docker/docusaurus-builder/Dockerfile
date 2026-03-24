FROM ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive

# Install Node.js 22 LTS, Python3, and JDK 21 (for SAU3 indexing)
RUN apt-get update && \
    apt-get install -y ca-certificates curl gnupg python3 openjdk-21-jre-headless && \
    mkdir -p /etc/apt/keyrings && \
    curl -fsSL https://deb.nodesource.com/gpgkey/nodesource-repo.gpg.key | gpg --dearmor -o /etc/apt/keyrings/nodesource.gpg && \
    echo "deb [signed-by=/etc/apt/keyrings/nodesource.gpg] https://deb.nodesource.com/node_22.x nodistro main" > /etc/apt/sources.list.d/nodesource.list && \
    apt-get update && \
    apt-get install -y nodejs && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# Enable corepack and activate yarn
RUN corepack enable && corepack prepare yarn@stable --activate
ENV COREPACK_ENABLE_NETWORK=0

# Create default user
RUN groupadd -g 1000 appuser 2>/dev/null || true && \
    useradd -u 1000 -g 1000 -m -s /bin/bash -d /home/user appuser 2>/dev/null || true

# Allow arbitrary UID to add passwd/group entries at runtime
RUN chmod 666 /etc/passwd /etc/group

# Create mount points
RUN mkdir -p /workspace /output /tmp/status /opt/sau3 && chmod 777 /workspace /output /tmp/status

# Copy SAU3 JAR for OpenSearch indexing
COPY sau3.jar /opt/sau3/sau3.jar

COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

USER 1000
WORKDIR /workspace

EXPOSE 3000

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
