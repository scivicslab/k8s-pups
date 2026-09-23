#!/bin/sh
# k8s-pups pod entrypoint for the AI Workspace (ai-workspace).
#
# The pod mounts the user's persistent NFS at $HOME/works (HOME=/home/devteam, set in the image).
# Tools are not shipped in the image; the portal acquires them into $HOME/works at runtime.
set -e

# The JVM derives user.home from /etc/passwd (getpwuid), NOT the HOME env var, so we pin it explicitly
# with -Duser.home below. Keep this path in sync with that flag and with the pod's NFS mount point
# (AiWorkspacePlugin.userDataMountPath = /home/devteam/works).
WORKS=/home/devteam/works
mkdir -p "$WORKS"

# Run the portal (jar lives at /app). -Duser.home makes PluginLoader (user.home/works) and the
# file browser use the NFS-mounted ~/works.
exec java -Duser.home=/home/devteam \
    -Dai-workspace.port-range=28000-28099 -jar /app/ai-workspace.jar
