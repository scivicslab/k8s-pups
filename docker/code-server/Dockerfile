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

# The AI assistant of the editor: Continue, talking to the GPU broker (an OpenAI-compatible server) whose address the
# controller passes in. The built-in chat of the editor is GitHub Copilot, which needs a GitHub account; it is hidden.
# The extension sits in the image, not under /home/devteam/.local: the Pod mounts a fresh directory there. The directory
# is writable by every user, as the Pod runs as the number of the cluster, and the editor writes its list of extensions.
ENV CONTINUE_VERSION=2.0.0
ENV CONTINUE_SHA256=e06893353f943dd4ed87d5438474d77ea850e6033abdf2c0694e42cb754c4c22
RUN curl -fsSL "https://open-vsx.org/api/Continue/continue/linux-x64/${CONTINUE_VERSION}/file/Continue.continue-${CONTINUE_VERSION}@linux-x64.vsix" \
      -o /tmp/continue.vsix \
 && echo "${CONTINUE_SHA256}  /tmp/continue.vsix" | sha256sum -c - \
 && mkdir -p /opt/code-server/extensions \
 && code-server --extensions-dir /opt/code-server/extensions --install-extension /tmp/continue.vsix \
 && rm -f /tmp/continue.vsix \
 && chmod -R a+rwX /opt/code-server/extensions

COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod 755 /usr/local/bin/entrypoint.sh \
 && mkdir -p /home/devteam/works \
 && chown -R 1000:1000 /home/devteam

ENV HOME=/home/devteam
WORKDIR /home/devteam/works
USER 1000
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
