#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
lock="$repo_dir/saturn-core.lock.json"
app_dir="${SATURN_APP_PATH:-$repo_dir/V2rayNG/app}"
licenses="$app_dir/src/main/assets/third_party/saturn"
mkdir -p "$licenses" "$app_dir/src/main/assets/saturn"

cp "$lock" "$licenses/source-pins.json"

work_dir="$repo_dir/.saturn-native"
mkdir -p "$work_dir"

if command -v jq >/dev/null 2>&1 && [ "$(jq -r '.assets // empty' "$lock")" != "" ]; then
    while IFS=$'\t' read -r abi archive url entry library expected_sha; do
        target_dir="$app_dir/libs/$abi"
        target_lib="$target_dir/$library"
        mkdir -p "$target_dir"
        
        valid=0
        if [ -f "$target_lib" ]; then
            actual_sha="$(sha256sum "$target_lib" | cut -d ' ' -f 1)"
            if [ "$actual_sha" = "$expected_sha" ]; then
                valid=1
            fi
        fi
        
        if [ "$valid" -eq 0 ]; then
            cached_lib="$work_dir/$library"
            if [ -f "$cached_lib" ] && [ "$(sha256sum "$cached_lib" | cut -d ' ' -f 1)" = "$expected_sha" ]; then
                cp "$cached_lib" "$target_lib"
            else
                archive_file="$work_dir/$archive"
                if [ ! -f "$archive_file" ]; then
                    curl --fail --location --retry 3 "$url" -o "$archive_file"
                fi
                unzip -p "$archive_file" "$entry" > "$target_lib"
            fi
        fi
        
        actual_sha="$(sha256sum "$target_lib" | cut -d ' ' -f 1)"
        if [ "$actual_sha" != "$expected_sha" ]; then
            echo "Checksum mismatch for $library: expected $expected_sha, got $actual_sha" >&2
            exit 1
        fi
    done < <(jq -r '.assets[] | [.abi,.archive,.url,.entry,.library,.sha256] | @tsv' "$lock")
fi

if command -v go >/dev/null 2>&1 && [ -d "$repo_dir/saturn-core" ]; then
    echo "Building saturn-core native binaries for Android ABIs..."
    (
        cd "$repo_dir/saturn-core"
        go mod tidy || true
        for TARGET in "arm64:arm64-v8a" "arm:armeabi-v7a" "amd64:x86_64" "386:x86"; do
            ARCH="${TARGET%%:*}"
            ABI="${TARGET##*:}"
            TARGET_DIR="$app_dir/libs/$ABI"
            mkdir -p "$TARGET_DIR"
            CGO_ENABLED=0 GOOS=android GOARCH="$ARCH" go build -trimpath -ldflags="-s -w" -o "$TARGET_DIR/libsaturn.so" ./cmd/saturn-client || true
        done
        mkdir -p "$repo_dir/bin"
        CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags="-s -w" -o "$repo_dir/bin/saturn-server-linux-amd64" ./cmd/saturn-server || true
        CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -trimpath -ldflags="-s -w" -o "$repo_dir/bin/saturn-server-linux-arm64" ./cmd/saturn-server || true
    )
fi

mkdir -p "$app_dir/src/main/assets/saturn"
cp -f "$repo_dir/install-saturn.sh" "$app_dir/src/main/assets/saturn/install-saturn.sh" || true

echo "Saturn direct protocol core and server installer prepared."
