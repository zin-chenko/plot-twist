FROM nginx:1.30.6-alpine@sha256:47928bf67eb62a1863cb08d9ecb52d3e704304ba614eaee17884204677702951
COPY --chown=nginx:nginx docker/nginx/nginx.conf /etc/nginx/nginx.conf
ARG TRUST_CONFIG=trusted-proxy.dev.conf
COPY --chown=nginx:nginx docker/nginx/${TRUST_CONFIG} /etc/nginx/trusted-proxy.conf
COPY --chown=nginx:nginx docs/openapi.json /usr/share/nginx/html/openapi.json
USER nginx
ENTRYPOINT ["nginx", "-g", "daemon off;"]
