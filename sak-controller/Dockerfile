# expects the distribution expanded by `./gradlew :sak-controller:installDist`
FROM alpine:3.24

RUN apk add --no-cache openjdk25-jre-headless \
    && addgroup -S -g 10000 sak \
    && adduser -S -u 10000 -G sak -H -h /data -s /sbin/nologin sak \
    && mkdir -p /data \
    && chown sak:sak /data

COPY build/install/sak-controller /opt/sak-controller

USER 10000:10000
WORKDIR /data
EXPOSE 8080
VOLUME /data

# the configuration has to be mounted, its sak.data_dir should point to /data
ENTRYPOINT ["/opt/sak-controller/bin/sak-controller"]
CMD ["-config=/etc/sak/config.yaml"]
