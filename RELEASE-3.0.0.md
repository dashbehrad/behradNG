# behradNG 3.0.0

Version name: 3.0.0. Version code: 755.

Saturn is available under **+ → Saturn**. Its settings include a custom HTTPS DNS resolver,
explicit resolver bootstrap IPs, user-supplied obfs4 bridge lines, turbo/deep bridge testing,
and a Tor exit-country code (`auto` selects Tor's default).

The connection path is a local SOCKS proxy with DoH hostname resolution, an obfs4 bridge,
and a Tor circuit. DNS queries use the selected HTTPS resolver. Bridge TCP payloads connect
to the bridge itself. DoH can help with DNS interference; IP-blocked bridges still need a
reachable replacement. An improvement on Iranian carriers has not been measured.

Turbo checks up to 12 resolved candidates with three concurrent probes, a 1.5-second TCP
timeout, and selects up to three candidates. Deep checks up to 64 candidates with four
concurrent probes, a five-second TCP timeout, and selects up to eight. TCP latency ranks
reachability only; Tor must subsequently complete its obfs4 handshake and reach 100% bootstrap
before the client reports success. Paste valid custom bridge lines in the editor.

Saturn uses Tor's TCP SOCKS output. Existing app routing and per-app selections still apply.
Native support is Android 8+ on ARMv7, ARM64 and x86_64. Unsupported Android versions/ABIs
and root mode produce an explicit error. The other protocols retain their existing support.
Saturn profiles are standalone; their internal DoH/obfs4/Tor chain is managed by saturn-core.

`saturn://v1/<encrypted-payload>#<secret>` links use AES-256-GCM with a random nonce and a
SHA-256-derived key from a fresh random secret. SHA-256 alone is not encryption. These are
self-contained capability links: the fragment carries the secret, so anyone possessing the
entire link can decrypt and import it. They do not provide password protection against someone
who has the link. Keep the entire link private. Native Tor identity files and runtime ports
are excluded from the shared payload.

The release package ID and signing configuration are retained. Debug builds retain the
separate `.debug` package to avoid release-signature conflicts.

Tor Android and its native source, the Maven artifact checksum, and Lyrebird are pinned in
`saturn-core.lock.json`. Preparation scripts package geoip/geoip6 and dependency notices.
The Kotlin orchestration plus the native process supervisor comprise saturn-core; Tor and
Lyrebird remain their respective upstream projects.

This is a source publication with `[skip ci]`, as requested. No workflow, replacement APK,
or binary GitHub release is produced by this commit. See `VALIDATION-3.0.0.md` for checks and
the remaining build/device requirements.
