# galaxy-bio with one change in the AI agents: no "strict" tool definitions (patch_agent_model.py of docker/galaxy-bio, which
# is copied into this build context next to this file before the build). It is the image the Galaxy tool takes, built on top of
# the galaxy-bio image that was tested, so that nothing else in it changes; docker/galaxy-bio/Dockerfile has the same step for a
# full rebuild.
#   docker build --build-arg BASE=<registry>/galaxy-bio:<tag> -t <registry>/galaxy-bio-patch:<tag> .
ARG BASE=192.168.5.13:32000/galaxy-bio:26.1.2-2611061821
FROM ${BASE}
USER root
COPY patch_agent_model.py /tmp/patch_agent_model.py
RUN python3 /tmp/patch_agent_model.py && /galaxy/server/.venv/bin/python -m compileall -q /galaxy/server/lib/galaxy/agents/base.py && rm /tmp/patch_agent_model.py
USER galaxy
