#!/bin/bash
set -ex
export HOME=/tmp
export GALAXY_CONFIG_MASTER_API_KEY=buildkey
export GALAXY_CONFIG_CONDA_AUTO_INIT=false
export GALAXY_CONFIG_CONDA_PREFIX=/galaxy/conda
export GALAXY_CONFIG_CONDA_AUTO_INSTALL=false
export GALAXY_CONFIG_TOOL_CONFIG_FILE=/galaxy/server/config/tool_conf.xml.sample,/galaxy/server/config/shed_tool_conf.xml
export GALAXY_CONFIG_DEPENDENCY_RESOLVERS_CONFIG_FILE=/galaxy/server/config/dependency_resolvers_conf.xml
galaxy > /tmp/galaxy-build.log 2>&1 &
for i in $(seq 1 120); do
  curl -sf http://127.0.0.1:8080/api/version && break
  sleep 3
done
shed-tools install -g http://127.0.0.1:8080 -a buildkey -t /tmp/tools.yml || { tail -50 /tmp/galaxy-build.log; exit 1; }
# Fail the build when a repository did not reach the installed state (an errored one leaves no files behind).
curl -s -H "x-api-key: buildkey" http://127.0.0.1:8080/api/tool_shed_repositories > /tmp/repos.json
python3 - <<'PY'
import json, sys
bad = [(r["name"], r["status"], r.get("error_message")) for r in json.load(open("/tmp/repos.json")) if r["status"] != "Installed"]
if bad:
    print("repositories not installed:", bad)
    sys.exit(1)
PY
galaxy stop || pkill -f gunicorn || true
sleep 5
