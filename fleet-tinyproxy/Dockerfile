FROM alpine:3.23
RUN apk add --no-cache tinyproxy=1.11.3-r0
USER 1000:1000
ENTRYPOINT ["tinyproxy", "-d", "-c", "/etc/tinyproxy/tinyproxy.conf"]
