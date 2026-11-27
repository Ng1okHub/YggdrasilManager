# Remaining open-source decisions

**English** | [繁體中文](open-source-readiness.zh-TW.md) | [简体中文](open-source-readiness.zh-CN.md)

Inspection date: 2026-11-27. The release build, Manager version source, local signing tools, GitHub verification/release workflows, README and changelog are now in place. These are the remaining decisions and checks before a public launch.

## Before the first public release

1. **Select a project license.** There is no root `LICENSE`, and GitHub reports no license. A public repository alone does not grant reuse rights. Choose the license for code you own and verify compatibility with the kernel ABI header, native dependencies, JVM dependencies and frontend assets before adding it. A license choice has not been made on your behalf.
2. **Complete third-party attribution.** The vendored `libunwindstack-ndk/LICENSE` includes Apache and Unicode notices, and individual native files retain upstream copyright headers. Audit the native, Gradle and npm dependency licenses, preserve the required notices, and decide how to distribute them with the APK (for example an About/licenses page plus a notice asset).
3. **Decide the browser-console access model.** Both servers currently listen on `0.0.0.0` (HWBP `8765`, Syscall `8766`) and accept configuration commands without authentication. Document trusted-network use; consider binding to localhost by default with ADB forwarding, or adding authentication and origin validation before broader distribution. Changing that behavior is a separate product decision.
4. **Back up the release key outside this workspace.** Keep an encrypted copy of both `.signing/yggdrasilmanager-release.p12` and `keystore.properties`, and confirm it can reproduce the public certificate. The four GitHub Actions Secrets are configured, but they cannot substitute for a recoverable backup.
5. **Run a release-device smoke test.** Install the release APK and exercise Root binding, module detection, all four tools, both browser consoles and the bundled self-test. Include API 24/25 when claiming Android 7 support and a 16 KB page-size device when available. JVM tests, lint, signature verification and ZIP alignment checks do not prove those runtime behaviors.

## Repository maintenance

- Push the reviewed changes and confirm the first GitHub Actions run. Protect the default branch (`master` at inspection time) with the CI check and review rules, and protect version tags. Rulesets could not be inspected while this private repository is on its current GitHub plan (API returned HTTP 403).
- Define how to contribute and report vulnerabilities. `CONTRIBUTING.md`, `SECURITY.md`, issue templates and a PR template are currently absent. Include the supported module versions and a minimal diagnostic report in issue guidance.
- Audit full Git history for credentials and private material before changing visibility. Current ignore rules protect new signing files; they do not remove anything from existing commits. No full-history secret scan has been performed in this change.
- Record tested device/module combinations and the initial release date in the changelog. The frontend dependency audit was clean after updating the lockfile; the remaining Android lint warnings include dependency updates and unused resources.

The repository was private at inspection time. Change its visibility only after completing the public-release checks above.
