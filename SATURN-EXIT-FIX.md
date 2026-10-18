# Saturn exit-stream and SOCKS timeout fix

The reported connection used Automatic country. In that mode Saturn does not emit
`ExitNodes`, so a selected-country restriction does not explain the failure.

## Source findings

- SlipNet `552ab5078d90ae84294fcd6ef649b8e023ba2a63` leaves exit selection
  unrestricted. Its `TorSocksBridge` waits up to 30 seconds for the Tor path,
  preserves domain-name SOCKS requests, and sends DNS over TCP through Tor.
- The pinned Xray source (`701af60772cd`) selects policy level **0** for an
  unauthenticated SOCKS outbound. `settings.level` does not create an authenticated
  user. The app template only configures level 8 and a four-second handshake.
  Saturn therefore used Xray's short default when waiting for Tor to open an exit
  stream. This is a concrete failure path; it is not proof of the device's cause.
- Tor bootstrap completion was the only readiness condition. It did not test an
  exit stream, so the app could report connected before confirming exit access.

References:

- https://github.com/anonvector/SlipNet/blob/552ab5078d90ae84294fcd6ef649b8e023ba2a63/app/src/full/java/app/slipnet/tunnel/SnowflakeBridge.kt
- https://github.com/anonvector/SlipNet/blob/552ab5078d90ae84294fcd6ef649b8e023ba2a63/app/src/main/java/app/slipnet/tunnel/TorSocksBridge.kt
- https://github.com/XTLS/Xray-core/blob/701af60772cd/proxy/socks/client.go
- https://github.com/XTLS/Xray-core/blob/701af60772cd/infra/conf/socks.go

## Changes

Only Saturn configuration receives a minimum 30-second handshake allowance,
including unauthenticated SOCKS policy level 0. Longer existing allowances and
other policy fields are preserved. Both the base project and behradNG overlay
receive the same configuration change.

After bootstrap, Saturn must open a SOCKS CONNECT stream to
`www.torproject.org:443` through its own Tor listener before publishing readiness.
Hostname resolution stays at the Tor exit. Transient failures retry for up to
60 seconds; cancellation closes the pending socket and stops the probe. This is
an exit TCP-connect check, not a TLS, HTTP, bandwidth, or VPN-routing test.

Exit-check failure has a separate startup stage and localized error in all ten
supported locales. The saved country is preserved; Auto still emits no ExitNodes.
A reachable exit does not guarantee the configured ping/IP service accepts Tor.

## Local checks

19 standalone checks passed, exercising the production Saturn session class,
its settings and configuration helpers, plus the existing/new Kotlin test methods.
Checks included a real loopback SOCKS server accepting and rejecting CONNECT,
remote hostname addressing, socket closure on cancellation, retries, timeout,
owner cancellation, country configuration, and preservation of policy fields.

The standalone harness used the cached Kotlin compiler, real JVM networking and
coroutines, and shims for Android/JUnit/unexercised native and DoH interfaces.
Core configuration helpers were extracted unchanged from production source.
This was not a Gradle/JUnit-engine or Android runtime test. The ignored harness is
`.local-apk-build/saturn-exit-check/run.ps1`.

## Not run

- From `V2rayNG/`: `gradlew.bat :app:testPlaystoreDebugUnitTest --tests com.v2ray.ang.core.SaturnExitTest --tests com.v2ray.ang.core.SaturnConfigurationTest --tests com.v2ray.ang.core.SaturnBootstrapTest --tests com.v2ray.ang.core.CoreConfigManagerTest`.
- `gradlew.bat :app:testPlaystoreDebugUnitTest :app:compilePlaystoreDebugKotlin :app:assemblePlaystoreDebug`.
- In the prepared behradNG overlay project:
  `gradlew.bat :app:testDebugUnitTest :app:compileDebugKotlin :app:assembleDebug`.

These checks cannot run in this source archive: `V2rayNG/gradlew.bat`, the wrapper
JAR, and prepared native inputs are absent. Attempting the root Windows wrapper
from `V2rayNG/` fails before Gradle starts because its wrapper JAR is absent.
Maven download access also failed in this session.

Not run: device checks for Saturn startup and browsing with Auto country in VPN
and proxy-only modes; a selected country with and without usable exits; failure
cleanup after acquiring native processes; stop during the exit check; normal and
repeated stop; DNS resolution, ping, and IP detection after connection. These need
an assembled APK and an emulator or physical device with working Tor access.
The reported device problem is not runtime-verified as resolved.

Not run: `git fetch upstream master`, instruction synchronization and branch diff
checks: this extracted workspace has no `.git`. No instruction files were edited.
No workflow or APK release was started for this change.
