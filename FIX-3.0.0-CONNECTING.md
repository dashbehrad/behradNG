# Saturn linker fix and connecting state

The pinned Lyrebird/anet dependency accesses the private Go symbol `net.zoneCache` through
`go:linkname`. Go 1.23+ rejects this unless its linker is passed `-checklinkname=0`.
Both Saturn preparation scripts now pass that flag while retaining PIE and 16 KiB alignment.
Remove the compatibility flag once the pinned dependency no longer uses that private linkname.
Upstream explanation: https://github.com/wlynxg/anet#how-to-build-with-go-1230-or-later

The main dashboard shows yellow status styling, a yellow power button and localized
“Connecting” text during daemon startup. The power button cancels pending startup. Ready,
failed and stopped events clear connecting. Connection-test progress does not hide pending
startup. Saturn/Aether report pending state when a newly created UI asks for a snapshot;
the UI does not infer readiness from the button press or a bound service.

## Local checks

- A Go 1.26 host fixture reproduced `invalid reference to net.zoneCache` without the flag
  and linked successfully with it. This is not an Android/Lyrebird cross-build.
- Nine standalone methods passed for UI state transitions and the native lifecycle guard,
  using production state/lifecycle code with bitmap/JUnit API shims. These are not Gradle tests.
- Changed Kotlin PSI syntax, locale XML consistency, Bash/PowerShell syntax and whitespace
  are checked before publication. Agent instructions and temporary/native binaries are excluded.

## Not run

- `prepare-saturn.sh` / `prepare-saturn.ps1` Android builds and native APK inspection on all
  declared ABIs: local NDK/native inputs and dependency downloads are unavailable.
- `gradlew.bat :app:testPlaystoreDebugUnitTest --tests '*MainConnectionStateTest' --tests '*AetherLifecycleTest'`,
  `:app:testPlaystoreDebugUnitTest`, `:app:compilePlaystoreDebugKotlin`,
  `:app:assemblePlaystoreDebug`: missing wrapper JAR and prepared Android/native inputs.
  Fork-overlay equivalents are `:app:testDebugUnitTest`, `:app:compileDebugKotlin`,
  `:app:assembleDebug` and `:app:assembleRelease`.
- VPN/proxy/root startup, duplicate start, success/failure, cancel during setup, repeated stop,
  restart races, activity recreation during Saturn/Aether bootstrap and snapshot reconciliation:
  require an Android device/emulator. These lifecycle behaviors are not runtime-verified.
- Light/dark/dynamic color rendering, yellow-state contrast, touch/keyboard/D-pad cancellation,
  and TalkBack focus/name/state inspection: require an Android device/emulator.
- `git fetch upstream master` and the root guide's instruction-diff commands: this archive has
  no `.git` and shell GitHub access is unavailable. Existing-file baselines are compared with
  GitHub main through the connector; no instruction files are published.
  The unavailable checks are:
  `git diff --cached --name-only -- ':(glob)**/AGENTS.md' ':(glob)**/CLAUDE.md' ':(glob)**/GEMINI.md' .github/copilot-instructions.md`
  and `git diff --name-only upstream/master...HEAD -- ':(glob)**/AGENTS.md' ':(glob)**/CLAUDE.md' ':(glob)**/GEMINI.md' .github/copilot-instructions.md`.
- GitHub workflows: intentionally skipped with `[skip ci]`; no APK rebuilt or uploaded.
