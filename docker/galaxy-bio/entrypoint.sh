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

# The AI features of Galaxy (the GalaxyAI chat panel, the explanation of a failed job) talk to the GPU broker, an
# OpenAI-compatible server inside the cluster, whose base URL (ending in /v1) and model the controller passes in.
# Without them Galaxy shows no AI feature. The block goes at the end of galaxy.yml, which is in its galaxy: section;
# a container restarted in the same Pod already has it.
CFG=/galaxy/server/config/galaxy.yml
if [ -n "$GALAXY_AI_BASE_URL" ] && [ -n "$GALAXY_AI_MODEL" ]; then
    case "$(cat "$CFG")" in
        *"    inference_services:"*) ;;
        *) cat >> "$CFG" <<EOF
    inference_services:
        default:
            model: "$GALAXY_AI_MODEL"
            api_base_url: "${GALAXY_AI_BASE_URL%/}/"
            api_key: "not-needed"
EOF
        ;;
    esac
fi

exec galaxy
