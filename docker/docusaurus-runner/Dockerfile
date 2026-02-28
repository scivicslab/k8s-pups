FROM ubuntu:24.04

# Avoid interactive prompts during package installation
ENV DEBIAN_FRONTEND=noninteractive

# Install Node.js 22 LTS from NodeSource
RUN apt-get update && \
    apt-get install -y ca-certificates curl gnupg && \
    mkdir -p /etc/apt/keyrings && \
    curl -fsSL https://deb.nodesource.com/gpgkey/nodesource-repo.gpg.key | gpg --dearmor -o /etc/apt/keyrings/nodesource.gpg && \
    echo "deb [signed-by=/etc/apt/keyrings/nodesource.gpg] https://deb.nodesource.com/node_22.x nodistro main" > /etc/apt/sources.list.d/nodesource.list && \
    apt-get update && \
    apt-get install -y nodejs && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# Enable corepack and activate yarn (pre-download so no network needed at runtime)
RUN corepack enable && corepack prepare yarn@stable --activate
ENV COREPACK_ENABLE_NETWORK=0

# Create devteam user with uid/gid 1002 (matching NFS host)
RUN groupadd -g 1002 devteam && \
    useradd -u 1002 -g 1002 -m -s /bin/bash devteam

# Create workspace mount point
RUN mkdir -p /workspace && chown 1002:1002 /workspace

COPY entrypoint.sh /usr/local/bin/entrypoint.sh

USER devteam
WORKDIR /workspace

EXPOSE 3000

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
