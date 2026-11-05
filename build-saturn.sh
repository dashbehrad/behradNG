#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CORE_DIR="$SCRIPT_DIR/saturn-core"
BIN_DIR="$SCRIPT_DIR/bin"
mkdir -p "$BIN_DIR"

echo "=== Building Saturn Protocol Core ==="

cd "$CORE_DIR"

# Download dependencies
go mod tidy

# 1. Build saturn-server for Linux amd64 and arm64
echo "Building saturn-server (Linux amd64)..."
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags="-s -w" -o "$BIN_DIR/saturn-server-linux-amd64" ./cmd/saturn-server

echo "Building saturn-server (Linux arm64)..."
CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -trimpath -ldflags="-s -w" -o "$BIN_DIR/saturn-server-linux-arm64" ./cmd/saturn-server

# 2. Build saturn-client for Linux amd64 and arm64
echo "Building saturn-client (Linux amd64)..."
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags="-s -w" -o "$BIN_DIR/saturn-client-linux-amd64" ./cmd/saturn-client

echo "Building saturn-client (Linux arm64)..."
CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -trimpath -ldflags="-s -w" -o "$BIN_DIR/saturn-client-linux-arm64" ./cmd/saturn-client

# 3. Build saturn-client native executable for Android (packaged as libsaturn.so)
for TARGET in "arm64:arm64-v8a" "arm:armeabi-v7a" "amd64:x86_64" "386:x86"; do
    ARCH="${TARGET%%:*}"
    ABI="${TARGET##*:}"
    TARGET_DIR="$SCRIPT_DIR/app/libs/$ABI"
    V2RAY_TARGET_DIR="$SCRIPT_DIR/V2rayNG/app/libs/$ABI"
    mkdir -p "$TARGET_DIR" "$V2RAY_TARGET_DIR"

    echo "Building saturn-client for Android ($ABI)..."
    CGO_ENABLED=0 GOOS=android GOARCH="$ARCH" go build -trimpath -ldflags="-s -w" -o "$TARGET_DIR/libsaturn.so" ./cmd/saturn-client
    cp "$TARGET_DIR/libsaturn.so" "$V2RAY_TARGET_DIR/libsaturn.so"
done

echo "=== Saturn Core build complete! ==="
