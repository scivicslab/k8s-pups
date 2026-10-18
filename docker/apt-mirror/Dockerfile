FROM ubuntu:24.04

# A mirror of the Ubuntu 24.04 package archive (apt-mirror) and the web server that serves it (nginx). The same image runs
# either: "sync" copies the archive into /data (a CronJob), "serve" serves /data/mirror (a Deployment).
# The layout under /data/mirror is <host>/ubuntu, which is the layout the APT_MIRROR_URL build argument of the
# JupyterLab image expects: ${APT_MIRROR_URL}/archive.ubuntu.com/ubuntu.
RUN apt-get update \
 && apt-get install -y --no-install-recommends apt-mirror nginx-light ca-certificates \
 && rm -rf /var/lib/apt/lists/*
COPY mirror.list /etc/apt/mirror.list
COPY nginx.conf /etc/nginx/nginx.conf
COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod 755 /usr/local/bin/entrypoint.sh
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
CMD ["serve"]
