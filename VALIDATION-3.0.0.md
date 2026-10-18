# Validation for 3.0.0

## Local checks

Sixteen standalone regression methods were compiled and executed against the production
Saturn settings, formatter, bridge selector, session configuration, proxy and editor ViewModel.
They cover preceding profile JSON, encryption round trips and tamper rejection, malformed
bridge/DoH input, turbo/deep limits, cancellation, Tor configuration, ViewModel loading and
storage failures, stable profile identity, and saved drafts. A real loopback SOCKS connection
exercised relay, destination rejection and listener teardown.

These checks used local Android, lifecycle, JUnit, Mockito and OkHttp API shims. Gson and
coroutines were real local libraries. The DoH resolver shim never made external requests.
These are not full Android/Gradle/JUnit or HTTPS/Tor validation.

Kotlin PSI syntax checks, resource/manifest XML parsing, Bash syntax, PowerShell AST parsing,
workflow shell-block parsing and whitespace checks are performed locally before publication.
No emulator, physical phone or native Tor process was used.

## Not run

- `git fetch upstream master` and the guide's two Git instruction-diff commands: the workspace
  is an extracted archive without `.git`, and shell access to GitHub is unavailable. Changed
  existing-file baselines are checked against main with the authorized GitHub connector.
  Agent instructions are excluded from the publication.
  Exact unavailable instruction checks:
  `git diff --cached --name-only -- ':(glob)**/AGENTS.md' ':(glob)**/CLAUDE.md' ':(glob)**/GEMINI.md' .github/copilot-instructions.md`
  and `git diff --name-only upstream/master...HEAD -- ':(glob)**/AGENTS.md' ':(glob)**/CLAUDE.md' ':(glob)**/GEMINI.md' .github/copilot-instructions.md`.
- Clean submodule/native preparation, `prepare-saturn.sh`, `prepare-saturn.ps1`, and APK ZIP
  verification for every declared app ABI: native downloads/toolchains are not prepared locally.
  Tor/Lyrebird preparation is provided for the three Saturn ABIs; x86 explicitly lacks Saturn.
- `gradlew.bat :app:testPlaystoreDebugUnitTest --tests '*Saturn*'`,
  `:app:testPlaystoreDebugUnitTest`, `:app:compilePlaystoreDebugKotlin`, and
  `:app:assemblePlaystoreDebug`: the clean canonical checkout lacks the Gradle wrapper JAR and
  prepared Android/native inputs. For the fork overlay these tasks are `:app:testDebugUnitTest`,
  `:app:compileDebugKotlin`, `:app:assembleDebug` and `:app:assembleRelease` after copying app/.
- Real HTTPS resolver success, TLS/redirect failure, bootstrap-address failure and DNS-leak
  checks; obfs4 authentication, Tor 100% bootstrap, country selection and DNS/UDP behavior:
  require native inputs, a network and a supported Android device.
- VPN and proxy-only cold start, duplicate start, failed setup after resource acquisition,
  normal/repeated stop, stop during DNS/scan/bootstrap, restart and stop racing restart,
  revoke/destroy/daemon death, network handover and stale-settings rejection: require a device.
  Existing Xray VPN/proxy/root branches and Aether also need regression checks after packaging.
- API 24/25 and x86 unsupported-state checks, supported ABIs and 4/16 KiB page-size checks:
  require corresponding devices/emulators and produced native binaries.
- Editor persistence/reopen and activity recreation; empty/loading/error/populated states;
  touch/keyboard/D-pad activation, TalkBack semantics/focus, and first-to-last control access
  with the IME open: require an Android device/emulator.
- APK install/upgrade with the retained release certificate and separate debug package,
  carrier throughput, latency, battery and sustained-connection measurements: no APK/device.
- GitHub workflows: intentionally skipped by the source commit; none dispatched.

The app's actual Tor connectivity, Iran performance, installation and APK packaging are not
verified by the standalone checks. A later authorized full build/device pass is still required.
