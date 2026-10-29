# The guacamole-desktop image (Remote Desktop) plus the command-line tools of basic sequence analysis from the Ubuntu
# archive and IGV, the Integrative Genomics Viewer, from the Broad Institute (the Linux package with its own Java).
# The image is built where the internet is reachable; the tool Pods themselves cannot reach it.
#   docker build --build-arg BASE=<registry>/guacamole-desktop:<tag> -t <registry>/guacamole-desktop-bio:<tag> .
ARG BASE=192.168.5.13:32000/guacamole-desktop:0.1.3-2610292137
FROM ${BASE}
ARG IGV_VERSION=2.19.8
RUN apt-get update && \
    apt-get install -y --no-install-recommends bwa samtools bcftools bedtools seqkit minimap2 && \
    rm -rf /var/lib/apt/lists/*
RUN wget -q -O /tmp/igv.zip "https://data.broadinstitute.org/igv/projects/downloads/2.19/IGV_Linux_${IGV_VERSION}_WithJava.zip" && \
    unzip -q /tmp/igv.zip -d /opt && \
    mv /opt/IGV_Linux_${IGV_VERSION} /opt/igv && \
    rm /tmp/igv.zip && \
    chmod -R a+rX /opt/igv && \
    ln -s /opt/igv/igv.sh /usr/local/bin/igv
COPY igv.desktop /usr/share/applications/igv.desktop
