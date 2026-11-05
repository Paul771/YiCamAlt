# Phase 6 — Release build evidence (REL-01)

- Date: 2026-11-05
- Commit of the clean-clone run: `f75ec64` (branch `gsd/phase-6-release`)
- Values deliberately not recorded: passwords, key location (beyond the template below), certificate DN and digests, vendor lib hashes, user paths, Gradle output lines around signing.

## REL-01 criteria

| Criterion | Result | Evidence (value-free) |
|---|---|---|
| Release APK is signed | PASS | `check-release-apk.ps1`: `verified=true v2=true v3=true signers=1` |
| Release signer differs from debug | PASS | `-CompareApk app-debug.apk`: `signerSame=false`, `RESULT=PASS` |
| ARM-only ABIs | PASS | `abis=arm64-v8a,armeabi-v7a` |
| Vendor natives extracted at build time, pinned | PASS | `vendorLibs=8/8 otherLibs=0` (per `app/vendor-natives.sha256`; hashes not written here) |
| Version | PASS | `package=com.yicamalt versionName=1.0.0 versionCode=10000` |
| Not debuggable | PASS | `debuggable=false` |
| R8 off | PASS | 06-02: `:app:assembleRelease --dry-run` -> 0 lines `minifyReleaseWithR8` |
| Missing key fails value-free | PASS | 06-02: missing signing file -> `code=RELEASE_SIGNING_MISSING file=missing keys=0/4 store=missing`, exit 1 |
| JVM tests without key | PASS | 06-02: RedactionScannerTest and `:app:assembleDebug` BUILD SUCCESSFUL without the signing file |
| Clean clone build | PASS | `tools/clean-clone-build.ps1` (online, no `--offline`): `trackedVendorFiles=0 apkDir=absent appBuild=absent localProperties=absent`; `build=OK vendorNatives=OK signing=ok offline=false`; `apkCheck=PASS signerSame=true` (same external key as the repo APK); `trackedChanged=0 untrackedNotIgnored=0`; `removed=true`; `RESULT=PASS commit=f75ec64`, exit 0; 0 `yicamalt-clean-*` folders left in TEMP |
| No vendor / key file tracked | PASS | `git ls-files` matching `apk/apks/apkm/xapk/aab/so/dex/jks/keystore/p12/pfx` or `release-signing/keystore.properties` = 0 -> `NO_TRACKED_VENDOR_OR_KEY` |
| Build outputs ignored | PASS | `git check-ignore`: `app/build/outputs/apk/release/app-release.apk` ignored, `_apk/x.apk` ignored |
| Pre-commit range scan over the phase | PASS | `tools/git-hooks/pre-commit --range <phase base>..HEAD` exit 0 |
| Hook self-test | PASS | `tools/git-hooks/test-pre-commit.sh`: `RESULT: all 13 cases passed` |
| Key outside the repo | PASS | `new-release-key.ps1 -Check`: `keys=4/4 store=found acl=ok`; key lives under `%USERPROFILE%\.yicamalt\`; backup of the key is pending with the user |

## Re-run on the device build commit (plan 06-04)

Full gate `:app:testDebugUnitTest --rerun :app:assembleDebug :app:assembleRelease` BUILD SUCCESSFUL, exit 0: 77 suites, 1103 tests, 0 failures, 2 skipped (RealLogScanTest without a log file); `check-release-apk.ps1 -CompareApk app-debug.apk` -> `signerSame=false`, `RESULT=PASS`.

Clean clone re-run for the installed APK: commit 664db41 RESULT=PASS

## How the clean clone build works

`tools/clean-clone-build.ps1` requires a committed worktree, clones HEAD into `<temp>/yicamalt-clean-<id>` with `--no-hardlinks`, checks out the exact commit detached, and runs the clone's own `tools/gradle.ps1 :app:assembleRelease --no-daemon --no-build-cache -PvendorApkDir=<repo _apk>`. The signing file is found at its default location and is never copied into the clone. Gradle output stays in memory and is never printed; only the anchors `vendor natives: OK (8 libs, manifest match)` and `BLOCK_VERIFY_RELEASE_SIGNING] result=ok` are read from the clone's log. The clone is removed only after guards: under the system temp root, name prefix `yicamalt-clean-`, not under the repo.

## Install note

The release APK is signed with a different key than the debug build. The first install on the phone requires removing the debug build first (login and app data are reset). This is the uninstall-before-install checkpoint of plan 06-06; no APK was installed in this plan.
