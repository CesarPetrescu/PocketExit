FROM nginx:1.31.3-alpine
RUN nginx -V 2>&1 | grep -q -- '--with-http_v3_module' \
    && nginx -V 2>&1 | grep -q -- '--with-stream'
COPY nginx/nginx.conf /etc/nginx/nginx.conf
COPY frontend/ /usr/share/nginx/html/
EXPOSE 80/tcp 443/tcp 443/udp 1080/tcp 8081/tcp 12000-12031/udp
HEALTHCHECK --interval=15s --timeout=4s --retries=5 CMD wget --no-check-certificate -qO- https://127.0.0.1/api/v1/health >/dev/null || exit 1
