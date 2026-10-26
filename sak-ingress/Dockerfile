FROM alpine:3.24

# the file capability lets caddy bind 80 and 443 as non-root user
RUN apk add --no-cache caddy libcap-setcap \
    && setcap cap_net_bind_service=+ep /usr/sbin/caddy \
    && apk del libcap-setcap \
    && addgroup -S -g 10001 sak-ingress \
    && adduser -S -u 10001 -G sak-ingress -H -h /data -s /sbin/nologin sak-ingress \
    && mkdir -p /data \
    && chown sak-ingress:sak-ingress /data

COPY Caddyfile /etc/caddy/Caddyfile

# caddy keeps its certificates and acme state in /data
ENV XDG_DATA_HOME=/data

USER 10001:10001
WORKDIR /data
EXPOSE 80 443
VOLUME /data

ENTRYPOINT ["/usr/sbin/caddy"]
CMD ["run", "--config", "/etc/caddy/Caddyfile", "--adapter", "caddyfile"]
