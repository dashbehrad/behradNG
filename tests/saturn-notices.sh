#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# Load only the production copy helper; preparing native cores would require network and an NDK.
source <(sed -n '/^copy_notices() {$/,/^}$/p' "$repo_dir/prepare-saturn.sh")
fixture="$(mktemp -d "${TMPDIR:-/tmp}/saturn-notices.XXXXXXXX")"
trap 'rm -rf -- "$fixture"' EXIT
mkdir -p "$fixture/module with spaces" "$fixture/assets" "$fixture/bin"
printf 'first license\n' > "$fixture/module with spaces/LICENSE.txt"
printf 'copyright\n' > "$fixture/module with spaces/COPYRIGHT"
printf 'not a notice\n' > "$fixture/module with spaces/source.go"
chmod 0444 "$fixture/module with spaces/LICENSE.txt" "$fixture/module with spaces/COPYRIGHT"
for abi in arm64-v8a armeabi-v7a x86_64; do
    copy_notices "$fixture/module with spaces" "$fixture/assets"
    cmp "$fixture/module with spaces/LICENSE.txt" "$fixture/assets/LICENSE.txt"
    cmp "$fixture/module with spaces/COPYRIGHT" "$fixture/assets/COPYRIGHT"
    test ! -e "$fixture/assets/source.go"
done
# Recover assets created by the old cp implementation, including a stale read-only notice.
printf 'stale\n' > "$fixture/assets/LICENSE.txt"
chmod 0444 "$fixture/assets/LICENSE.txt"
copy_notices "$fixture/module with spaces" "$fixture/assets"
cmp "$fixture/module with spaces/LICENSE.txt" "$fixture/assets/LICENSE.txt"
test "$(stat -c '%a' "$fixture/assets/LICENSE.txt")" = 644
test "$(stat -c '%a' "$fixture/module with spaces/LICENSE.txt")" = 444
# A failed copy must fail the whole pipeline, rather than succeeding as find -exec cp did.
printf '#!/usr/bin/env bash\nexit 73\n' > "$fixture/bin/install"
chmod +x "$fixture/bin/install"
set +e
(set -euo pipefail; PATH="$fixture/bin:$PATH"; copy_notices "$fixture/module with spaces" "$fixture/assets")
result=$?
set -e
test "$result" = 73
printf 'Saturn notice regression checks passed.\n'
