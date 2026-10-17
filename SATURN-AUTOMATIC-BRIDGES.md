# Automatic Saturn bridges and country selection

New Saturn profiles use automatic obfs4 bridge selection. Existing 3.0.0 profiles
with supplied bridges remain custom; choose **Bridge source → Automatic** to use
discovery. Switching modes preserves the stored custom bridge text. Countries
are selected from a read-only dropdown with Automatic and 249 ISO country names,
localized for the ten app locales. Saved country codes remain unchanged in links.
Some countries have no usable Tor exits; Automatic gives Tor the widest choice.

At startup, Saturn checks the configured DoH resolver, requests Tor's public
`/moat/circumvention/builtin` API over HTTPS using that resolver, and combines valid
obfs4 descriptors with the bundled Orbot pool. The refresh is cancellable, bounded
to four seconds and 64 KiB, and shares the session's HTTP cleanup. HTTP redirects,
system DNS fallback and malformed/non-obfs4 descriptors are not used.
Refresh failure is logged without bridge addresses and falls back to the bundle.
Sources and the upstream license are packaged under `assets/third_party/saturn`.

Both scan modes test every candidate (at most 64), rank reachable endpoints by TCP
latency, and give all reachable candidates to Tor with the fastest first. Keeping
the remaining candidates lets Tor try alternatives after a failed handshake. Mode controls
probe timeout and concurrency. This ranks TCP reachability, not a completed obfs4
handshake; only Tor bootstrap to 100% makes the service report connected. Public
bridges can be blocked, and discovery cannot guarantee connectivity in Iran.
DoH resolves names and does not carry the obfs4 traffic as an HTTPS tunnel.

The screenshot's old generic error does not establish its underlying cause.
Failures now distinguish resolver access, unavailable bridges, native startup,
Tor bootstrap and native exit codes, without exposing bridge details. Correct a
separate timeout bug: an owned bootstrap timeout now becomes a reported startup
failure and follows cleanup; cancellation by the service continues to propagate.

Compatibility checks cover preceding Saturn JSON without `bridgeSource`, custom
bridge preservation, automatic encrypted-link round trips and saving automatic
profiles without manual bridges. There is no native-core upgrade in this change.

Validation performed locally:

- 21 standalone Saturn test methods passed after compiling the exercised Kotlin
  with real Gson/coroutines and Android/lifecycle/OkHttp/JUnit shims. All test
  signatures return JVM void. These checks include real loopback SOCKS relay,
  candidate ranking, fallback merging, validation, cancellation, bootstrap timeout,
  preceding-format reading, encrypted links and editor save behavior.
- Ten new resource XML files parse, each has matching 249-country name arrays and
  ten control/error strings. The base code array has 249 unique ISO codes.
- Changed service and editor Kotlin parse. Inspect the explicit patch for whitespace
  errors and compare existing file baselines with GitHub main before publication.

Not run: the canonical project's `gradlew.bat :app:testPlaystoreDebugUnitTest
--tests com.v2ray.ang.core.SaturnBootstrapTest`, equivalent targeted checks for
`SaturnBridgeSelectorTest`, `SaturnSettingsTest`, `SaturnFmtTest`,
`SaturnEditorViewModelTest`, and `SaturnDohProxyTest`; the complete
`:app:testPlaystoreDebugUnitTest :app:compilePlaystoreDebugKotlin
:app:assemblePlaystoreDebug` checks; and the overlaid fork's
`:app:testDebugUnitTest :app:compileDebugKotlin :app:assembleRelease`. The extracted
workspace lacks the Windows wrapper, wrapper JAR and prepared Android/native inputs.
Standalone checks do not validate the real OkHttp API, JUnit engine, Compose
compilation, TLS/DoH service access or an APK.

Not run: device checks for cold start, duplicate start, setup failure after acquiring
the DoH listener, normal/repeated stop, stopping during refresh/probes/bootstrap,
and timeout cleanup in VPN and proxy-only modes. Root mode remains unsupported.
No emulator or physical device is available to verify Tor/obfs4 or Iran connectivity.

Not run: country and source dropdown touch, keyboard, D-pad, TalkBack, light/dark
theme checks, opening/closing the IME, rotation with a saved draft, and reopening a
saved profile. These require an Android device. No screenshot or JVM check proves
these UI behaviors.

Not run: `git fetch upstream master`, instruction synchronization and the required
staged/branch instruction-diff checks: this extracted workspace has no `.git`.
Publish explicit task files through the GitHub connector with `[skip ci]`. Do not
dispatch workflows, create tags, or publish generated binaries.

Bridge source reference:
https://github.com/guardianproject/orbot-android/blob/c4061501535b1b9d9b5342e9d342ab8cfa19ac4f/app/src/main/assets/builtin-bridges.json

API reference implementation:
https://github.com/guardianproject/orbot-android/blob/c4061501535b1b9d9b5342e9d342ab8cfa19ac4f/app/src/main/java/org/torproject/android/service/circumvention/MoatApi.kt
