# The jupyter-ai image plus the command-line tools and libraries of basic sequence analysis, installed from conda-forge and
# bioconda (the image is built where the internet is reachable; the tool Pods themselves cannot reach it).
#   docker build --build-arg BASE=<registry>/jupyter-ai:<tag> -t <registry>/jupyter-ai-bio:<tag> .
ARG BASE=192.168.5.13:32000/jupyter-ai:1.0.0-2611091102
FROM ${BASE}
USER root
RUN micromamba install -y -n base -c conda-forge -c bioconda \
        biopython \
        bwa \
        samtools \
        bcftools \
        bedtools \
        seqkit \
        minimap2 && \
    micromamba clean --all --yes
# The files that conda writes are readable by every user (umask 022), so the Pod can run as any UID.
USER root
# S3 clients for the datasets of an S3 server that the user attaches: the Pod gets credentials for them in AWS_* and an
# rclone remote named datasets, read with `aws s3`, `rclone` or a program.
RUN apt-get update && apt-get install -y --no-install-recommends curl unzip ca-certificates && rm -rf /var/lib/apt/lists/* && \
    curl -fsSL https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip -o /tmp/awscliv2.zip && \
    unzip -q /tmp/awscliv2.zip -d /tmp && /tmp/aws/install && rm -rf /tmp/aws /tmp/awscliv2.zip && \
    curl -fsSL https://downloads.rclone.org/rclone-current-linux-amd64.zip -o /tmp/rclone.zip && \
    unzip -q /tmp/rclone.zip -d /tmp && install -m 755 /tmp/rclone-*-linux-amd64/rclone /usr/local/bin/rclone && rm -rf /tmp/rclone*
RUN micromamba install -y -n base -c conda-forge boto3 s3fs && micromamba clean --all --yes
USER jovyan
