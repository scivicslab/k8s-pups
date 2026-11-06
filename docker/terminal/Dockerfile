FROM ubuntu:24.04

# A terminal in the browser (ttyd) on Ubuntu 24.04, with the command line AI agent Hermes Agent next to it.
# The user types `hermes` in the terminal; entrypoint.sh points it at the local LLM (GPU broker) of the deployment.
# libatomic1 is what the Node.js that Hermes manages links to; git, curl and tar are what its installer needs.
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      ca-certificates curl git tar xz-utils libatomic1 \
      ttyd tmux vim nano less jq htop procps python3 locales \
 && rm -rf /var/lib/apt/lists/*

ENV LANG=C.UTF-8

# Hermes Agent, installed as the user 1000 that owns /home/pod-user in the image. The browser and the computer-use driver
# are left out: the Pod has no way out to the network and no screen. A tool Pod runs as the number of the cluster (1000 on
# W206, the number of an account of the SE team on a007), and Hermes refuses to start when it cannot write its install
# state, so the whole home is made writable for every number, in the same layer as the install.
RUN mkdir -p /home/pod-user/works && chown -R 1000:1000 /home/pod-user
USER 1000
ENV HOME=/home/pod-user
RUN curl -fsSL https://hermes-agent.nousresearch.com/install.sh \
      | bash -s -- --non-interactive --skip-browser --skip-computer-use \
 && chmod -R a+rwX /home/pod-user
ENV PATH=/home/pod-user/.local/bin:${PATH}

COPY entrypoint.sh /usr/local/bin/entrypoint.sh
COPY motd.txt /etc/motd-terminal

USER root
# S3 clients for the datasets of an S3 server that the user attaches: the Pod gets credentials for them in AWS_* and an
# rclone remote named datasets, read with `aws s3`, `rclone` or a program.
RUN apt-get update && apt-get install -y --no-install-recommends curl unzip ca-certificates && rm -rf /var/lib/apt/lists/* && \
    curl -fsSL https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip -o /tmp/awscliv2.zip && \
    unzip -q /tmp/awscliv2.zip -d /tmp && /tmp/aws/install && rm -rf /tmp/aws /tmp/awscliv2.zip && \
    curl -fsSL https://downloads.rclone.org/rclone-current-linux-amd64.zip -o /tmp/rclone.zip && \
    unzip -q /tmp/rclone.zip -d /tmp && install -m 755 /tmp/rclone-*-linux-amd64/rclone /usr/local/bin/rclone && rm -rf /tmp/rclone*
USER 1000
WORKDIR /home/pod-user/works
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
