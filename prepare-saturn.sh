#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
lock="$repo_dir/saturn-core.lock.json"
app_dir="${SATURN_APP_PATH:-$repo_dir/app}"
licenses="$app_dir/src/main/assets/third_party/saturn"
mkdir -p "$licenses" "$app_dir/src/main/assets/saturn"

copy_notices() {
    local source="$1" destination="$2"
    mkdir -p "$destination"
    find "$source" -maxdepth 1 -type f \( -iname '*license*' -o -iname 'copying*' -o -iname '*copyright*' \) -print0 |
        while IFS= read -r -d '' notice; do
            install -m 0644 "$notice" "$destination/"
        done
}

cp "$lock" "$licenses/source-pins.json"
echo "Saturn core prepared (Aether + URnetwork proxy chain v3.0.0)."
