#!/bin/sh
# Serves the Docusaurus projects found under the user's ~/works with the portal server of html-saurus.
#
# The portal builds into the project directory (static-html/ and the search index), and the storage a group shares is
# often mounted read-only, so the sources of every project are copied to $PORTAL (a directory of the Pod that is writable)
# and the copy is served. The copy is as new as the moment the Pod started; start the tool again to see newer documents.
set -eu

SOURCE="${PORTAL_SOURCE:-/home/devteam/works}"
PORTAL="${PORTAL_DIR:-/home/devteam/portal}"
PORT="${PORTAL_PORT:-8080}"

mkdir -p "$PORTAL"
count=0
list="$(mktemp)"
# A Docusaurus project has docs/ and docusaurus.config.js or docusaurus.config.ts (what html-saurus looks for too).
find "$SOURCE" -maxdepth 3 \( -name node_modules -o -name .git -o -name build \) -prune -o \
     -type f \( -name 'docusaurus.config.js' -o -name 'docusaurus.config.ts' \) -print 2>/dev/null | sort > "$list"
while IFS= read -r config; do
  project="$(dirname "$config")"
  [ -d "$project/docs" ] || continue
  name="$(basename "$project")"
  target="$PORTAL/$name"
  n=2
  while [ -e "$target" ]; do target="$PORTAL/$name-$n"; n=$((n + 1)); done
  mkdir -p "$target"
  # Only the sources: what html-saurus builds from them is built again here.
  tar -C "$project" --exclude=node_modules --exclude=.git --exclude=build --exclude=.docusaurus \
      --exclude=static-html --exclude=search-index --exclude=search-embedding -cf - . | tar -C "$target" -xf -
  echo "portal: copied $project -> $target"
  count=$((count + 1))
done < "$list"
rm -f "$list"
echo "portal: $count project(s) found under $SOURCE"

exec java -jar /opt/html-saurus/html-saurus-portal-server.jar serve -d "$PORTAL" -p "$PORT" -t "${PORTAL_THREADS:-2}"
