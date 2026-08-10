# syntax=docker/dockerfile:1.7
FROM alpine:3.24 AS download
RUN apk add --no-cache ca-certificates curl
ARG SPARK_TUNNEL_URL=https://webhost.photonspark.com/api/v1/downloads/spark-tunnel/linux/amd64?version=0.3.0
ARG SPARK_TUNNEL_SHA256=145980a8d1008eb3aa92bcf9d54340820fc241f99194a5729857d963e543d5a4
RUN curl -fsSL --retry 3 "$SPARK_TUNNEL_URL" -o /spark-tunnel \
    && echo "$SPARK_TUNNEL_SHA256  /spark-tunnel" | sha256sum -c - \
    && chmod 0755 /spark-tunnel

FROM scratch
COPY --from=download /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
COPY --from=download /spark-tunnel /spark-tunnel
USER 65532:65532
ENTRYPOINT ["/spark-tunnel"]
