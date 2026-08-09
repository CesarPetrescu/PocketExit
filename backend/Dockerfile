# syntax=docker/dockerfile:1.7
FROM golang:1.26-alpine AS build
WORKDIR /src
COPY go.mod ./
COPY cmd ./cmd
COPY internal ./internal
RUN go test ./...
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /out/pocketexit ./cmd/server

FROM alpine:3.24
RUN apk add --no-cache ca-certificates tzdata \
    && addgroup -S pocketexit \
    && adduser -S -G pocketexit pocketexit
COPY --from=build /out/pocketexit /usr/local/bin/pocketexit
USER pocketexit
EXPOSE 8080/tcp 1080/tcp 12000-12031/udp
HEALTHCHECK --interval=15s --timeout=3s --retries=5 CMD wget -qO- http://127.0.0.1:8080/api/v1/health >/dev/null || exit 1
ENTRYPOINT ["/usr/local/bin/pocketexit"]
