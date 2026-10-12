# Android compatibility documentation
> [!NOTE]
> Files in this folder are AI slops and should be regarded as scratchpads of LLM.

Start with the [compatibility plan](ANDROID_COMPATIBILITY_PLAN.md) for implementation scope, optional Advanced settings, and validation steps. The source audits contain the supporting evidence and release-specific limits.

Modern call redirection prefers usable framework audio helpers, including `getCallUplinkInjectionAudioTrack()`. Equivalent manual construction remains an attribution/compatibility workaround within that backend. Explicit direct-telephony selection skips the helpers, and explicit redirection does not silently switch to direct routing. See the [audio implementation plan](ANDROID_COMPATIBILITY_PLAN.md#3-implement-the-older-cellular-audio-backend).

The [dialer scope](ANDROID_COMPATIBILITY_PLAN.md#dialer-scope-and-phased-fallback) starts with gateway/recovery UI and defers a minimal manual-call fallback until gateway audio is verified. It records shared lifecycle work, call ownership, included fallback controls, and features left out.

The [companion and bridge refactor](ANDROID_COMPATIBILITY_PLAN.md#companion-and-bridge-refactor) keeps one APK, existing modules/interfaces, and the current Telecom-in-app/Shizuku-audio arrangement. Immediate work focuses on call ownership, cleanup, and needed compatibility implementations. A [direct privileged implementation](ANDROID_COMPATIBILITY_PLAN.md#optional-direct-privileged-operation) for LineageOS/Magisk can later reuse those interfaces locally in the app process. A new Telecom IPC protocol, contract library, dedicated process, or independent companion APK is deferred until a concrete deployment needs it.

The installed Shizuku server controls UserService bootstrap behavior; downgrading the gateway's client API dependency does not restore older startup behavior. The plan retains UserService and adapts effective attribution where needed. See the [bootstrap audit](ANDROID11_SOURCE_AUDIT.md#supplied-shizuku-userservice-bootstrap).

The [potential architectural benefits](ANDROID_COMPATIBILITY_PLAN.md#potential-architectural-benefits) distinguish maintainability, testability, and call ownership in the current design from conditional system-deployment, process-isolation, and independent-package benefits.

| Document | Contents |
| --- | --- |
| [ANDROID_COMPATIBILITY_PLAN.md](ANDROID_COMPATIBILITY_PLAN.md) | Provisional API 30 stock unrooted port, optional dialer/audio integrations, limited refactor through existing interfaces, and deferred privileged/older/Xposed deployment notes and validation. |
| [ANDROID6_9_SOURCE_AUDIT.md](ANDROID6_9_SOURCE_AUDIT.md) | Android 6 native/Telecom baseline, shared Android 6–9 framework findings, and differences in Android 7, 8, and 9. |
| [ANDROID10_SOURCE_AUDIT.md](ANDROID10_SOURCE_AUDIT.md) | Native capture enforcement, incall-music permission/fallback behavior, playback-capture limits, Telecom companion binding/registration restrictions, and optional hook research. |
| [ANDROID11_SOURCE_AUDIT.md](ANDROID11_SOURCE_AUDIT.md) | Landed audio-permission patches and read/write requirements, shell grants, direct routing, Telecom binding, PermissionController dialer qualification/grants, optional Xposed hooks, and HAL/error behavior. |

The current plan starts with production API 30 audio construction and capability reporting in the existing privileged implementation, followed by default-dialer and runtime/build compatibility. A separate demo phase is not required; physical two-way audio is verified through the resulting app before claiming support. Playback's package check is separate from recording AppOps normalization. The supplied `UserService.java` installs the gateway package's application, so the plan includes a helper-local operation-package adapter for that bootstrap path; see the [Shizuku findings](ANDROID11_SOURCE_AUDIT.md#supplied-shizuku-userservice-bootstrap). Both PCM directions stay in the existing Shizuku helper. Xposed implementation and API 23–29 backports are deferred. The [conditional hook comparison](ANDROID_COMPATIBILITY_PLAN.md#deferred-xposed-scenario) remains a research note: if output-capture checks were already handled, recording permission/app-op handling would be a small extension. It is not an assumed dependency of this port. The implementation still declares `minSdk 34`; these documents describe a port, not completed support.

Deferral keeps the technical material available: [optional Telecom hook design](ANDROID_COMPATIBILITY_PLAN.md#optional-xposed-call-access-deferred), [older construction/runtime backports](ANDROID_COMPATIBILITY_PLAN.md#older-android-construction-and-runtime-compatibility-deferred), and [future deployment validation](ANDROID_COMPATIBILITY_PLAN.md#validation-of-deferred-deployments). These notes do not add work to the current implementation scope.

## Audited sources

Local checkouts are organized by Android version. Repository names are `base` (`frameworks/base`), `av` (`frameworks/av`), and `Telecomm` (`packages/services/Telecomm`).

| Release | API | Tag | Repository coverage |
| --- | --- | --- | --- |
| Android 6.0.1 | 23 | `android-6.0.1_r81` | `base`, `av`, `Telecomm` |
| Android 7.1.2 | 25 | `android-7.1.2_r39` | `base` only |
| Android 8.1 | 27 | `android-8.1.0_r81` | `base` only |
| Android 9 | 28 | `android-9.0.0_r61` | `base` only |
| Android 10 | 29 | `android-10.0.0_r47` | `base`, `av`, `Telecomm` |
| Android 11 | 30 | `android-11.0.0_r48` | `base`, `av`, `Telecomm`, `PermissionController` |

Android 7.0/API 24, Android 8.0/API 26, initial Android 11 grants, and vendor HAL implementations were not separately audited. Android 7–9 native routing and Telecom binding remain unverified. Generic PermissionController role qualification and dialer grants are audited for the matching Android 11 tag; device role selection and callbacks still need testing. No physical two-way audio test was performed on these historical candidates.

The shared Android 6–9 permission result describes stock grants for this gateway, not identical audio implementations. Android 10 is kept separate for its native permission/fallback behavior; Android 11 is kept separate because it supplies the first audited shell capture grants and a candidate stock implementation path. API 30 is the provisional stock unrooted minimum, subject to helper and physical-device verification. API 23 remains a possible boundary for a separate future deployment with sufficient effective audio access.
