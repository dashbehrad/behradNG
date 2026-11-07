#!/usr/bin/env bash
set -euo pipefail

VERSION=${1:-"v1.0.0"}
OUT_DIR=${2:-"build"}

mkdir -p "$OUT_DIR"

PLATFORMS=(
    "linux/amd64"
    "linux/arm64"
    "linux/arm"
    "windows/amd64"
    "windows/arm64"
    "darwin/amd64"
    "darwin/arm64"
)

echo "=== Building Saturn Core ($VERSION) ==="

for PLATFORM in "${PLATFORMS[@]}"; do
    OS="${PLATFORM%/*}"
    ARCH="${PLATFORM#*/}"
    EXT=""
    if [ "$OS" = "windows" ]; then
        EXT=".exe"
    fi

    echo "-> Compiling for $OS/$ARCH..."
    CGO_ENABLED=0 GOOS=$OS GOARCH=$ARCH go build -trimpath -ldflags="-s -w -X main.Version=$VERSION" -o "$OUT_DIR/saturn-server-${OS}-${ARCH}${EXT}" ./cmd/saturn-server
    CGO_ENABLED=0 GOOS=$OS GOARCH=$ARCH go build -trimpath -ldflags="-s -w -X main.Version=$VERSION" -o "$OUT_DIR/saturn-client-${OS}-${ARCH}${EXT}" ./cmd/saturn-client
done

echo "=== Compilation Complete! Output directory: $OUT_DIR ==="
ls -lh "$OUT_DIR"
