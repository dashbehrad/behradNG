#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
lock="$repo_dir/saturn-core.lock.json"
app_dir="${SATURN_APP_PATH:-$repo_dir/app}"
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

echo "Saturn core prepared (Aether + URnetwork proxy chain v3.0.0)."
