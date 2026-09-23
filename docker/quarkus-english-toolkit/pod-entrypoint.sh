#!/bin/sh
# k8s-pups pod entrypoint for English Toolkit (quarkus-english-toolkit).
#
# The tool resolves its database and imported material under the home directory, and the Pod
# mounts the user's storage at /home/ubuntu (EnglishToolkitPlugin.userDataMountPath). The JVM
# takes user.home from /etc/passwd, not from HOME, so it is pinned here; keep the path in sync
# with the plugin and with english.media.local-root.
set -e
exec java -Duser.home=/home/ubuntu -jar /app/quarkus-run.jar
