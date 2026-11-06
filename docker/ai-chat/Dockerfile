FROM ubuntu:24.04

# AI Chat: a Gradio chat screen for an OpenAI-compatible server. Ubuntu, as the coding standard fixes the base distribution.
RUN apt-get update \
 && apt-get install -y --no-install-recommends python3 python3-venv python3-pip ca-certificates \
 && rm -rf /var/lib/apt/lists/*
ENV LANG=C.UTF-8
RUN python3 -m venv /opt/venv && /opt/venv/bin/pip install --no-cache-dir gradio openai
COPY app.py /opt/ai-chat/app.py
RUN mkdir -p /home/pod-user/works && chown -R 1000:1000 /home/pod-user
ENV HOME=/home/pod-user
ENV GRADIO_ANALYTICS_ENABLED=False
WORKDIR /home/pod-user/works
USER root
# S3 clients for the datasets of an S3 server that the user attaches: the Pod gets credentials for them in AWS_* and an
# rclone remote named datasets, read with `aws s3`, `rclone` or a program.
RUN apt-get update && apt-get install -y --no-install-recommends curl unzip ca-certificates && rm -rf /var/lib/apt/lists/* && \
    curl -fsSL https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip -o /tmp/awscliv2.zip && \
    unzip -q /tmp/awscliv2.zip -d /tmp && /tmp/aws/install && rm -rf /tmp/aws /tmp/awscliv2.zip && \
    curl -fsSL https://downloads.rclone.org/rclone-current-linux-amd64.zip -o /tmp/rclone.zip && \
    unzip -q /tmp/rclone.zip -d /tmp && install -m 755 /tmp/rclone-*-linux-amd64/rclone /usr/local/bin/rclone && rm -rf /tmp/rclone*
USER 1000
EXPOSE 8080
CMD ["/opt/venv/bin/python", "/opt/ai-chat/app.py"]
