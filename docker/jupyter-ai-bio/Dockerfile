# The jupyter-ai image plus the command-line tools and libraries of basic sequence analysis, installed from conda-forge and
# bioconda (the image is built where the internet is reachable; the tool Pods themselves cannot reach it).
#   docker build --build-arg BASE=<registry>/jupyter-ai:<tag> -t <registry>/jupyter-ai-bio:<tag> .
ARG BASE=192.168.5.13:32000/jupyter-ai:1.0.0-2610190508
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
USER jovyan
