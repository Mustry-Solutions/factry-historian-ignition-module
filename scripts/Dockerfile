# Dockerfile for Factry Historian Proxy
FROM golang:1.24-alpine AS builder

WORKDIR /build

# Copy go mod files
COPY go.mod go.sum ./
RUN go mod download

# Copy source code and generated proto
COPY *.go ./
COPY proto/ proto/

# Build the binary
RUN CGO_ENABLED=0 GOOS=linux go build -o factry-proxy .

# Final stage
FROM alpine:latest

RUN apk --no-cache add ca-certificates curl

WORKDIR /app

# Copy binary from builder
COPY --from=builder /build/factry-proxy .

# Expose ports
EXPOSE 8111
EXPOSE 50051

# Health check
HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
  CMD curl -f http://localhost:8111/health || exit 1

# Run the proxy
CMD ["./factry-proxy"]
