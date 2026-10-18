# APK installation

Use the APK attached to GitHub Releases to update a release installation. Pull-request artifacts are test builds: their package ID ends in .debug and their filenames end in -debug.apk. They install separately and do not share release profiles or settings.

Android rejects updates whose signing certificate differs from the installed app, even when the package name matches. An older test build using com.v2ray.ang.behradng may therefore conflict with a release APK. Back up/export profiles before considering removal of that older app; this change does not migrate its private data or change the release signing key.

"App not installed" alone does not identify the failure. To obtain the exact reason with a connected authorized device, run:

```text
adb install -r path/to/behradNG-2.5.0.apk
```

Retain the INSTALL_FAILED_* message. UPDATE_INCOMPATIBLE means a certificate/package conflict; VERSION_DOWNGRADE requires a build with a higher versionCode; NO_MATCHING_ABIS, OLDER_SDK, storage and invalid-package errors require different fixes. Do not uninstall as the default diagnostic step.

## Configurations returning after reinstall

Android can restore app data during installation, including a sideloaded APK, or
during phone transfer. Earlier behradNG builds allowed the operating system to
back up the MMKV directory containing profiles, subscriptions, and settings.

The app now excludes `files/mmkv/` from Android backup and restore on supported
Android versions, including both cloud backup and device transfer on Android 12+.
Explicit backup/import remains available. Updating the app preserves the current
local profiles; this change does not automatically delete user data or remove an
old backup from a cloud account.

To start empty, install an APK containing this change, stop the connection, then
use Android Settings > Apps > behradNG > Storage > Clear storage. This removes
local profiles and settings, so export anything you want to keep first. Open the
app again before reinstalling or importing anything. The old APK will still use
its previous backup policy.

If profiles return immediately after Clear storage without any reinstall or
explicit import, that is a separate issue: note the APK version and whether the
return happens before or after connecting to the internet. Android's documented
install-time restore does not by itself explain that sequence.

Source: https://developer.android.com/identity/data/autobackup

Validation: both manifests reference the legacy and modern XML rules. Static
checks confirmed the MMKV directory is excluded in the legacy, cloud, and device
transfer policies in both the base project and the fork overlay. XML parsing and
whitespace checks passed.

**Not run:** `gradlew.bat :app:assemblePlaystoreDebug` from `V2rayNG/`, or
`:app:assembleDebug` in the prepared fork project. The archive lacks the Gradle
wrapper JAR and prepared Android/native build inputs. Installation, clear-storage,
old-backup restore, and phone-transfer scenarios on Android 7–11 and Android 12+
also require a built APK and physical devices/emulators; they have not run.
Git instruction synchronization cannot run because this archive has no `.git`.

## Validation for the earlier package/signature change

Local checks: debug/release selector exercised for both event types; Bash syntax checked for workflow run blocks; debug shortcut XML and package targets checked; whitespace checked.

Not run: :app:assemblePlaystoreDebug (canonical project) / :app:assembleDebug and :app:assembleRelease (fork overlay), APK installation and upgrade on Android. The local checkout lacks prepared SDK/native inputs and the Gradle wrapper JAR; adb cannot start in this environment. No GitHub workflow was dispatched; this source commit uses [skip ci]. No replacement APK was built or uploaded by this change.
