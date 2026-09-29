# JupyterLab image for genome analysis.
#
# This is the jupyter-lab:4.5.5-2602281600 recipe (ubuntu:24.04 + micromamba +
# JupyterLab + scientific Python) plus the tools the "Genomics in the Cloud"
# chapter 4 exercises need: a Java runtime, GATK, the htslib command line
# tools, and igv-notebook.
FROM ubuntu:24.04

ARG DEBIAN_FRONTEND=noninteractive

# ---- system packages ----
# curl, bzip2, ca-certificates : required to install micromamba
# wget, unzip, less            : used by the download and inspection steps
# openjdk-21-jdk-headless      : GATK 4.7 requires a Java 17 or later runtime
# samtools, bcftools, tabix,
# bwa, bedtools                : read alignment and variant file handling
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        curl bzip2 ca-certificates \
        wget unzip less \
        openjdk-21-jdk-headless \
        samtools bcftools tabix bwa bedtools \
    && rm -rf /var/lib/apt/lists/*

# ---- micromamba ----
ENV MAMBA_ROOT_PREFIX=/opt/conda
RUN curl -fsSL "https://micro.mamba.pm/api/micromamba/linux-64/latest" \
        -o /tmp/micromamba.tar.bz2 && \
    tar -xjf /tmp/micromamba.tar.bz2 -C /usr/local/bin --strip-components=1 bin/micromamba && \
    rm /tmp/micromamba.tar.bz2

# ---- JupyterLab + scientific Python stack ----
# jupyterlab is pinned to the version running in jupyter-lab:4.5.5-2602281600
# so that this image differs from it only by the added tools.
RUN micromamba install -y -n base -c conda-forge \
        python=3.12 \
        jupyterlab=4.5.5 \
        numpy \
        pandas \
        matplotlib \
        scipy \
        scikit-learn \
        ipywidgets && \
    micromamba clean --all --yes

ENV PATH=/opt/conda/bin:$PATH

# ---- Python packages for genome analysis ----
# igv-notebook embeds igv.js in a notebook cell; pysam reads BAM/VCF from Python.
RUN /opt/conda/bin/pip install --no-cache-dir \
        igv-notebook==0.6.2 \
        pysam

# ---- GATK ----
ARG GATK_VERSION=4.7.0.0
RUN curl -fsSL \
        "https://github.com/broadinstitute/gatk/releases/download/${GATK_VERSION}/gatk-${GATK_VERSION}.zip" \
        -o /tmp/gatk.zip && \
    unzip -q /tmp/gatk.zip -d /opt && \
    rm /tmp/gatk.zip && \
    ln -s /opt/gatk-${GATK_VERSION}/gatk /usr/local/bin/gatk && \
    chmod -R a+rX /opt/gatk-${GATK_VERSION}

# ---- non-root user (UID 1000 matches k8s-pups runAsUser) ----
# Ubuntu 24.04 already has UID 1000 (ubuntu); rename to jovyan.
RUN usermod -l jovyan ubuntu && \
    groupmod -n jovyan ubuntu && \
    usermod -d /home/jovyan -m jovyan

# Make conda packages world-readable (container may run as any UID)
RUN chmod -R a+rX /opt/conda

# Allow arbitrary UID in workspace mode.
RUN chmod 666 /etc/passwd /etc/group

# Entrypoint script: reads PUPS_SESSION_PATH to set --ServerApp.base_url dynamically.
COPY --chown=root:root entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

USER jovyan
WORKDIR /home/jovyan

EXPOSE 8888

CMD ["/usr/local/bin/entrypoint.sh"]
