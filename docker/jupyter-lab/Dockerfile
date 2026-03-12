FROM ubuntu:24.04

ARG DEBIAN_FRONTEND=noninteractive

# ---- Optional local apt-mirror for faster builds ----
ARG APT_MIRROR_URL=""
RUN if [ -n "$APT_MIRROR_URL" ]; then \
      rm -f /etc/apt/sources.list.d/ubuntu.sources && \
      echo "deb ${APT_MIRROR_URL}/archive.ubuntu.com/ubuntu noble main restricted universe multiverse" > /etc/apt/sources.list && \
      echo "deb ${APT_MIRROR_URL}/archive.ubuntu.com/ubuntu noble-updates main restricted universe multiverse" >> /etc/apt/sources.list && \
      echo "deb ${APT_MIRROR_URL}/archive.ubuntu.com/ubuntu noble-backports main restricted universe multiverse" >> /etc/apt/sources.list && \
      echo "deb ${APT_MIRROR_URL}/security.ubuntu.com/ubuntu noble-security main restricted universe multiverse" >> /etc/apt/sources.list; \
    fi

# ---- system packages ----
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        curl bzip2 ca-certificates wget unzip \
        build-essential gfortran gcc-doc \
        git software-properties-common \
        locales \
    && rm -rf /var/lib/apt/lists/*

# ---- package-set-3: Development tools, editors, Python, build essentials ----
COPY package-set-3-names.txt /tmp/package-set-3.txt
RUN apt-get update && \
    total=$(wc -l < /tmp/package-set-3.txt) && \
    n=0 && failed=0 && \
    while IFS= read -r pkg || [ -n "$pkg" ]; do \
        pkg=$(echo "$pkg" | tr -d '[:space:]'); \
        [ -z "$pkg" ] && continue; \
        n=$((n+1)); \
        if apt-get install -y "$pkg" > /dev/null 2>&1; then \
            echo "[${n}/${total}] OK: ${pkg}"; \
        else \
            echo "[${n}/${total}] FAILED: ${pkg}"; \
            failed=$((failed+1)); \
        fi; \
    done < /tmp/package-set-3.txt && \
    echo "package-set-3 done: ${n} attempted, ${failed} failed" && \
    rm -f /tmp/package-set-3.txt && \
    rm -rf /var/lib/apt/lists/*

# ---- package-set-1: R packages (r-cran-* and r-bioc-*) ----
RUN apt-get update && \
    apt-cache search '^r-cran-' | awk '{print $1}' | sort > /tmp/r-cran-list.txt && \
    apt-cache search '^r-bioc-' | awk '{print $1}' | sort > /tmp/r-bioc-list.txt && \
    total=$(cat /tmp/r-cran-list.txt /tmp/r-bioc-list.txt | wc -l) && \
    n=0 && failed=0 && \
    for pkg in $(cat /tmp/r-cran-list.txt /tmp/r-bioc-list.txt); do \
        n=$((n+1)); \
        if apt-get install -y --no-install-recommends "$pkg" > /dev/null 2>&1; then \
            echo "[${n}/${total}] OK: ${pkg}"; \
        else \
            echo "[${n}/${total}] FAILED: ${pkg}"; \
            failed=$((failed+1)); \
        fi; \
    done && \
    echo "package-set-1 done: ${n} attempted, ${failed} failed" && \
    rm -f /tmp/r-cran-list.txt /tmp/r-bioc-list.txt && \
    rm -rf /var/lib/apt/lists/*

# ---- package-set-2: Genome / biology / bioinformatics packages ----
COPY package-set-2-names.txt /tmp/package-set-2-names.txt
RUN apt-get update && \
    total=$(wc -l < /tmp/package-set-2-names.txt) && \
    n=0 && failed=0 && \
    while IFS= read -r pkg || [ -n "$pkg" ]; do \
        pkg=$(echo "$pkg" | tr -d '[:space:]'); \
        [ -z "$pkg" ] && continue; \
        n=$((n+1)); \
        if apt-get install -y --no-install-recommends "$pkg" > /dev/null 2>&1; then \
            echo "[${n}/${total}] OK: ${pkg}"; \
        else \
            echo "[${n}/${total}] FAILED: ${pkg}"; \
            failed=$((failed+1)); \
        fi; \
    done < /tmp/package-set-2-names.txt && \
    echo "package-set-2 done: ${n} attempted, ${failed} failed" && \
    rm -rf /var/lib/apt/lists/* /tmp/package-set-2-names.txt

# ---- Japanese locale + timezone ----
RUN locale-gen ja_JP.UTF-8 && update-locale LANG=ja_JP.UTF-8
ENV LANG=ja_JP.UTF-8
ENV LANGUAGE=ja_JP:ja
ENV LC_ALL=ja_JP.UTF-8
RUN ln -sf /usr/share/zoneinfo/Asia/Tokyo /etc/localtime && \
    echo "Asia/Tokyo" > /etc/timezone

# ---- non-root user (UID 1000 matches k8s-pups runAsUser) ----
# Ubuntu 24.04 already has UID 1000 (ubuntu); rename to jovyan.
RUN usermod -l jovyan ubuntu && \
    groupmod -n jovyan ubuntu && \
    usermod -d /home/jovyan -m jovyan

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

ENV PATH=/opt/conda/bin:$PATH

# ---- R kernel (IRkernel) ----
# R is already installed from package-set-1. Install IRkernel into system R.
RUN Rscript -e "install.packages('IRkernel', repos='https://cloud.r-project.org')" && \
    Rscript -e "IRkernel::installspec(user=FALSE)"

# ---- Julia + IJulia kernel ----
ARG JULIA_VERSION=1.11.3
RUN curl -fsSL "https://julialang-s3.julialang.org/bin/linux/x64/1.11/julia-${JULIA_VERSION}-linux-x86_64.tar.gz" \
        -o /tmp/julia.tar.gz && \
    tar -xzf /tmp/julia.tar.gz -C /opt && \
    ln -s /opt/julia-${JULIA_VERSION}/bin/julia /usr/local/bin/julia && \
    rm /tmp/julia.tar.gz

ENV JULIA_DEPOT_PATH=/opt/julia-depot
RUN mkdir -p /opt/julia-depot && \
    julia -e 'using Pkg; Pkg.add("IJulia")' && \
    chmod -R a+rX /opt/julia-depot

# ---- Node.js + ijavascript kernel ----
RUN curl -fsSL https://deb.nodesource.com/setup_22.x | bash - && \
    apt-get install -y nodejs && \
    rm -rf /var/lib/apt/lists/* && \
    npm install -g ijavascript && \
    ijsinstall --install=global

# ---- Java kernel (IJava / Rapaio) ----
RUN apt-get update && \
    apt-get install -y --no-install-recommends openjdk-21-jdk && \
    rm -rf /var/lib/apt/lists/*

RUN curl -fsSL "https://github.com/padreati/rapaio-jupyter-kernel/releases/download/2.3.0/rapaio-jupyter-kernel-2.3.0.jar" \
        -o /tmp/rapaio-kernel.jar && \
    java -jar /tmp/rapaio-kernel.jar -i -auto && \
    rm /tmp/rapaio-kernel.jar

# ---- NCBI SRA Toolkit (prefetch, fasterq-dump, etc.) ----
ARG SRA_TOOLKIT_VERSION=3.2.1
RUN curl -fsSL "https://ftp-trace.ncbi.nlm.nih.gov/sra/sdk/${SRA_TOOLKIT_VERSION}/sratoolkit.${SRA_TOOLKIT_VERSION}-ubuntu64.tar.gz" \
        -o /tmp/sratoolkit.tar.gz && \
    tar -xzf /tmp/sratoolkit.tar.gz -C /opt && \
    rm /tmp/sratoolkit.tar.gz
ENV PATH=/opt/sratoolkit.${SRA_TOOLKIT_VERSION}-ubuntu64/bin:$PATH

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
