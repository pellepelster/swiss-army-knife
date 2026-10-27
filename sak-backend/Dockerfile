# expects the distribution expanded by `./gradlew :sak-backend:installDist`
FROM alpine:3.24

# the full jre, the headless one has no native jpeg library (libjavajpeg), which the
# thumbnails of the file storage need to read and write jpegs
RUN apk add --no-cache openjdk25-jre \
    && addgroup -S -g 10000 sak \
    && adduser -S -u 10000 -G sak -H -h /data -s /sbin/nologin sak \
    && mkdir -p /data \
    && chown sak:sak /data

COPY build/install/sak-backend /opt/sak-backend

USER 10000:10000
WORKDIR /data
EXPOSE 8080
VOLUME /data

# the configuration has to be mounted, its sak.data_dir should point to /data; the
# controller listens on 127.0.0.1 only (ktor.deployment.host), so it is meant to run
# with the host network behind the ingress
ENTRYPOINT ["/opt/sak-backend/bin/sak-backend"]
CMD ["-config=/etc/sak/config.yaml"]
