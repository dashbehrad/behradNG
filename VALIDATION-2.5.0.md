# Version 2.5.0 validation

## Executed locally before commit

- Eleven standalone regression methods cover preceding profile JSON, GUID round-trips and renames, unsupported/ambiguous/missing/duplicate members, Aether entry order, runtime-port isolation, ordinary chains, editor save/load failures, draft restoration by row ID, and deletion outcomes.
- These methods compiled from the production ProfileItem, CoreConfigContextBuilder and ProxyChainEditorViewModel sources, with actual Gson/coroutines and small Android/lifecycle/JUnit API shims. This is not a Gradle/JUnit run or Android runtime validation.
- The actual deletion and rollback method bodies also passed a standalone fake-storage check at all four mutation failure points, plus selected-profile protection. This does not execute MMKV on Android.
- Ten locale resource XML files, Iran rule JSON, the license checksum, and whitespace checks passed.
- Kotlin syntax parsing covers every changed Kotlin source, including canonical files and existing root app overrides.
- Bash syntax and PowerShell parser checks cover the dataset preparation scripts and changed Windows build script.

## Not run

- Gradle targeted tests: :app:testDebugUnitTest --tests com.v2ray.ang.core.ProfileItemChainTest, --tests com.v2ray.ang.core.CoreConfigContextBuilderTest and --tests com.v2ray.ang.ui.server.ProxyChainEditorViewModelTest in the prepared fork project. Local wrapper JAR and generated/native build inputs are absent; network downloads are blocked.
- Full :app:testDebugUnitTest, :app:compileDebugKotlin and :app:assembleRelease in the prepared fork project, and canonical :app:testPlaystoreDebugUnitTest, :app:compilePlaystoreDebugKotlin and :app:assemblePlaystoreDebug. Same local build-input blocker. A PR workflow now runs the full debug suite, compilation and APK assembly remotely.
- APK signature/package/version, pinned geo files and native-library ABI checks: no local APK. The workflow contains these checks for its output APK.
- Device VPN and proxy-only checks: registration, cold start, two equivalent consecutive starts, correct chain egress, direct Iran/private routing, proxied DNS, a failure after partial acquisition, normal/repeated stop, stop during setup, restart and stop racing restart, daemon death, and network handover. No connected device.
- UI checks: save/reopen persistence, recreation by member GUID, touch/keyboard/D-pad selection, TalkBack semantics/focus, IME visibility and scrolling, light/dark and dynamic-color/single/double-column behavior of the shared dropdown. No emulator/device.
- MCI, Irancell and TCI throughput/latency/CPU/battery benchmarks. No carrier-connected device. No universal speed improvement is claimed.
- Required Git upstream fetch/instruction diff commands: this is an archive without .git and outbound Git connections are blocked. Publication uses the previously authorized GitHub connector path; instruction files are excluded. Existing changed-file baselines were compared with current main through GitHub.
