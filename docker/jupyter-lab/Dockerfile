FROM ubuntu:24.04

ARG DEBIAN_FRONTEND=noninteractive

# ---- system packages ----
RUN apt-get update && \
    apt-get install -y --no-install-recommends curl bzip2 ca-certificates && \
    rm -rf /var/lib/apt/lists/*

# ---- micromamba ----
ENV MAMBA_ROOT_PREFIX=/opt/conda
RUN curl -fsSL "https://micro.mamba.pm/api/micromamba/linux-64/latest" \
        -o /tmp/micromamba.tar.bz2 && \
    tar -xjf /tmp/micromamba.tar.bz2 -C /usr/local/bin --strip-components=1 bin/micromamba && \
    rm /tmp/micromamba.tar.bz2

# ---- JupyterLab + scientific Python stack ----
RUN micromamba install -y -n base -c conda-forge \
        python=3.12 \
        jupyterlab \
        numpy \
        pandas \
        matplotlib \
        scipy \
        scikit-learn \
        ipywidgets && \
    micromamba clean --all --yes

# Make conda packages world-readable (container may run as any UID)
RUN chmod -R a+rX /opt/conda

# Allow arbitrary UID in workspace mode.
# When NFS home is mounted, the container runs as the LDAP UID (e.g. 1002)
# which doesn't exist in /etc/passwd. Python's pwd module and other tools
# need to resolve the current UID, so the entrypoint adds an entry at runtime.
RUN chmod 666 /etc/passwd /etc/group

# ---- non-root user (UID 1000 matches k8s-pups runAsUser) ----
# Ubuntu 24.04 already has UID 1000 (ubuntu); rename to jovyan.
RUN usermod -l jovyan ubuntu && \
    groupmod -n jovyan ubuntu && \
    usermod -d /home/jovyan -m jovyan

ENV PATH=/opt/conda/bin:$PATH

# Entrypoint script: reads PUPS_SESSION_PATH to set --ServerApp.base_url dynamically.
COPY --chown=root:root entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

USER jovyan
WORKDIR /home/jovyan

EXPOSE 8888

# JUPYTER_TOKEN="" is injected by k8s-pups to disable built-in token auth.
# PUPS_SESSION_PATH is injected by k8s-pups and used by entrypoint.sh as base_url.
# Access is protected by Envoy Gateway SecurityPolicy (Keycloak OIDC).
CMD ["/usr/local/bin/entrypoint.sh"]
