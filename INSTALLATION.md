# APK installation

Use the APK attached to GitHub Releases to update a release installation. Pull-request artifacts are test builds: their package ID ends in .debug and their filenames end in -debug.apk. They install separately and do not share release profiles or settings.

Android rejects updates whose signing certificate differs from the installed app, even when the package name matches. An older test build using com.v2ray.ang.behradng may therefore conflict with a release APK. Back up/export profiles before considering removal of that older app; this change does not migrate its private data or change the release signing key.

"App not installed" alone does not identify the failure. To obtain the exact reason with a connected authorized device, run:

```text
adb install -r path/to/behradNG-2.5.0.apk
```

Retain the INSTALL_FAILED_* message. UPDATE_INCOMPATIBLE means a certificate/package conflict; VERSION_DOWNGRADE requires a build with a higher versionCode; NO_MATCHING_ABIS, OLDER_SDK, storage and invalid-package errors require different fixes. Do not uninstall as the default diagnostic step.

## Validation for this source change

Local checks: debug/release selector exercised for both event types; Bash syntax checked for workflow run blocks; debug shortcut XML and package targets checked; whitespace checked.

Not run: :app:assemblePlaystoreDebug (canonical project) / :app:assembleDebug and :app:assembleRelease (fork overlay), APK installation and upgrade on Android. The local checkout lacks prepared SDK/native inputs and the Gradle wrapper JAR; adb cannot start in this environment. No GitHub workflow was dispatched; this source commit uses [skip ci]. No replacement APK was built or uploaded by this change.
