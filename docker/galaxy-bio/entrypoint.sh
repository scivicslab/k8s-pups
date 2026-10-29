#!/bin/sh
# Start Galaxy for one user under the session path of the Pod.
set -e

DB=/galaxy/server/database
# The user's storage is mounted over the database directory; fill it from the installed seed on first start.
if [ ! -e "$DB/.seeded" ]; then
    cp -r /galaxy/seed-db/. "$DB/"
    touch "$DB/.seeded"
fi

GALAXY_CONFIG_GALAXY_URL_PREFIX="${PUPS_SESSION_PATH%/}"
GALAXY_USER="${PUPS_USER_ID:-user}"
case "$GALAXY_USER" in
    *@*) ;;
    *) GALAXY_USER="$GALAXY_USER@pups.local" ;;
esac
export GALAXY_CONFIG_GALAXY_URL_PREFIX
export GALAXY_CONFIG_SINGLE_USER="$GALAXY_USER"
export GALAXY_CONFIG_ADMIN_USERS="$GALAXY_USER"

exec galaxy
