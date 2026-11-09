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
# A file of the base image that only its owner can read answers 500 to a Pod running as another UID: welcome.html, the
# centre of the first page, is one (a007 runs the Pods as 30420). Only those files are changed, so the layer stays small.
RUN find /galaxy/server -xdev \( -type f -o -type d \) ! -perm -o=r -exec chmod a+rX {} +
# The tutorial search of Galaxy's AI (GTN) reads a database that Galaxy downloads on first use into data_dir (the user's
# storage); the Pod has no way out to the internet, so it is downloaded at build time and gtn_database_path names it.
RUN mkdir -p /galaxy/gtn && \
    curl -fsSL https://depot.galaxyproject.org/chatgxy/gtn_search.db -o /galaxy/gtn/gtn_search.db && \
    chmod -R a+rX /galaxy/gtn && \
    echo '    gtn_database_path: /galaxy/gtn/gtn_search.db' >> /galaxy/server/config/galaxy.yml
# The AI agents must not send "strict" tool definitions to the OpenAI-compatible server of the deployment: see the script.
# (The image galaxy-bio-patch applies the same script to an image that was built before it was here.)
COPY patch_agent_model.py /tmp/patch_agent_model.py
RUN python3 /tmp/patch_agent_model.py && /galaxy/server/.venv/bin/python -m compileall -q /galaxy/server/lib/galaxy/agents/base.py && rm /tmp/patch_agent_model.py
COPY entrypoint.sh /entrypoint.sh
USER galaxy
ENTRYPOINT ["tini", "--", "/entrypoint.sh"]
