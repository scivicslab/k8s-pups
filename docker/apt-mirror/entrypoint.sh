#!/bin/sh
# "sync": copy the archive into /data and delete what the archive dropped. "serve": serve /data/mirror on port 8080.
case "$1" in
  sync)  mkdir -p /data/mirror /data/skel /data/var
         apt-mirror /etc/apt/mirror.list
         sh /data/var/clean.sh ;;
  serve) mkdir -p /data/mirror
         exec nginx -g 'daemon off;' ;;
  *)     exec "$@" ;;
esac
