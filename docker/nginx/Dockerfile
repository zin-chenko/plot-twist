FROM nginx:1.28-alpine
COPY --chown=nginx:nginx nginx.conf /etc/nginx/nginx.conf
USER nginx
ENTRYPOINT ["nginx", "-g", "daemon off;"]
