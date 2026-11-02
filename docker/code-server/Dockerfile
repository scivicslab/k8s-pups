FROM ubuntu:24.04

# The code editor (code-server: VS Code in the browser) and nothing else. The editor opens the user's ~/works, which the Pod
# mounts from the user's storage. git is there because the editor's source control view runs it.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates git \
 && rm -rf /var/lib/apt/lists/*

ENV CODE_SERVER_VERSION=4.141.0
RUN curl -fsSL "https://github.com/coder/code-server/releases/download/v${CODE_SERVER_VERSION}/code-server_${CODE_SERVER_VERSION}_amd64.deb" \
      -o /tmp/code-server.deb \
 && apt-get update \
 && apt-get install -y --no-install-recommends /tmp/code-server.deb \
 && rm -rf /tmp/code-server.deb /var/lib/apt/lists/*

ENV LANG=C.UTF-8

COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod 755 /usr/local/bin/entrypoint.sh \
 && mkdir -p /home/devteam/works \
 && chown -R 1000:1000 /home/devteam

ENV HOME=/home/devteam
WORKDIR /home/devteam/works
USER 1000
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
