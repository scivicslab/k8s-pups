FROM ubuntu:24.04

# AI Chat: a Gradio chat screen for an OpenAI-compatible server. Ubuntu, as the coding standard fixes the base distribution.
RUN apt-get update \
 && apt-get install -y --no-install-recommends python3 python3-venv python3-pip ca-certificates \
 && rm -rf /var/lib/apt/lists/*
ENV LANG=C.UTF-8
RUN python3 -m venv /opt/venv && /opt/venv/bin/pip install --no-cache-dir gradio openai
COPY app.py /opt/ai-chat/app.py
RUN mkdir -p /home/devteam/works && chown -R 1000:1000 /home/devteam
ENV HOME=/home/devteam
ENV GRADIO_ANALYTICS_ENABLED=False
WORKDIR /home/devteam/works
USER 1000
EXPOSE 8080
CMD ["/opt/venv/bin/python", "/opt/ai-chat/app.py"]
