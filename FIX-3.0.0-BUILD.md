# 3.0.0 native preparation and Gradle configuration fix

The failed run was https://github.com/dashbehrad/behradNG/actions/runs/39042175702.

Go module-cache license files are read-only. Copying them with `cp` retained that
mode in generated assets, so later ABI builds could not overwrite them. The Bash
notice helper now uses `install -m 0644`, including for existing read-only copies.
Its pipeline propagates copy failures; `find -exec cp ... \;` previously allowed
preparation to succeed despite missing notices. PowerShell clears the read-only
attribute on generated notice copies without changing the module cache.

The actual Gradle failure was an ambiguous `:app:testDebugUnitTest` task. The
3.0.0 overlay had accidentally replaced behradNG's build configuration with the
upstream flavor-based configuration. Restore the fork configuration from the
last successful release source (`33408101dcd56986f9ce0c107735faefdbadf3ca`), retaining
version 3.0.0/code 755 and Saturn's DoH dependency. This restores the existing CI
task names, universal APK path, behradNG package ID, debug suffix, and persistent
release signing configuration. The canonical upstream build file is unchanged.

Local checks:

- `bash tests/saturn-notices.sh`: repeated copies for three ABIs, read-only source
  files, an existing read-only destination, spaces in paths, unchanged source
  permissions, notice filtering, and copy-failure propagation.
- `bash -n prepare-saturn.sh` and PowerShell AST parsing for `prepare-saturn.ps1`.
- Compare the restored build file with the last successful release configuration;
  only the version and Saturn dependency differ. Check patch whitespace locally.

Not run: `gradlew.bat :app:assemblePlaystoreDebug` and the workflow's
`:app:testDebugUnitTest :app:compileDebugKotlin :app:assembleRelease`. This archive
lacks the Windows Gradle wrapper and wrapper JAR, initialized native submodules, and prepared
Android build dependencies. No full APK build or device installation is verified.

Not run: native preparation for every declared ABI and a physical-device Saturn
connection check; the Android NDK/build inputs and a connected test device are
unavailable. The notice fixture does not substitute for a native build.

Not run: `git fetch upstream master`, upstream instruction synchronization, and
the required staged/branch instruction-diff checks. The workspace is an extracted
archive without `.git`. Publication uses explicit task files through the GitHub
connector; no instruction files or generated binaries are included.

Publish with `[skip ci]`; do not dispatch workflows or create release tags.
