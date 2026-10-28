# the smallest image proving a container runtime works, pulled and run by sakctl on every
# start before the image of the tools; one static binary on scratch, a few kilobytes
FROM alpine:3.24 AS build
RUN apk add --no-cache gcc musl-dev
COPY canary.c /canary.c
RUN gcc -static -Os -s -o /canary /canary.c

FROM scratch
COPY --from=build /canary /canary
USER 1000:1000
ENTRYPOINT ["/canary"]
