FROM ubuntu:24.04

# A caching proxy for apt (apt-cacher-ng): a package is fetched from the Ubuntu archive once and served from the cache
# afterwards. Use it with the build argument or the setting Acquire::http::Proxy "http://<host>:<port>".
RUN apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends apt-cacher-ng ca-certificates \
 && rm -rf /var/lib/apt/lists/*
# HTTPS repositories (NodeSource, and so on) cannot be cached; let them pass through the proxy.
RUN echo 'PassThroughPattern: .*' >> /etc/apt-cacher-ng/acng.conf
EXPOSE 3142
CMD ["/usr/sbin/apt-cacher-ng", "-c", "/etc/apt-cacher-ng", "ForeGround=1"]
