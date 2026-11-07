# BaroLauncher versioning policy

Version format: **MAJOR.RELEASE.SERVICE_PACK.PATCH[-CHANNEL.N]**.

- `1.0.0.0-beta.1`: first beta for release 1.0.
- `1.0.0.0`: first stable (GA) release.
- `1.0.1.0`: release 1.0 Service Pack 1.
- `1.0.1.1`: Service Pack 1 Patch 1.
- `1.1.0.0`: next feature release.
- `2.0.0.0`: incompatible major release.

Pre-release channels: `dev`, `alpha`, `beta`, `rc`. Stable builds omit the suffix.

The root `VERSION` file defines `VERSION_NAME` (Android `versionName`) and `VERSION_CODE` (Android `versionCode`). The Android `versionCode` **must strictly increase for every published upgrade**, including pre-releases and service packs; do not derive it from the dotted version. Source versionCode was 184, so BaroLauncher begins at 185.

`android9-latest` is a moving download tag, not an immutable product version. For reproducible releases, publish separate immutable tags such as `v1.0.0.0-beta.1` or `v1.0.0.0` when a build is validated. Never call a build GA solely because CI compiles.

Compatibility: Android 9 / API 28 minimum. Preserve the existing application package and signing key for in-place upgrades; a different signing certificate will prevent Android from updating the installed app. Do not remove or disable the stock projector launcher during testing.
