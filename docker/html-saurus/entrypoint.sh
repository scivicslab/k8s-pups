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

# The controller publishes the tool below the session path (/<prefix>/session/<id>/) and removes it before forwarding, so
# the portal writes every URL of its pages below it. Run outside a session, the variable is unset and the portal serves at the root.
export HTML_SAURUS_BASE_PATH="${HTML_SAURUS_BASE_PATH:-${PUPS_SESSION_PATH:-}}"

mkdir -p "$PORTAL"
count=0
list="$(mktemp)"
# Which folders are projects: those that match the patterns the user entered in the launch form (PORTAL_PROJECTS, for
# example ~/works/doc_*), or, with none entered, every project near the top of the storage.
. /usr/local/bin/projects.sh
# What the reader needs to know about the patterns that found nothing is collected here, then shown on the portal page
NOTICE_LINES_FILE="$(mktemp)"
export NOTICE_LINES_FILE
list_projects "$SOURCE" "${PORTAL_PROJECTS:-}" | sort -u > "$list"
while IFS= read -r project; do
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
if [ -n "${PORTAL_PROJECTS:-}" ]; then
  echo "portal: $count project(s) found under $SOURCE for the folders [$PORTAL_PROJECTS]"
else
  echo "portal: $count project(s) found under $SOURCE"
fi

# The notice of the portal page (HTML_SAURUS_NOTICE_FILE): written only when there is something to say
NOTICE_FILE="${HTML_SAURUS_NOTICE_FILE:-${TMPDIR:-/tmp}/portal-notice.txt}"
write_notice "$NOTICE_LINES_FILE" "$count" "$NOTICE_FILE"
rm -f "$NOTICE_LINES_FILE"
export HTML_SAURUS_NOTICE_FILE="$NOTICE_FILE"

exec java -jar /opt/html-saurus/html-saurus-portal-server.jar serve -d "$PORTAL" -p "$PORT" -t "${PORTAL_THREADS:-2}"
