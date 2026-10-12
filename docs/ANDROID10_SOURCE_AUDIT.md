# Android 10 framework, native audio, and Telecom audit

Audited `android10/{base,av,Telecomm}`, all at `android-10.0.0_r47` (API 29). Telecom coverage includes InCallService classification/binding, companion-list handling, and the test companion-registration API. Also inspected the [PackageInstaller role sources](#dialer-role-definition-and-behavior) from the same tag. The remaining generic PermissionController implementation, vendor HAL behavior, and a physical device were not inspected. Implementation steps are in [ANDROID_COMPATIBILITY_PLAN.md](ANDROID_COMPATIBILITY_PLAN.md).

## Result

Android 10 retains the missing shell capture grants identified in the [combined Android 6–9 audit](ANDROID6_9_SOURCE_AUDIT.md). Shell UID 2000 still lacks `RECORD_AUDIO` and `CAPTURE_AUDIO_OUTPUT`. Default-dialer selection and correct attribution do not supply those grants to the helper.

The matching native sources confirm the capture requirements and a different uplink permission/fallback path from Android 6. Stock AOSP Android 10 with non-root Shizuku cannot open the gateway's cellular downlink: it fails native permission checks before device selection. API 29 therefore cannot be selected as the stock unrooted gateway minimum. Vendor grants or root/system integration are alternative deployments; no physical two-way audio result or HAL support claim was obtained.

If effective `CAPTURE_AUDIO_OUTPUT` access were already supplied in a future Xposed deployment, handling `RECORD_AUDIO` and its app-op at creation/start would be a small extension. API 29 capture and playback could then be evaluated in the same Shizuku helper, preserving its socket transport and modem-driven clock. App-side capture, split PCM directions, and a root audio helper would not be required for those permission checks. Construction, hidden-API access, Telecom binding, profiles, and physical audio would still need verification. This is [deferred research](ANDROID_COMPATIBILITY_PLAN.md#deferred-xposed-scenario); the current port assumes no hooks and targets API 30 provisionally.

## Confirmed native capture checks

The Android 10 path is `AudioRecord` -> native record creation -> AudioFlinger `createRecord()` -> AudioPolicyService `getInputForAttr()`.

1. [AudioFlinger normalizes the client UID/PID](https://android.googlesource.com/platform/frameworks/av/+/android-10.0.0_r47/services/audioflinger/AudioFlinger.cpp#1841) to the Binder caller for callers other than media/audio servers, then [requests the policy input](https://android.googlesource.com/platform/frameworks/av/+/android-10.0.0_r47/services/audioflinger/AudioFlinger.cpp#1901). Direct calls to [policy likewise normalize identity](https://android.googlesource.com/platform/frameworks/av/+/android-10.0.0_r47/services/audiopolicy/service/AudioPolicyInterfaceImpl.cpp#364). Supplying a different operation package or client UID does not change the shell caller's permission state.
2. [Policy checks `recordingAllowed()`](https://android.googlesource.com/platform/frameworks/av/+/android-10.0.0_r47/services/audiopolicy/service/AudioPolicyInterfaceImpl.cpp#381), returning `PERMISSION_DENIED` when it fails. [`checkRecordingInternal()`](https://android.googlesource.com/platform/frameworks/av/+/android-10.0.0_r47/media/utils/ServiceUtilities.cpp#62) requires `RECORD_AUDIO` and an allowed record app-op for ordinary callers, including shell.
3. [Policy explicitly checks `CAPTURE_AUDIO_OUTPUT` by source](https://android.googlesource.com/platform/frameworks/av/+/android-10.0.0_r47/services/audiopolicy/service/AudioPolicyInterfaceImpl.cpp#387) for `VOICE_UPLINK`, `VOICE_DOWNLINK`, `VOICE_CALL`, and `ECHO_REFERENCE`. Failure returns `PERMISSION_DENIED` before the policy manager selects an input. This is stronger than merely checking the selected device's classification.
4. [The selected-input classification is also checked](https://android.googlesource.com/platform/frameworks/av/+/android-10.0.0_r47/services/audiopolicy/service/AudioPolicyInterfaceImpl.cpp#425): `API_INPUT_TELEPHONY_RX` and ordinary mix capture require output-capture permission. Selecting telephony RX with a different source does not avoid that requirement. External policy reroute capture instead checks `MODIFY_AUDIO_ROUTING`.
5. [`startInput()` rechecks recording](https://android.googlesource.com/platform/frameworks/av/+/android-10.0.0_r47/services/audiopolicy/service/AudioPolicyInterfaceImpl.cpp#497) through `startRecording()`, including starting the record app-op, and can return `PERMISSION_DENIED`.

[`captureAudioOutputAllowed()`](https://android.googlesource.com/platform/frameworks/av/+/android-10.0.0_r47/media/utils/ServiceUtilities.cpp#127) performs the privileged permission check. Both capture and recording helpers explicitly exempt audio-server UID and root UID; [the UID predicate](https://android.googlesource.com/platform/frameworks/av/+/android-10.0.0_r47/media/utils/include/mediautils/ServiceUtilities.h#52) does not include shell UID 2000. Native root handling therefore differs from shell handling on this release.

On the ordinary Java construction path, native permission denial need not appear as a distinct Java `SecurityException`. [JNI initialization failure](https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r47/core/jni/android_media_AudioRecord.cpp#361) becomes setup error `-20`; the Java constructor remains uninitialized, and the builder throws `UnsupportedOperationException("Cannot create AudioRecord")`. Permission preflight/native logs are necessary to distinguish this from other initialization failures.

In a future hooked deployment, diagnostics would need to distinguish declared grants from effective check results. A package permission query alone would be insufficient when a scoped hook permits the real helper audio checks; bounded construction/start probes would be needed. No hook-specific diagnostic mode is planned for the current port.

## Confirmed native uplink permission and fallback

For an explicitly requested telephony TX device, linear PCM during a call, and either music stream or voice-communication usage, [the policy manager assigns `AUDIO_OUTPUT_FLAG_INCALL_MUSIC`](https://android.googlesource.com/platform/frameworks/av/+/android-10.0.0_r47/services/audiopolicy/managerdefault/AudioPolicyManager.cpp#1007). This includes the gateway's proposed voice-communication track recipe.

[AudioPolicyService checks `MODIFY_PHONE_STATE`](https://android.googlesource.com/platform/frameworks/av/+/android-10.0.0_r47/services/audiopolicy/service/AudioPolicyInterfaceImpl.cpp#206) after a successful flagged output selection, using [`modifyPhoneStateAllowed()`](https://android.googlesource.com/platform/frameworks/av/+/android-10.0.0_r47/media/utils/ServiceUtilities.cpp#203). If denied, it releases the selected output, restores the original flags, clears the explicit device selection, and retries on the engine's default output. This can produce successful local playback rather than a permission exception. The check is specifically for the `INCALL_MUSIC` path; it should not be generalized to every possible attribute/device combination.

Shell requests and is allowlisted for `MODIFY_PHONE_STATE` on this baseline, so this permission is not its uplink blocker. The dialer role definition below does not grant it to an ordinary dialer app. There is no `CALL_AUDIO_INTERCEPTION` check in this path on Android 10. Neither conclusion establishes compatible profiles, HAL success, or remote receipt of PCM.

[`AudioTrack::setOutputDevice()`](https://android.googlesource.com/platform/frameworks/av/+/android-10.0.0_r47/media/libaudioclient/AudioTrack.cpp#1300) stores the selected ID, invalidates/interrupts an initialized track when it changes, and returns `NO_ERROR`. The setter is not the native permission enforcement point and does not synchronously confirm a modem route. Preserve the requested/effective device distinction and physical audio verification.

## Permission configuration

The [shell manifest](https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r47/packages/Shell/AndroidManifest.xml#25) uses `android.uid.shell` with the following requests:

| Permission | Requested by shell |
| --- | --- |
| `MODIFY_PHONE_STATE` | Yes |
| `RECORD_AUDIO` | No |
| `CAPTURE_AUDIO_OUTPUT` | No |
| `MODIFY_AUDIO_ROUTING` | No |
| `MODIFY_AUDIO_SETTINGS` | No |
| `MANAGE_AUDIO_POLICY` | Yes |

[`platform.xml`](https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r47/data/etc/platform.xml#144) assigns audio-setting permissions to media, audioserver, and cameraserver; its shell UID assignment is `INTERNET`. The [shell privileged-permission allowlist](https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r47/data/etc/privapp-permissions-platform.xml#246) supplies no recording/capture grant. A search of supplied manifests found no other package sharing `android.uid.shell`. [SystemConfig](https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r47/core/java/com/android/server/SystemConfig.java#541) parses explicit UID assignments; the target device's vendor/system configuration can differ.

[`ActivityManager.checkComponentPermission()`](https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r47/core/java/android/app/ActivityManager.java#3731) exempts root and system, not shell. [PackageManager's UID check](https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r47/services/core/java/com/android/server/pm/PackageManagerService.java#5630) reaches [PermissionManager's package/shared-UID or explicit system grant checks](https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r47/services/core/java/com/android/server/pm/permission/PermissionManagerService.java#329). [System-component default grants](https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r47/services/core/java/com/android/server/pm/permission/DefaultPermissionGrantPolicy.java#302) iterate requested runtime permissions; they do not add an undeclared microphone permission to shell.

[`RECORD_AUDIO`](https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r47/core/res/AndroidManifest.xml#1187) is dangerous/instant; [`CAPTURE_AUDIO_OUTPUT`](https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r47/core/res/AndroidManifest.xml#3623) is signature/privileged. The [runtime grant path](https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r47/services/core/java/com/android/server/pm/permission/PermissionManagerService.java#2115) applies [declaration/type checks](https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r47/services/core/java/com/android/server/pm/permission/BasePermission.java#420). `pm grant` cannot grant shell an undeclared microphone permission or turn privileged output capture into a runtime grant. An app-op change does not satisfy a missing Android permission.

[`MANAGE_AUDIO_POLICY`](https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r47/core/res/AndroidManifest.xml#4475) is a separate signature permission, not a grant of the above audio permissions. In particular, [dynamic policy registration](https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r47/services/core/java/com/android/server/audio/AudioService.java#6790) checks `MODIFY_AUDIO_ROUTING` for routing policies, with an audio MediaProjection exception for render/loopback capture. Privileged playback capture separately requires `CAPTURE_MEDIA_OUTPUT` or `CAPTURE_AUDIO_OUTPUT`. Shell's policy-management request does not establish a call-capture route.

## Playback capture limits

[`VOICE_DOWNLINK` documentation](https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r47/media/java/android/media/MediaRecorder.java#251) still explicitly requires `CAPTURE_AUDIO_OUTPUT`, reserved for system components. The same requirement is documented for uplink and full-call recording.

The new [`AudioPlaybackCaptureConfiguration`](https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r47/media/java/android/media/AudioPlaybackCaptureConfiguration.java#31) captures eligible app playback with `USAGE_UNKNOWN`, `USAGE_MEDIA`, or `USAGE_GAME`, subject to capture-policy restrictions. Other usages cannot be captured through this public mechanism. [`AudioRecord.Builder.setAudioPlaybackCaptureConfig()`](https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r47/media/java/android/media/AudioRecord.java#623) is therefore not a replacement for the gateway's cellular downlink source. MediaProjection user approval does not grant the helper privileged call-capture permissions.

## Construction and attribution

Both audio builders and [`AudioTrack.Builder.setTransferMode()`](https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r47/media/java/android/media/AudioTrack.java#878) exist. Neither builder provides `setContext()`. Keep the release-specific construction/reflection adapter; absence of that method alone does not establish a minimum SDK.

The [record constructor](https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r47/media/java/android/media/AudioRecord.java#404) passes `getCurrentOpPackageName()` to native setup. [That method](https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r47/media/java/android/media/AudioRecord.java#419) uses `ActivityThread.currentOpPackageName()`, with a `uid:<callingUid>` fallback for command-line utilities. [AppOps package normalization](https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r47/services/core/java/com/android/server/appop/AppOpsService.java#4455) maps shell UID 2000 to `com.android.shell` and root to `root`; [record app-op handling](https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r47/services/core/java/com/android/server/appop/AppOpsService.java#2030) uses this normalization.

[JNI construction](https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r47/core/jni/android_media_AudioRecord.cpp#268) creates a native record with the supplied operation package and [initializes it using default caller UID/PID](https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r47/core/jni/android_media_AudioRecord.cpp#290). Attaching an existing native record is also supported. These are construction mechanisms; no capture-permission bypass is established. Validate any narrow reflection/JNI adapter in the actual helper process.

[`ApplicationInfo` hidden-API policy](https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r47/core/java/android/content/pm/ApplicationInfo.java#1869) depends on platform signing or qualifying system-package state. Verify the helper's actual loader and hidden-API behavior; changing an operation package does not itself reproduce shell package policy or supply audio permissions.

## Device routing and call access

The public routing surface remains available: [`AudioManager.getDevices()`](https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r47/media/java/android/media/AudioManager.java#4922), [`TYPE_TELEPHONY`](https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r47/media/java/android/media/AudioDeviceInfo.java#109) with TX/RX mappings, [`AudioTrack.setPreferredDevice()`](https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r47/media/java/android/media/AudioTrack.java#3039), and [`AudioRecord.setPreferredDevice()`](https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r47/media/java/android/media/AudioRecord.java#1676). Java selection is not proof of native permission acceptance, a compatible output profile, HAL routing, or remote PCM delivery.

API 29 introduces [`RoleManager.ROLE_DIALER`](https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r47/core/java/android/app/role/RoleManager.java#93) and [`createRequestRoleIntent()`](https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r47/core/java/android/app/role/RoleManager.java#201). [DefaultDialerManager](https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r47/telecomm/java/android/telecom/DefaultDialerManager.java#87) delegates selection to the role system. Preserve the plan's API 29+ role-request branch. The PackageInstaller role files are examined below; generic qualification/grant implementation still needs the remaining PermissionController sources. Telecom's inspected classifier admits a UI-declared default-dialer service protected by `BIND_INCALL_SERVICE`; actual selection, binding, and callbacks remain device checks.

The [default system-dialer grant routine](https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r47/services/core/java/com/android/server/pm/permission/DefaultPermissionGrantPolicy.java#767) covers requested phone, contacts, SMS, microphone, and camera runtime permissions. It includes no privileged output capture. Permissions granted to a dialer UID do not transfer to a separate shell helper.

[`CALL_COMPANION_APP`](https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r47/core/res/AndroidManifest.xml#1147) now exists as a normal permission. The app's current `MANAGE_ONGOING_CALLS` permission/app-op is absent from this framework and is not required by the inspected companion classifier. Companion classification and binding exist, but registering list membership through the supplied test API is restricted to `userdebug` builds. The existing Shizuku setup therefore cannot be assumed to work on a production `user` build. See [optional Xposed call access](#optional-xposed-call-access); default-dialer integration remains a separate path.

The dedicated call-interception helpers, `CALL_AUDIO_INTERCEPTION`, `MODE_CALL_REDIRECT`, and `USAGE_CALL_ASSISTANT` are absent from this audited framework. Keep newer backends and handset suppression optional and isolate their classes as planned.

## Optional Xposed call access

The [Android 11 non-UI hook approach](ANDROID11_SOURCE_AUDIT.md#optional-xposed-call-access) is also feasible at source level on Android 10, with a release-specific implementation. Its scope, component/user matching, readiness behavior, and validation requirements apply here too. Keep this as deferred research; no hook was implemented or device-tested.

Android 10 differs from the audited Android 11 classifier:

- [`getInCallServiceType()`](https://android.googlesource.com/platform/packages/services/Telecomm/+/android-10.0.0_r47/src/com/android/server/telecom/InCallController.java#1270) already returns `IN_CALL_SERVICE_TYPE_COMPANION` for a service with `CALL_COMPANION_APP`, without `CONTROL_INCALL_EXPERIENCE`, and with neither UI nor car-UI metadata. It first requires `BIND_INCALL_SERVICE` protection.
- [`connectToNonUiInCallServices()`](https://android.googlesource.com/platform/packages/services/Telecomm/+/android-10.0.0_r47/src/com/android/server/telecom/InCallController.java#1148) binds non-UI services alongside the selected dialer, then adds eligible companion services from the companion package list.
- [`getCallCompanionApps()`](https://android.googlesource.com/platform/packages/services/Telecomm/+/android-10.0.0_r47/src/com/android/server/telecom/RoleManagerAdapterImpl.java#99) returns only the in-memory test override list. The [shell command](https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r47/cmds/telecom/src/com/android/commands/telecom/Telecom.java#264) calls [`addOrRemoveTestCallCompanionApp()`](https://android.googlesource.com/platform/packages/services/Telecomm/+/android-10.0.0_r47/src/com/android/server/telecom/TelecomServiceImpl.java#1576), which requires `MODIFY_PHONE_STATE` and `Build.IS_USERDEBUG`. On a production `user` build it throws `SecurityException("Test-only API.")`, even for shell/root callers with that permission.

| Candidate approach | Android 10 changes |
| --- | --- |
| Admit the exact gateway as non-UI (preferred shared Android 10/11 design) | Hook classification to return `IN_CALL_SERVICE_TYPE_NON_UI` (`4`) for the intended component/user, preserving bind protection and non-UI/non-car-UI declarations. This avoids companion-list registration and the test API's build restriction. Merely overriding `CONTROL_INCALL_EXPERIENCE` is insufficient when `CALL_COMPANION_APP` remains granted: the stock non-UI branch also requires the service not to be classified as a third-party companion. |
| Retain actual companion classification | Keep the existing classifier and supply gateway list membership through a scoped Telecom hook, or narrowly adapt the registration path. Unlike Android 11, no missing companion classifier branch needs restoring. Preserve other list entries and avoid a global build-type override. |

After binding, Telecom supplies the normal [InCallAdapter](https://android.googlesource.com/platform/packages/services/Telecomm/+/android-10.0.0_r47/src/com/android/server/telecom/InCallController.java#1365), supporting reuse of existing call controls while the usual dialer remains selected. Neither approach needs the absent `MANAGE_ONGOING_CALLS` app-op. Ordinary call-placement permissions and gateway call-ownership checks still apply. Telecom qualification remains separate from the missing stock shell capture grants and HAL/modem support. Use the Android 11 discussion for the common hook-loss, restart/reboot, call-control, and cleanup checks.

## Dialer role definition and behavior

PackageInstaller source references at `android-10.0.0_r47`:

- [https://android.googlesource.com/platform/packages/apps/PackageInstaller/+/android-10.0.0_r47/res/xml/roles.xml](https://android.googlesource.com/platform/packages/apps/PackageInstaller/+/android-10.0.0_r47/res/xml/roles.xml)
- [https://android.googlesource.com/platform/packages/apps/PackageInstaller/+/android-10.0.0_r47/src/com/android/packageinstaller/role/model/DialerRoleBehavior.java](https://android.googlesource.com/platform/packages/apps/PackageInstaller/+/android-10.0.0_r47/src/com/android/packageinstaller/role/model/DialerRoleBehavior.java)

`roles.xml:163` declares `android.app.role.DIALER` as exclusive, with `DialerRoleBehavior`. Its required-components block lists:

- An activity handling `android.intent.action.DIAL` with no data scheme.
- An activity handling the same action with the `tel` scheme.

These can be fulfilled by one activity with separate intent filters. The block lists no `InCallService` or `IN_CALL_SERVICE_UI` requirement. This establishes what the inspected XML declares, not every possible check in the remaining generic role implementation or Telecom binding. Keep a correctly declared service for actual gateway call control, and keep the planned gateway/recovery screen; a normal dial pad is not required by these declarations.

The dialer role's permissions block includes the phone, contacts, SMS, microphone, and camera sets. The microphone set at `roles.xml:59` contains `RECORD_AUDIO`. None of these sets contains `CAPTURE_AUDIO_OUTPUT`, `MODIFY_AUDIO_ROUTING`, `MODIFY_PHONE_STATE`, or `CALL_AUDIO_INTERCEPTION`. These are grants configured for the role-holder app, not its separate shell helper; dialer selection alone leaves the stock shell capture permissions unresolved. A separate effective audio hook could address access in a future deployment. Actual grant handling and requested-permission filtering need the generic role implementation.

The role also configures `android:run_any_in_background` as allowed, and `android:run_in_background` as allowed only through target SDK 25. It configures preferred activities for both dial-intent forms. These app-op entries do not establish exemptions from all foreground-service or power-management rules. No `SYSTEM_CALL_STREAMING` role is declared in the inspected XML.

`DialerRoleBehavior.java` adds:

- Role availability on voice-capable devices (`isAvailableAsUser()`, line 43).
- A settings summary marking the system dialer (line 50).
- An encryption-unaware confirmation message via a mixin (line 64).
- A fallback holder through `config_defaultDialer` (line 72).
- Role visibility through `config_showDialerRole` (line 77).

It does not override application qualification or add audio permissions. Mixin and generic role behavior implementation were not inspected. Its declared package is `com.android.packageinstaller.role.model`, matching the PackageInstaller source path linked above.

## Next step

The subsequent [Android 11 audit](ANDROID11_SOURCE_AUDIT.md) supplies shell recording/capture grants and covers matching native audio and Telecom service code. It establishes API 30 as the provisional stock unrooted implementation candidate. Start with a small API 30 helper/default-dialer diagnostic and physical two-way audio verification. API 23–29 backports and Xposed implementation are deferred. If Android 10 is revisited, verify its requested/effective uplink route because permission denial can retry local playback. See the [compatibility plan](ANDROID_COMPATIBILITY_PLAN.md).
