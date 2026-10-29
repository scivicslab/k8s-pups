# Galaxy with a curated set of bioinformatics tools and their conda environments, installed at build time
# (the build host reaches the internet; the tool Pods do not).
#   docker build -t <registry>/galaxy-bio:<tag> .
FROM galaxy/galaxy-min:26.1.2
USER root
RUN python3 -m venv /opt/eph && /opt/eph/bin/pip install -q ephemeris && ln -s /opt/eph/bin/shed-tools /usr/local/bin/shed-tools
RUN mkdir -p /galaxy/shed_tools /galaxy/conda /galaxy/server/database && \
    chown galaxy:galaxy /galaxy /galaxy/shed_tools /galaxy/conda /galaxy/server/database
COPY shed_tool_conf.xml dependency_resolvers_conf.xml /galaxy/server/config/
COPY tools.yml build-tools.sh /tmp/
RUN sed -i 's|^    tool_config_file:.*|    tool_config_file: /galaxy/server/config/tool_conf.xml.sample,/galaxy/server/config/shed_tool_conf.xml|' /galaxy/server/config/galaxy.yml && \
    chown galaxy /galaxy/server/config/* && chmod +x /tmp/build-tools.sh
USER galaxy
# Galaxy installs a tool's dependencies with conda; its own conda initialisation yields no bin/conda, so use Miniforge.
RUN curl -sSfL -o /tmp/miniforge.sh https://github.com/conda-forge/miniforge/releases/latest/download/Miniforge3-Linux-x86_64.sh && \
    HOME=/tmp bash /tmp/miniforge.sh -b -u -p /galaxy/conda && rm /tmp/miniforge.sh
RUN /tmp/build-tools.sh
USER root
# The Pod runs as an arbitrary UID: configuration is writable (conda and the tools are world-readable already, umask 022).
# The database directory is the user's storage at run time; it is filled from /galaxy/seed-db on first start.
COPY file_sources_conf.yml /galaxy/server/config/
# The image sets static_enabled: false because it expects a proxy for /static; here the Pod is behind the gateway only.
RUN echo '    file_sources_config_file: /galaxy/server/config/file_sources_conf.yml' >> /galaxy/server/config/galaxy.yml && \
    sed -i 's|^    static_enabled: false|    static_enabled: true|' /galaxy/server/config/galaxy.yml && \
    mv /galaxy/server/database /galaxy/seed-db && mkdir /galaxy/server/database && \
    chmod -R a+rwX /galaxy/server/config /galaxy/seed-db
COPY entrypoint.sh /entrypoint.sh
USER galaxy
ENTRYPOINT ["tini", "--", "/entrypoint.sh"]
