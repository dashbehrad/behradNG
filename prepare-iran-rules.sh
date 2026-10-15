#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
APP_PATH="${1:-$ROOT/app}"
LOCK="$ROOT/iran-rules.lock.json"
REPOSITORY="$(jq -r .repository "$LOCK")"
REVISION="$(jq -r .revision "$LOCK")"
GEO_TEMP="$(mktemp -d)"
trap 'rm -rf -- "$GEO_TEMP"' EXIT
for FILE in geoip.dat geosite.dat; do
  HASH="$(jq -r --arg file "$FILE" '.files[$file]' "$LOCK")"
  curl --fail --location --retry 3 "https://raw.githubusercontent.com/$REPOSITORY/$REVISION/$FILE" -o "$GEO_TEMP/$FILE"
  printf '%s  %s\n' "$HASH" "$GEO_TEMP/$FILE" | sha256sum --check --strict
done
LICENSE="$ROOT/V2rayNG/app/src/main/assets/third_party/IRAN_RULES_LICENSE.txt"
printf '%s  %s\n' "$(jq -r .licenseSha256 "$LOCK")" "$LICENSE" | sha256sum --check --strict
mkdir -p "$APP_PATH/src/main/assets/third_party"
cp "$GEO_TEMP/geoip.dat" "$GEO_TEMP/geosite.dat" "$APP_PATH/src/main/assets/"
cp "$LICENSE" "$APP_PATH/src/main/assets/third_party/IRAN_RULES_LICENSE.txt"
