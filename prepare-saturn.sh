#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
lock="$repo_dir/saturn-core.lock.json"
work="$repo_dir/.saturn-native"
app_dir="${SATURN_APP_PATH:-$repo_dir/app}"
mkdir -p "$work" "$app_dir/src/main/assets/saturn" "$app_dir/src/main/assets/third_party/saturn"

checkout() {
    local url="$1" revision="$2" destination="$3"
    if [ ! -d "$destination/.git" ]; then git clone --no-checkout "$url" "$destination"; fi
    git -C "$destination" fetch origin "$revision"
    git -C "$destination" checkout --detach "$revision"
    test "$(git -C "$destination" rev-parse HEAD)" = "$revision"
}

copy_notices() {
    local source="$1" destination="$2"
    mkdir -p "$destination"
    # Go's module cache is read-only. Install writable copies so later ABIs and reruns can replace them.
    # pipefail also makes any failed notice copy stop preparation instead of silently omitting licenses.
    find "$source" -maxdepth 1 -type f \( -iname '*license*' -o -iname 'copying*' -o -iname '*copyright*' \) -print0 |
        while IFS= read -r -d '' notice; do
            install -m 0644 "$notice" "$destination/"
        done
}

tor_revision="$(jq -r .tor.sourceRevision "$lock")"
checkout https://github.com/guardianproject/tor-android.git "$tor_revision" "$work/tor-android"
git -C "$work/tor-android" submodule update --init --recursive
test "$(git -C "$work/tor-android/external/tor" rev-parse HEAD)" = "$(jq -r .tor.torRevision "$lock")"
checkout "$(jq -r .obfs4.repository "$lock")" "$(jq -r .obfs4.sourceRevision "$lock")" "$work/lyrebird"
cp "$work/lyrebird/go.mod" "$work/lyrebird/go.sum" "$app_dir/src/main/assets/third_party/saturn/"
checkout "$(jq -r .cloudflared.repository "$lock")" "$(jq -r .cloudflared.sourceRevision "$lock")" "$work/cloudflared"
aar_version="$(jq -r .tor.version "$lock")"
curl --fail --location --retry 3 \
    "https://raw.githubusercontent.com/guardianproject/gpmaven/$(jq -r .tor.mavenRevision "$lock")/info/guardianproject/tor-android/$aar_version/tor-android-$aar_version.aar" \
    -o "$work/tor.aar"
printf '%s  %s\n' "$(jq -r .tor.sha256 "$lock")" "$work/tor.aar" | sha256sum --check -
for name in geoip geoip6; do
    cp "$work/tor-android/external/tor/src/config/$name" "$app_dir/src/main/assets/saturn/$name"
done

licenses="$app_dir/src/main/assets/third_party/saturn"
for source in "$work/tor-android" "$work/tor-android"/external/* "$work/lyrebird" "$work/cloudflared"; do
    [ -d "$source" ] || continue
    destination="$licenses/$(basename "$source")"
    copy_notices "$source" "$destination"
done

ndk="${NDK_HOME:?Set NDK_HOME to the workflow NDK}"
ndk_host=linux-x86_64
case "$(uname -s)" in Darwin*) ndk_host=darwin-x86_64 ;; esac
toolchain="$ndk/toolchains/llvm/prebuilt/$ndk_host/bin"
while IFS=$'\t' read -r abi arch arm target; do
    mkdir -p "$app_dir/libs/$abi"
    unzip -p "$work/tor.aar" "jni/$abi/libtor.so" > "$app_dir/libs/$abi/libsaturn-tor.so"
    test -s "$app_dir/libs/$abi/libsaturn-tor.so"
    (
        cd "$work/lyrebird"
        export GOOS=android GOARCH="$arch" CGO_ENABLED=1 CC="$toolchain/${target}26-clang"
        if [ "$arm" != '-' ]; then export GOARM="$arm"; fi
        go mod download
        main_package=./cmd/lyrebird
        if [ ! -d cmd/lyrebird ]; then main_package=./; fi
        test "$(go list -f '{{.Name}}' "$main_package")" = main
        # Go 1.23+ rejects anet's private net.zoneCache linkname; remove this flag when the pinned dependency stops using it.
        go build -trimpath -buildmode=pie -ldflags='-s -w -buildid= -checklinkname=0 -extldflags=-Wl,-z,max-page-size=16384' \
            -o "$app_dir/libs/$abi/libsaturn-obfs4.so" "$main_package"
        # Retain notices for the Go dependency closure in the APK.
        go list -m -json all | jq -r 'select(.Dir != null) | .Dir' | while IFS= read -r module; do
            notice_dir="$licenses/go/$(printf '%s' "$module" | sha256sum | cut -c1-16)"
            copy_notices "$module" "$notice_dir"
        done
    )
    if [ -f "$app_dir/libs/$abi/libaether-core.so" ]; then
        cp "$app_dir/libs/$abi/libaether-core.so" "$app_dir/libs/$abi/libsaturn-cloudflared.so"
    else
        (
            cd "$work/cloudflared"
            export GOOS=android GOARCH="$arch" CGO_ENABLED=1 CC="$toolchain/${target}26-clang"
            if [ "$arm" != '-' ]; then export GOARM="$arm"; fi
            go mod download
            go build -trimpath -buildmode=pie -ldflags='-s -w -buildid= -extldflags=-Wl,-z,max-page-size=16384' \
                -o "$app_dir/libs/$abi/libsaturn-cloudflared.so" ./cmd/cloudflared
        )
    fi
    "$toolchain/${target}26-clang" -O2 -fPIE -pie -Wl,-z,max-page-size=16384 \
        "$repo_dir/native/saturn-launcher.c" -o "$app_dir/libs/$abi/libsaturn-core.so"
done < <(jq -r '.abis[] | [.abi,.goarch,.goarm,.target] | @tsv' "$lock")
cp "$lock" "$licenses/source-pins.json"
