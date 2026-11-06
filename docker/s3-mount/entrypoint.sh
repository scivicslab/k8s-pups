#!/bin/bash
# Mounts each S3 dataset of the session read-only at $MOUNT_ROOT/<dataset>, and unmounts them all when the Pod stops.
#
# The controller passes:
#   K8SPUPS_S3_DATASETS   the datasets, comma-separated
#   K8SPUPS_S3_BUCKET     the bucket that holds them, one dataset per top-level directory
#   AWS_CONFIG_FILE       an AWS config whose credential_process reads the credentials the controller keeps renewing
#   RCLONE_CONFIG_DATASETS_*  the rclone remote "datasets" on the S3 server, with env_auth
#
# A FUSE mount left behind on the node keeps the kubelet from removing the Pod, so the mounts are undone on SIGTERM
# before this script ends; the Pod's grace period leaves time for that.
set -u

MOUNT_ROOT="${MOUNT_ROOT:-/mnt/s3}"
export RCLONE_CONFIG=/dev/null   # everything is in the environment; no notice about a missing rclone.conf

mounted=()
pids=()

unmount_all() {
    for dir in "${mounted[@]}"; do
        fusermount3 -u -z "$dir" 2>/dev/null && echo "unmounted $dir"
    done
    for pid in "${pids[@]}"; do kill "$pid" 2>/dev/null; done
    wait
}
trap 'unmount_all; exit 0' TERM INT

IFS=',' read -ra datasets <<< "${K8SPUPS_S3_DATASETS:-}"
for dataset in "${datasets[@]}"; do
    [ -n "$dataset" ] || continue
    dir="$MOUNT_ROOT/$dataset"
    mkdir -p "$dir"
    rclone mount "datasets:${K8SPUPS_S3_BUCKET}/${dataset}" "$dir" \
        --read-only --vfs-cache-mode off --allow-other --dir-cache-time 1m --log-level NOTICE &
    pids+=("$!")
    mounted+=("$dir")
    echo "mounting $dataset at $dir"
done

if [ "${#pids[@]}" -eq 0 ]; then
    echo "no S3 dataset to mount"
fi

# Stay alive until the Pod stops; a waiting shell runs the trap at once. rclone writes its own errors to the log.
while true; do
    sleep 3600 &
    wait $!
done
