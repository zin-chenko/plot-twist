FROM dxflrs/garage:v2.1.0@sha256:4c9b34c113e61358466e83fd6e7d66e6d18657ede14b776eb78a93ee8da7cf6a AS binary
FROM alpine:3.22
COPY --from=binary /garage /usr/local/bin/garage
RUN addgroup -g 10001 garage && adduser -D -u 10001 -G garage garage && mkdir -p /var/lib/garage && chown garage:garage /var/lib/garage
COPY garage.toml /etc/garage.toml
COPY init.sh /init.sh
RUN sed -i 's/\r$//' /init.sh
USER garage
CMD ["garage", "server"]
