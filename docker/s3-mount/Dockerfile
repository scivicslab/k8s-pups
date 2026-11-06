FROM ubuntu:24.04

# The sidecar of a tool Pod that mounts the S3 datasets the user attached, read-only, with FUSE (rclone mount). It is
# the only container of the Pod with the rights FUSE needs; the tool container sees the mounts through a shared
# directory and has none of those rights.
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl unzip fuse3 \
 && rm -rf /var/lib/apt/lists/* \
 && curl -fsSL https://downloads.rclone.org/rclone-current-linux-amd64.zip -o /tmp/rclone.zip \
 && unzip -q /tmp/rclone.zip -d /tmp \
 && install -m 755 /tmp/rclone-*-linux-amd64/rclone /usr/local/bin/rclone \
 && rm -rf /tmp/rclone*

COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod 755 /usr/local/bin/entrypoint.sh

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
