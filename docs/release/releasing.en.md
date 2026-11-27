# Building and releasing YggdrasilManager

**English** | [繁體中文](releasing.zh-TW.md) | [简体中文](releasing.zh-CN.md)

The Manager version comes from the root `version.properties`. The Android home screen already reads `BuildConfig.VERSION_NAME`, so the UI and APK use the same value. The kernel module has its own version and release cycle.

## Toolchain

| Component | Version |
| --- | --- |
| JDK | 21 |
| Node.js | 22 or later; GitHub Actions uses 22 |
| Gradle wrapper | 9.4.1, with distribution SHA-256 verification |
| Android Gradle plugin | 9.2.1 |
| Android compile SDK | 36.1 (`platforms;android-36.1`) |
| Android build tools | 36.0.0 |
| Android NDK | 28.2.13676358 |
| CMake | 3.22.1 |

The APK contains only `arm64-v8a`; the KPM ABI, syscall catalog and bundled self-test target arm64. The Android minimum is API 24 and target is API 36.

Install Android packages through Android Studio or `sdkmanager`:

```sh
sdkmanager 'platforms;android-36.1' 'build-tools;36.0.0' 'ndk;28.2.13676358' 'cmake;3.22.1'
```

Point `ANDROID_HOME` at your SDK, or set `sdk.dir` in the ignored `local.properties`. Use `.\gradlew.bat` instead of `bash ./gradlew` on Windows PowerShell. `bash` is explicit because the checked-in wrapper does not currently have the Unix executable bit.

## Version management

```sh
node scripts/version.mjs show
node scripts/version.mjs bump patch
```

`bump major`, `bump minor` and `bump patch` advance the corresponding SemVer component, clear a prerelease suffix and increment Android `versionCode` by one. Choose one bump per release.

For a prerelease, or to promote a prerelease to its final version, use `set`:

```sh
node scripts/version.mjs set 1.1.0-rc.1
node scripts/version.mjs set 1.1.0
```

Each `set` also increments `versionCode`. Equal or lower SemVer versions are rejected. Versions omit build metadata (`+...`) so every accepted name has a unique release tag. Codes must stay between 1 and 2,100,000,000 and must exceed every previously distributed release code. Do not reset the code when changing a major version.

The private frontend package's `version` is a tooling package value; it does not determine the Manager version. The root version file is the release authority.

Update the [changelog](changelog.en.md) before each tag. Release workflows accept only a tag exactly equal to `v` plus `versionName`:

```sh
node scripts/version.mjs check-tag v1.0.0
```

## Release key

For the initial setup on a new project or fork:

```sh
node scripts/generate-signing-key.mjs
```

The script generates a 4096-bit RSA key in a PKCS#12 keystore with a random password, SHA256withRSA signing and a 10,000-day certificate validity. It requires JDK `keytool`, preferring `JAVA_HOME/bin` when set. It refuses to overwrite an existing keystore or local properties file.

| File | Handling |
| --- | --- |
| `.signing/yggdrasilmanager-release.p12` | Private key; ignored by Git; back up privately. |
| `keystore.properties` | Passwords and alias; ignored by Git; back up privately with the key. |
| `.signing/release-certificate.pem` | Generated public certificate. |
| `signing/release-certificate.pem` | Public certificate committed for release verification. |

For this repository the release key has already been generated. Reuse it for all updates. Losing the key prevents updates signed by it; generating a replacement is not a normal release step. Keep an encrypted backup outside this workspace; GitHub Secrets cannot export a backup.

For a fork using its own newly generated key, copy its public certificate to `signing/release-certificate.pem` before releasing. Gradle does not enforce this certificate for contributor builds; the release workflow enforces it before publishing. Never copy the private keystore into `signing/`.

To use an existing key, copy `keystore.properties.example` to `keystore.properties` and fill all four values. `storeFile` is relative to the repository root unless absolute; use forward slashes on Windows. Environment variables override local properties:

| Variable | Purpose |
| --- | --- |
| `YGGDRASIL_KEYSTORE_FILE` | Keystore path |
| `YGGDRASIL_KEYSTORE_PASSWORD` | Keystore password |
| `YGGDRASIL_KEY_ALIAS` | Signing alias |
| `YGGDRASIL_KEY_PASSWORD` | Key password |

A release build without all four credentials fails. There is no debug-key fallback for release builds. Shrinking remains disabled because JNI and libsu root services need a separate device validation before enabling it.

## Local builds and verification

The frontend sync replaces `app/src/main/assets/observe-web/` with the current source build. Always rebuild it before packaging:

```sh
npm ci --prefix frontend
npm test --prefix frontend
npm run sync:android --prefix frontend
node --test scripts/test/*.test.mjs
bash ./gradlew :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease
```

Outputs:

- `app/build/outputs/apk/debug/app-debug.apk`: package `ng1ok.yggdrasilmanager.debug`, version suffix `-debug`.
- `app/build/outputs/apk/release/app-release.apk`: signed package `ng1ok.yggdrasilmanager`.
- `app/build/reports/`: unit-test and lint reports.

Older development builds used the release package ID with a debug certificate. Android cannot update those installs with the new release certificate; remove that old install before installing a release, preserving any settings you need first. New debug builds use the separate `.debug` ID and do not have that conflict.

From the SDK's `build-tools/36.0.0` directory, use `apksigner` and `zipalign` (`apksigner.bat` and `zipalign.exe` on Windows):

```sh
apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
zipalign -c -P 16 -v 4 app/build/outputs/apk/release/app-release.apk
keytool -printcert -file signing/release-certificate.pem
```

Add that directory to `PATH` or call the tools by absolute path; run the commands from the repository root. Match the APK's signing SHA-256 digest to the public certificate. The release workflow performs this check automatically and generates `SHA256SUMS` for the APK and public certificate.

## GitHub Actions setup

The repository must have Actions enabled. Repository Secrets are configured with an authenticated GitHub CLI:

```sh
node scripts/configure-github-signing.mjs ngiokweng/YggdrasilManager
```

Use your own `OWNER/REPOSITORY` for a fork. The script sends values through standard input and replaces these four Actions Secrets without printing their contents:

- `YGGDRASIL_KEYSTORE_BASE64`
- `YGGDRASIL_KEYSTORE_PASSWORD`
- `YGGDRASIL_KEY_ALIAS`
- `YGGDRASIL_KEY_PASSWORD`

The setup is already completed for `ngiokweng/YggdrasilManager`. Do not upload `keystore.properties` or the private key as workflow artifacts. Signed release builds disable persistent Gradle caches and the configuration cache so signing data is not persisted in uploaded caches.

| Workflow | Trigger | Result |
| --- | --- | --- |
| `CI` | Branch pushes, PRs and manual runs | Version tests, npm audit, frontend tests/build, Android tests, debug/release lint and APK compilation; uploads debug APK and reports. |
| `Release` | `v*` tags; manual reruns from a tag | Checks tag/version agreement, runs tests/audit/lint, builds with the private key, verifies the certificate and 16 KB ZIP alignment, then publishes APK, certificate and checksums to a GitHub release. |

PRs, including forks, generate disposable signing keys and need no release secrets. The CI release APK is only a compilation check and is not distributed. Actions are pinned to commit SHAs. Dependabot version-update PRs for Actions, Gradle and npm are disabled with `open-pull-requests-limit: 0`; security-update settings are unchanged.

## Publishing

Commit the reviewed source, generated browser assets, public certificate, version and changelog, then push the branch. Tag the same commit only after CI passes. For the initial version:

```sh
git tag -a v1.0.0 -m 'YggdrasilManager 1.0.0'
git push origin v1.0.0
```

Subsequent releases must use the bumped version. A tag such as `v1.1.0-rc.1` creates a GitHub prerelease. Existing releases are not overwritten; do not move published tags. A manual retry uses the tag as its ref, for example `gh workflow run release.yml --ref v1.0.0` after the workflow exists on the default branch.

The workflow does not change repository visibility. Select the project license and complete the [remaining open-source decisions](open-source-readiness.en.md) before making the repository public. A GitHub-hosted run cannot be verified until the workflow changes are committed and pushed.
