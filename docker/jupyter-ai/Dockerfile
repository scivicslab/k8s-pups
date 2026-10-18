FROM ubuntu:24.04

ARG DEBIAN_FRONTEND=noninteractive

# JupyterLab with the Jupyter AI chat and magics, for k8s-pups. Compared with the jupyter-lab image
# this one has no R, Julia, Java or bioinformatics packages: it is the light entry to notebooks with
# an AI assistant. The assistant talks to an OpenAI-compatible server (the GPU broker) whose address
# the controller passes in as JUPYTER_AI_BASE_URL.
RUN apt-get update && \
    apt-get install -y --no-install-recommends curl bzip2 ca-certificates git locales && \
    rm -rf /var/lib/apt/lists/*

RUN locale-gen ja_JP.UTF-8 && update-locale LANG=ja_JP.UTF-8
ENV LANG=ja_JP.UTF-8
ENV LANGUAGE=ja_JP:ja
ENV LC_ALL=ja_JP.UTF-8
RUN ln -sf /usr/share/zoneinfo/Asia/Tokyo /etc/localtime && echo "Asia/Tokyo" > /etc/timezone

# UID 1000 matches k8s-pups runAsUser. Ubuntu 24.04 already has UID 1000 (ubuntu); rename it to jovyan.
RUN usermod -l jovyan ubuntu && groupmod -n jovyan ubuntu && usermod -d /home/jovyan -m jovyan

ENV MAMBA_ROOT_PREFIX=/opt/conda
RUN curl -fsSL "https://micro.mamba.pm/api/micromamba/linux-64/latest" -o /tmp/micromamba.tar.bz2 && \
    tar -xjf /tmp/micromamba.tar.bz2 -C /usr/local/bin --strip-components=1 bin/micromamba && \
    rm /tmp/micromamba.tar.bz2

# jupyter-ai 2.x (version 2.24 and later has the OpenRouter provider, which takes any OpenAI-compatible base URL).
RUN micromamba install -y -n base -c conda-forge \
        python=3.12 \
        jupyterlab \
        "jupyter-ai>=2.24,<3" \
        langchain-openai \
        numpy pandas matplotlib scipy scikit-learn ipywidgets && \
    micromamba clean --all --yes

ENV PATH=/opt/conda/bin:$PATH

# Make conda packages world-readable (the container may run as any UID in workspace mode).
RUN chmod -R a+rX /opt/conda
RUN chmod 666 /etc/passwd /etc/group

COPY --chown=root:root entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

USER jovyan
WORKDIR /home/jovyan
EXPOSE 8888
CMD ["/usr/local/bin/entrypoint.sh"]
