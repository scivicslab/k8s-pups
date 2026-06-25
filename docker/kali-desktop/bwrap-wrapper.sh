#!/bin/bash
# Pass-through bwrap wrapper for k8s containers.
# The real bwrap uses --unshare-all which requires CLONE_NEWUSER (user namespace creation),
# blocked in k8s pods. This wrapper strips all namespace arguments and runs the target
# binary directly. The k8s pod itself already provides process isolation.
while [[ $# -gt 0 ]]; do
  case "$1" in
    --unshare-all|--die-with-parent|--clearenv) shift ;;
    --chdir|--dev|--tmpfs|--seccomp) shift 2 ;;
    --ro-bind|--ro-bind-try|--symlink|--bind-try|--bind) shift 3 ;;
    --setenv) export "$2=$3"; shift 3 ;;
    --) shift; break ;;
    --*) shift ;;
    *) break ;;
  esac
done
mkdir -p /tmp-home /tmp-run 2>/dev/null
exec "$@"
