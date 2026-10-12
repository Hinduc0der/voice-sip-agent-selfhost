# Android 6–9 gateway source audit

Android 6 is the detailed native-audio and Telecom baseline. The Android 7–9 framework audits confirm the same relevant permission configuration and public routing APIs; their differences are collected below. Implementation steps are in [ANDROID_COMPATIBILITY_PLAN.md](ANDROID_COMPATIBILITY_PLAN.md).

| Release | API | Audited tag | Supplied repositories |
| --- | --- | --- | --- |
| Android 6.0.1 | 23 | `android-6.0.1_r81` | `android6/{base,av,Telecomm}` |
| Android 7.1.2 | 25 | `android-7.1.2_r39` | `android7/base` |
| Android 8.1 | 27 | `android-8.1.0_r81` | `android8/base` |
| Android 9 | 28 | `android-9.0.0_r61` | `android9/base` |

Android 7.0/API 24 and Android 8.0/API 26 were not separately audited. Matching native audio and Telecom service sources were not supplied for Android 7–9. Their exact native permission/routing behavior must not be inferred from Android 6. No physical device or vendor HAL was tested. The existing unrelated Android 8 checkout modification was preserved; audited files match its release tag.

## Feasibility decision

Across the four audited stock frameworks, shell UID 2000 requests `MODIFY_PHONE_STATE` but lacks `RECORD_AUDIO`, `CAPTURE_AUDIO_OUTPUT`, `MODIFY_AUDIO_ROUTING`, and `MODIFY_AUDIO_SETTINGS`. Supplied system UID grants do not supply the missing capture permissions. Default-dialer selection, correct attribution, `pm grant`, and record app-op changes do not resolve that configuration. Android 6 native checks confirm that it blocks the full stock unrooted gateway; the Android 7–9 frameworks likewise do not meet the documented call-capture requirements without additional access.

In a separate future deployment where effective `CAPTURE_AUDIO_OUTPUT` access is already supplied by Xposed, handling the helper's `RECORD_AUDIO` and record app-op checks would be a small extension. That would make API 23 worth evaluating without moving either PCM direction out of the Shizuku helper or adding a root audio helper. This is a conditional feasibility result; Xposed implementation and API 23–29 backports are deferred. See the [deferred workload comparison](ANDROID_COMPATIBILITY_PLAN.md#deferred-xposed-scenario).

Public device enumeration/preference and audio builders are available throughout these releases. They do not establish effective permission acceptance or modem audio delivery. Keep installability, effective audio access, and verified two-way audio as separate claims. Runtime backports, release-specific construction, native loading, Telecom access, and physical two-way audio remain to be verified. Audio permission hooks do not qualify the app for Telecom binding or create missing HAL/modem routes. The [Android 11 audit](ANDROID11_SOURCE_AUDIT.md) identifies API 30 as the first audited stock unrooted candidate, subject to helper and device verification; root/system integration and vendor grants remain alternative deployments.

## Why Android 5 is excluded

A focused comparison of `android5/{base,av,Telecomm}`, all at `android-5.1.1_r38` (API 22), supports excluding Android 5 from this port:

- The planned public `AudioManager.getDevices()`, `AudioDeviceInfo`, `AudioTrack.setPreferredDevice()`, and `ACTION_CHANGE_DEFAULT_DIALER` APIs are absent; they arrive in API 23. Android 5 does contain a hidden `AudioDevice.TYPE_TELEPHONY`, so this is a public API/backend boundary, not proof that older audio routing is impossible. API 23 also matches the Shizuku baseline [after 2020](https://github.com/RikkaApps/Shizuku/commit/d26f6204cf97672c1eff52dffe2276f4fc2dd992) cited in the [external note's reference](https://github.com/RikkaApps/Shizuku-API).
- [InCallService binding](https://android.googlesource.com/platform/packages/services/Telecomm/+/android-5.1.1_r38/src/com/android/server/telecom/InCallController.java#284) requires privileged `CONTROL_INCALL_EXPERIENCE`, with no Android 6 default-dialer exception. [Shell](https://android.googlesource.com/platform/frameworks/base/+/android-5.1.1_r38/packages/Shell/AndroidManifest.xml#25) lacks recording grants, and [AudioFlinger enforces `RECORD_AUDIO`](https://android.googlesource.com/platform/frameworks/av/+/android-5.1.1_r38/services/audioflinger/ServiceUtilities.cpp#28). Legacy routing plus root/system integration or framework hooks would therefore be a separate port; Xposed is one possible approach, not the only one.

API 23 is a possible compatibility boundary for a separate future deployment with effective audio-permission hooks. It is insufficient for the stock unrooted gateway because Android 6 lacks shell capture grants; introducing runtime permissions does not supply privileged call-audio access. The current plan uses API 30 as the first audited stock unrooted candidate, pending device verification. VoLTE availability is a separate device/network issue, and its support alone does not establish PCM injection/capture support.

## Shared framework findings

- `RECORD_AUDIO` is a dangerous runtime permission; `CAPTURE_AUDIO_OUTPUT` is signature/privileged. Shell has no blanket UID exemption. Default system-component grants iterate requested permissions, so they do not supply shell's undeclared recording permission.
- `VOICE_DOWNLINK` is documented to require output-capture permission. Default-dialer grants cover requested ordinary runtime permissions, including microphone access, and do not grant privileged capture or transfer dialer permissions to a separate shell process.
- `AudioManager.getDevices()`, telephony TX/RX mappings, record/track `setPreferredDevice()`, both builders, and `AudioTrack.Builder.setTransferMode()` exist. Neither builder has `setContext()`; this is an adaptable construction difference, not an independent minimum-SDK requirement.
- Record construction passes `ActivityThread.currentOpPackageName()` to native setup. AppOps normalizes shell operations to `com.android.shell` and root operations to `root`. Correct attribution enables existing grants; it does not acquire new permissions.
- The current interception helpers, `CALL_AUDIO_INTERCEPTION`, `MODE_CALL_REDIRECT`, and companion permission pair are absent. Dialer candidates handle `ACTION_DIAL` without a scheme and with `tel:`. Exact InCallService binding is established below for Android 6 only.

The remaining native-audio and Telecom details in the next sections describe Android 6. The [Android 7–9 differences](#android-79-framework-differences) establish only the framework findings for those releases.

## Alternative recording options

Dedicated recorder apps may still work through device-specific or privileged paths:

- Manufacturer-specific audio behavior or recording APIs that expose call audio.
- A preinstalled system recorder with the required privileged permissions.
- A root helper with sufficient access to the device's call-audio path.
- With root, a potential Xposed module to adapt framework permission/routing behavior or expose captured PCM; this needs a device-specific feasibility check and cannot create missing HAL/firmware capabilities.
- Microphone recording with speakerphone may capture the remote party adequately in a quiet environment, where concurrent recording is permitted. Quality and echo need testing. This is an acoustic capture fallback; a SIP gateway still needs a working uplink path.

These alternatives have not been validated by this audit. Being a dedicated recorder or the default dialer alone does not grant privileged capture access.

## Android 6 native capture permissions and caller identity

The downlink path is:

1. `AudioRecord` configured with `VOICE_DOWNLINK`.
2. Default policy engine selects `AUDIO_DEVICE_IN_VOICE_CALL`, the voice-call/telephony RX device. Framework `AudioSystem` aliases `DEVICE_IN_TELEPHONY_RX` to `DEVICE_IN_VOICE_CALL`. If that input is unavailable, policy cannot find a device for this source.
3. Policy manager classifies the input as `API_INPUT_TELEPHONY_RX`; policy service checks `CAPTURE_AUDIO_OUTPUT` and returns native `PERMISSION_DENIED` when it is absent.
4. AudioFlinger `openRecord()` separately requires `RECORD_AUDIO`. `recordingAllowed()` also checks the record app-op for non-root callers.

Sources: [input selection](https://android.googlesource.com/platform/frameworks/av/+/android-6.0.1_r81/services/audiopolicy/enginedefault/src/Engine.cpp#685), [input classification](https://android.googlesource.com/platform/frameworks/av/+/android-6.0.1_r81/services/audiopolicy/managerdefault/AudioPolicyManager.cpp#1374), [capture enforcement](https://android.googlesource.com/platform/frameworks/av/+/android-6.0.1_r81/services/audiopolicy/service/AudioPolicyInterfaceImpl.cpp#310), [record enforcement](https://android.googlesource.com/platform/frameworks/av/+/android-6.0.1_r81/services/audioflinger/AudioFlinger.cpp#1455), [permission/app-op checks](https://android.googlesource.com/platform/frameworks/av/+/android-6.0.1_r81/services/audioflinger/ServiceUtilities.cpp#37).

The [shell manifest](https://android.googlesource.com/platform/frameworks/base/+/android-6.0.1_r81/packages/Shell/AndroidManifest.xml#25) requests `MODIFY_PHONE_STATE` but omits `RECORD_AUDIO`, `CAPTURE_AUDIO_OUTPUT`, `MODIFY_AUDIO_ROUTING`, and `MODIFY_AUDIO_SETTINGS`. [System UID assignments](https://android.googlesource.com/platform/frameworks/base/+/android-6.0.1_r81/data/etc/platform.xml#116) supply none of these to shell. UID 2000 receives no blanket permission exemption: [ActivityManager's special case](https://android.googlesource.com/platform/frameworks/base/+/android-6.0.1_r81/core/java/android/app/ActivityManager.java#2616) is for root and system, while other callers reach [UID permission checks](https://android.googlesource.com/platform/frameworks/base/+/android-6.0.1_r81/services/core/java/com/android/server/pm/PackageManagerService.java#3191).

| Caller | Downlink capture with stock checks on this baseline |
| --- | --- |
| Ordinary third-party app | Can request `RECORD_AUDIO`; cannot obtain privileged `CAPTURE_AUDIO_OUTPUT` through normal user approval. |
| User-selected default dialer | May receive requested microphone runtime permission, but no privileged output-capture grant. Its permission grants do not transfer to a shell helper. |
| Non-root Shizuku helper, UID 2000 | Neither required capture permission is supplied by this AOSP shell configuration. |
| Root helper or appropriately granted system integration | Potentially passes permission checks; still needs construction, device/profile, HAL, and end-to-end verification. |

`CAPTURE_AUDIO_OUTPUT` is [signature/privileged](https://android.googlesource.com/platform/frameworks/base/+/android-6.0.1_r81/core/res/AndroidManifest.xml#2195). The [runtime grant implementation](https://android.googlesource.com/platform/frameworks/base/+/android-6.0.1_r81/services/core/java/com/android/server/pm/PackageManagerService.java#3460) requires the target package to request the permission and the permission to be runtime/development. Consequently `pm grant com.android.shell ...` cannot fix this baseline: shell does not request `RECORD_AUDIO`, and output capture is not a runtime/development permission. Changing the record app-op does not satisfy the permission checks.

A future hooked deployment could change effective check results without changing declared package grants. Its diagnostics would need to distinguish those results and use bounded helper construction/start checks rather than rejecting access solely on a package permission query. The recording permission and app-op would still need to pass where this release enforces them. This hook-specific integration is outside the current port.

### Attribution differs from modern Android

The current `setContext(ShellEnv.shellContext(...))` workaround corrects caller attribution. The Shizuku process runs as UID 2000 while loading the app's code; its default package attribution can identify the app rather than `com.android.shell`. Supplying a shell context makes the package/attribution match the actual shell UID so audio services can accept the caller and use permissions shell already holds. It does not grant new permissions or change the process UID.

This mismatch depends on process initialization; it is not inevitable in every Shizuku UserService. In the modern checkout, `AttributionSource.myAttributionSource()` first uses the current Application's attribution and otherwise attempts to resolve a package from the actual process UID. Inspect the effective attribution in the target helper process instead of assuming its package name.

The modern `AudioManager.getCall*()` helpers construct their audio builders without passing a context. Merely invoking those same helpers through reflection preserves that behavior, and a shell context supplied only to `AudioManager` is not propagated to these builders. An alternative workaround must actually change the attribution source or use a construction path accepting it. Explicit shell-context builders are the project's current solution. The hard-coded UID 2000 context applies to a shell helper; root helpers require separate identity handling. Preserve the helper's audio attributes, redirection mode, and mode-change cleanup when replacing its construction path.

Reflection is a valid way to perform this attribution plumbing. Where `Builder.setContext(Context)` exists, invoking it reflectively with the shell context supplies the same attribution as calling it directly. A release-specific initialization path accepting equivalent attribution can also be investigated. The distinction is what the reflected call supplies: reflection into the builder to pass correct attribution can resolve the mismatch, whereas reflection into an unchanged context-free helper alone does not. This attribution fix lets the caller use its existing permissions; the separate native permission requirements still apply.

Android 6 has neither record nor track `Builder.setContext()`. The [record constructor](https://android.googlesource.com/platform/frameworks/base/+/android-6.0.1_r81/media/java/android/media/AudioRecord.java#383) passes `ActivityThread.currentOpPackageName()` to native code. [That method](https://android.googlesource.com/platform/frameworks/base/+/android-6.0.1_r81/core/java/android/app/ActivityThread.java#1646) returns the current application's operation package, or null without an application.

This release's [AppOps `getOpsLocked()`](https://android.googlesource.com/platform/frameworks/base/+/android-6.0.1_r81/services/core/java/com/android/server/AppOpsService.java#1113) normalizes operations for UID 2000 to `com.android.shell` and UID 0 to `root`. `recordingAllowed()` skips the app-op step for root after the permission check. These differ from modern attribution validation and need a release-specific implementation; neither behavior grants missing shell permissions. Preserve modern shell-context construction on releases that require it.

Missing `setContext()` is therefore an adaptable API difference, not an independent reason to reject API 23 or raise the minimum SDK. Where another release needs explicit attribution without this builder method, investigate its existing constructors, operation-package handling, and version-specific reflection or JNI entry points. Reflection can access existing hidden methods; it cannot invoke a method absent from that framework. No alternative construction recipe has been device-validated by this audit. On Android 6, shell AppOps normalization already handles the package mismatch, so such a workaround may be unnecessary.

Keep the following failures separate in capability reporting and diagnostics:

| Failure | Appropriate response |
| --- | --- |
| Caller package/attribution does not match the helper UID | Supply correct attribution through an available, release-specific construction path. |
| Effective recording/capture access is denied | Preflight required stock grants. A future hooked deployment would also need bounded construction/start checks to establish effective access. Changing attribution or reflecting into an audio constructor does not grant permissions. |
| HAL cannot establish or deliver the requested route | Report the observed route/open failure and require end-to-end device verification. |

The native permission checks use the Binder caller identity. [Policy input selection](https://android.googlesource.com/platform/frameworks/av/+/android-6.0.1_r81/services/audiopolicy/service/AudioPolicyInterfaceImpl.cpp#285) also replaces a client-supplied UID with the actual Binder UID for external callers. Passing a privileged package name or UID through reflection/JNI does not impersonate that caller. The stock Android 6 downlink blocker remains its actual shell permission grants, independently of attribution compatibility.

## Android 6 uplink selection and routing

Already present in API 23:

- [`AudioManager.getDevices()`](https://android.googlesource.com/platform/frameworks/base/+/android-6.0.1_r81/media/java/android/media/AudioManager.java#3774).
- [`AudioDeviceInfo.TYPE_TELEPHONY`](https://android.googlesource.com/platform/frameworks/base/+/android-6.0.1_r81/media/java/android/media/AudioDeviceInfo.java#105), mapped to telephony TX output and telephony RX input.
- [`AudioTrack.setPreferredDevice()`](https://android.googlesource.com/platform/frameworks/base/+/android-6.0.1_r81/media/java/android/media/AudioTrack.java#2224) and [`AudioRecord.setPreferredDevice()`](https://android.googlesource.com/platform/frameworks/base/+/android-6.0.1_r81/media/java/android/media/AudioRecord.java#1403).
- Both audio builders, including [`AudioTrack.Builder.setTransferMode()`](https://android.googlesource.com/platform/frameworks/base/+/android-6.0.1_r81/media/java/android/media/AudioTrack.java#641).

Ordinary [policy output selection](https://android.googlesource.com/platform/frameworks/av/+/android-6.0.1_r81/services/audiopolicy/service/AudioPolicyInterfaceImpl.cpp#149) normalizes the caller UID and forwards the request. It has no modern telephony TX `MODIFY_PHONE_STATE`/`CALL_AUDIO_INTERCEPTION` permission branch. [AudioFlinger track creation](https://android.googlesource.com/platform/frameworks/av/+/android-6.0.1_r81/services/audioflinger/AudioFlinger.cpp#539) likewise has no dedicated telephony TX permission check. Device enumeration/preference therefore does not itself require the modern interception permission on this audited release. Explicit audio-patch management is a different API with its own routing checks.

The [policy manager](https://android.googlesource.com/platform/frameworks/av/+/android-6.0.1_r81/services/audiopolicy/managerdefault/AudioPolicyManager.cpp#715) stores the selected output device as a session route. [Active explicit routes](https://android.googlesource.com/platform/frameworks/av/+/android-6.0.1_r81/services/audiopolicy/managerdefault/AudioPolicyManager.cpp#4141) are resolved by strategy. [`USAGE_VOICE_COMMUNICATION`](https://android.googlesource.com/platform/frameworks/av/+/android-6.0.1_r81/services/audiopolicy/enginedefault/src/Engine.cpp#267) maps to `STRATEGY_PHONE`; [output selection during a call](https://android.googlesource.com/platform/frameworks/av/+/android-6.0.1_r81/services/audiopolicy/managerdefault/AudioPolicyManager.cpp#3976) prioritizes that strategy. Use the existing voice-communication attributes as the first API 23 test recipe. Switching to media usage can cause the phone strategy to override the media route.

This is a plausible uplink construction/routing recipe, not proof of injection support. It needs an advertised telephony TX sink and an output profile capable of reaching it. The chosen stream, sample rate, channels, call mode, microphone mute, and vendor HAL can affect the result. Successful uplink playback would not solve the missing downlink capture permissions.

### HAL calls and errors

Java `setPreferredDevice()` validates the sink and calls native code. [Native `AudioTrack::setOutputDevice()`](https://android.googlesource.com/platform/frameworks/av/+/android-6.0.1_r81/media/libmedia/AudioTrack.cpp#1074) stores the ID, invalidates the track when it changes, and returns `NO_ERROR`. It does not synchronously establish a HAL route.

When policy applies the output route, the path is:

`AudioPolicyManager::startOutput()/startSource()` -> `setOutputDevice()` -> policy client `createAudioPatch()` -> AudioFlinger `PatchPanel` -> playback-thread `createAudioPatch_l()`.

The [playback thread](https://android.googlesource.com/platform/frameworks/av/+/android-6.0.1_r81/services/audioflinger/Threads.cpp#3130) calls HAL `create_audio_patch()` for HAL API 3.0+, or output-stream `set_parameters(routing=...)` for older HAL versions. These calls return a native status. However, [policy `setOutputDevice()`](https://android.googlesource.com/platform/frameworks/av/+/android-6.0.1_r81/services/audiopolicy/managerdefault/AudioPolicyManager.cpp#4262) returns a mute delay, not that status: it records a successful patch only when creation succeeds, but does not propagate failure through this return value. It can also ignore a requested device unsupported by the current output profile. Playback-thread routing state is updated before the HAL call, so reported route state alone is insufficient verification.

The app can see no advertised endpoint, builder `UnsupportedOperationException`, an uninitialized object when using constructors, start/read/write failures, or failure to obtain the expected effective route. Native permission denial may appear as a generic creation failure rather than a distinct Java `SecurityException`. A HAL route failure can also leave the app with apparently successful setup/playback. Keep permission preflight and native logs for diagnosis; verify remote signal delivery and return to normal routing on a physical device. No API in this path guarantees a working route or automatic microphone fallback.

## Android 6 default-dialer integration

API 23 has [`ACTION_CHANGE_DEFAULT_DIALER`](https://android.googlesource.com/platform/frameworks/base/+/android-6.0.1_r81/telecomm/java/android/telecom/TelecomManager.java#117) with a package-name extra. Its [candidate list](https://android.googlesource.com/platform/frameworks/base/+/android-6.0.1_r81/telecomm/java/android/telecom/DefaultDialerManager.java#153) checks handlers for `ACTION_DIAL` both without a scheme and with `tel:`.

[`InCallController`](https://android.googlesource.com/platform/packages/services/Telecomm/+/android-6.0.1_r81/src/com/android/server/telecom/InCallController.java#328) requires the service to be protected by `BIND_INCALL_SERVICE`, then admits either the default-dialer package or a package with `CONTROL_INCALL_EXPERIENCE`. It uses `IN_CALL_SERVICE_UI` metadata to select the default dialer's UI service. Qualifying non-UI services are bound separately, and the built-in UI is used when no default UI is selected or binding fails. Emergency calls use the built-in UI.

Default-dialer [runtime permission grants](https://android.googlesource.com/platform/frameworks/base/+/android-6.0.1_r81/services/core/java/com/android/server/pm/DefaultPermissionGrantPolicy.java#612) cover requested phone, contacts, SMS, and microphone permissions. They do not include `CAPTURE_AUDIO_OUTPUT`. The current companion permission/override mechanism is absent in this release. Keep the plan's optional default-dialer integration and gateway/recovery behavior; do not tie its readiness to modern companion commands.

## Android 7–9 framework differences

### Android 7.1.2, API 25

The relevant shell permission configuration and device/dialer APIs remain the same. `platform.xml` now includes audio-setting grants for audioserver and cameraserver as well as media; those grants do not apply to the shell helper. Default-dialer runtime grants also include camera access, without privileged output capture.

AppOps package normalization is implemented through `resolvePackageName()` and used by `noteOperation()`. Record JNI constructs the native object with the operation package and default caller UID/PID; attaching an already-created native record through its proxy constructor is also supported. These are construction mechanisms, with no established permission bypass. Keep any reflection/JNI adapter release-specific.

Framework evidence:

- Permissions: [shell requests](https://android.googlesource.com/platform/frameworks/base/+/android-7.1.2_r39/packages/Shell/AndroidManifest.xml#25), [system UID assignments](https://android.googlesource.com/platform/frameworks/base/+/android-7.1.2_r39/data/etc/platform.xml#132), [UID permission state](https://android.googlesource.com/platform/frameworks/base/+/android-7.1.2_r39/services/core/java/com/android/server/pm/PackageManagerService.java#3743), [default requested-permission grants](https://android.googlesource.com/platform/frameworks/base/+/android-7.1.2_r39/services/core/java/com/android/server/pm/DefaultPermissionGrantPolicy.java#218), [runtime grant checks](https://android.googlesource.com/platform/frameworks/base/+/android-7.1.2_r39/services/core/java/com/android/server/pm/PackageManagerService.java#4012).
- Call capture/control: [downlink permission documentation](https://android.googlesource.com/platform/frameworks/base/+/android-7.1.2_r39/media/java/android/media/MediaRecorder.java#219), [dialer runtime grants](https://android.googlesource.com/platform/frameworks/base/+/android-7.1.2_r39/services/core/java/com/android/server/pm/DefaultPermissionGrantPolicy.java#729), [dialer candidates](https://android.googlesource.com/platform/frameworks/base/+/android-7.1.2_r39/telecomm/java/android/telecom/DefaultDialerManager.java#154).
- Construction/routing: [record operation package](https://android.googlesource.com/platform/frameworks/base/+/android-7.1.2_r39/media/java/android/media/AudioRecord.java#381), [AppOps normalization](https://android.googlesource.com/platform/frameworks/base/+/android-7.1.2_r39/services/core/java/com/android/server/AppOpsService.java#2290), [native construction](https://android.googlesource.com/platform/frameworks/base/+/android-7.1.2_r39/core/jni/android_media_AudioRecord.cpp#263), [caller UID/PID defaults](https://android.googlesource.com/platform/frameworks/base/+/android-7.1.2_r39/core/jni/android_media_AudioRecord.cpp#292), [device enumeration](https://android.googlesource.com/platform/frameworks/base/+/android-7.1.2_r39/media/java/android/media/AudioManager.java#3910), [track preference](https://android.googlesource.com/platform/frameworks/base/+/android-7.1.2_r39/media/java/android/media/AudioTrack.java#2416), [record preference](https://android.googlesource.com/platform/frameworks/base/+/android-7.1.2_r39/media/java/android/media/AudioRecord.java#1611).

### Android 8.1, API 27

No additional shell recording/capture grants or builder-context API were found. Construction, AppOps shell/root normalization, device preference, and default-dialer candidate selection retain the same relevant behavior as Android 7.1.2. `RECORD_AUDIO` is now declared dangerous/instant; privileged output capture remains signature/privileged. Device-specific system/vendor configuration still needs a separate check.

Framework evidence:

- Permissions: [shell requests](https://android.googlesource.com/platform/frameworks/base/+/android-8.1.0_r81/packages/Shell/AndroidManifest.xml#25), [system UID assignments](https://android.googlesource.com/platform/frameworks/base/+/android-8.1.0_r81/data/etc/platform.xml#133), [UID permission state](https://android.googlesource.com/platform/frameworks/base/+/android-8.1.0_r81/services/core/java/com/android/server/pm/PackageManagerService.java#5348), [default requested-permission grants](https://android.googlesource.com/platform/frameworks/base/+/android-8.1.0_r81/services/core/java/com/android/server/pm/DefaultPermissionGrantPolicy.java#222), [runtime grant checks](https://android.googlesource.com/platform/frameworks/base/+/android-8.1.0_r81/services/core/java/com/android/server/pm/PackageManagerService.java#5654), [record permission declaration](https://android.googlesource.com/platform/frameworks/base/+/android-8.1.0_r81/core/res/AndroidManifest.xml#955).
- Call capture/control: [downlink permission documentation](https://android.googlesource.com/platform/frameworks/base/+/android-8.1.0_r81/media/java/android/media/MediaRecorder.java#222), [dialer runtime grants](https://android.googlesource.com/platform/frameworks/base/+/android-8.1.0_r81/services/core/java/com/android/server/pm/DefaultPermissionGrantPolicy.java#788), [dialer candidates](https://android.googlesource.com/platform/frameworks/base/+/android-8.1.0_r81/telecomm/java/android/telecom/DefaultDialerManager.java#154).
- Construction/routing: [record operation package](https://android.googlesource.com/platform/frameworks/base/+/android-8.1.0_r81/media/java/android/media/AudioRecord.java#381), [AppOps normalization](https://android.googlesource.com/platform/frameworks/base/+/android-8.1.0_r81/services/core/java/com/android/server/AppOpsService.java#2489), [native construction](https://android.googlesource.com/platform/frameworks/base/+/android-8.1.0_r81/core/jni/android_media_AudioRecord.cpp#266), [caller UID/PID defaults](https://android.googlesource.com/platform/frameworks/base/+/android-8.1.0_r81/core/jni/android_media_AudioRecord.cpp#292), [device enumeration](https://android.googlesource.com/platform/frameworks/base/+/android-8.1.0_r81/media/java/android/media/AudioManager.java#4275), [track preference](https://android.googlesource.com/platform/frameworks/base/+/android-8.1.0_r81/media/java/android/media/AudioTrack.java#2681), [record preference](https://android.googlesource.com/platform/frameworks/base/+/android-8.1.0_r81/media/java/android/media/AudioRecord.java#1612).

### Android 9, API 28

The missing recording/capture grants and available device APIs remain unchanged. Relevant differences are:

- Shell now requests `MANAGE_AUDIO_POLICY`, a separate signature permission that does not grant recording, output capture, or routing permissions. The shell privileged allowlist contains no capture grant, and the supplied manifests contain no other shell-shared-UID package requesting these permissions.
- UID permission and runtime-grant code moved into `services/core/java/com/android/server/pm/permission`. The declaration/type restrictions still prevent `pm grant` from solving the capture configuration.
- Non-SDK API restrictions introduce another compatibility check. Application hidden-API policy depends on platform signing, system allowlisting, overrides, and target SDK. Shell requests `DISABLE_HIDDEN_API_CHECKS`, but neither that request nor an operation-package change establishes arbitrary reflection access in a Shizuku app_process helper. Verify required reflection separately in the actual app and helper processes.
- Record operation-package handling, AppOps shell/root normalization, and JNI native-record construction/proxy support retain the same relevant behavior.

Framework evidence:

- Permissions: [shell requests](https://android.googlesource.com/platform/frameworks/base/+/android-9.0.0_r61/packages/Shell/AndroidManifest.xml#25), [policy-management permission](https://android.googlesource.com/platform/frameworks/base/+/android-9.0.0_r61/core/res/AndroidManifest.xml#3948), [system UID assignments](https://android.googlesource.com/platform/frameworks/base/+/android-9.0.0_r61/data/etc/platform.xml#136), [shell allowlist](https://android.googlesource.com/platform/frameworks/base/+/android-9.0.0_r61/data/etc/privapp-permissions-platform.xml#284), [UID permission state](https://android.googlesource.com/platform/frameworks/base/+/android-9.0.0_r61/services/core/java/com/android/server/pm/permission/PermissionManagerService.java#246), [runtime grant path](https://android.googlesource.com/platform/frameworks/base/+/android-9.0.0_r61/services/core/java/com/android/server/pm/permission/PermissionManagerService.java#1372), [declaration/type checks](https://android.googlesource.com/platform/frameworks/base/+/android-9.0.0_r61/services/core/java/com/android/server/pm/permission/BasePermission.java#376).
- Call capture/control: [downlink permission documentation](https://android.googlesource.com/platform/frameworks/base/+/android-9.0.0_r61/media/java/android/media/MediaRecorder.java#234), [dialer runtime grants](https://android.googlesource.com/platform/frameworks/base/+/android-9.0.0_r61/services/core/java/com/android/server/pm/permission/DefaultPermissionGrantPolicy.java#906), [dialer candidates](https://android.googlesource.com/platform/frameworks/base/+/android-9.0.0_r61/telecomm/java/android/telecom/DefaultDialerManager.java#155).
- Construction/routing: [record operation package](https://android.googlesource.com/platform/frameworks/base/+/android-9.0.0_r61/media/java/android/media/AudioRecord.java#390), [AppOps normalization](https://android.googlesource.com/platform/frameworks/base/+/android-9.0.0_r61/services/core/java/com/android/server/AppOpsService.java#3619), [native construction](https://android.googlesource.com/platform/frameworks/base/+/android-9.0.0_r61/core/jni/android_media_AudioRecord.cpp#275), [caller UID/PID defaults](https://android.googlesource.com/platform/frameworks/base/+/android-9.0.0_r61/core/jni/android_media_AudioRecord.cpp#301), [device enumeration](https://android.googlesource.com/platform/frameworks/base/+/android-9.0.0_r61/media/java/android/media/AudioManager.java#4615), [track preference](https://android.googlesource.com/platform/frameworks/base/+/android-9.0.0_r61/media/java/android/media/AudioTrack.java#2776), [record preference](https://android.googlesource.com/platform/frameworks/base/+/android-9.0.0_r61/media/java/android/media/AudioRecord.java#1584), [hidden-API policy](https://android.googlesource.com/platform/frameworks/base/+/android-9.0.0_r61/core/java/android/content/pm/ApplicationInfo.java#1682).

## Planning consequences

1. Preflight required helper grants and report known permission failures without repeated retries. Hook-specific capability handling is deferred.
2. The subsequent [Android 10 audit](ANDROID10_SOURCE_AUDIT.md) confirms a continuing stock capture blocker, while [Android 11](ANDROID11_SOURCE_AUDIT.md) supplies the first audited stock unrooted candidate. Target API 30 provisionally and keep both PCM directions in the same helper. API 23 becomes worth reevaluating only after a separate decision to pursue effective hooked access or another privileged deployment.
3. Isolate modern attribution/redirection classes and replace unsupported context-builder calls with release-appropriate construction where needed. Investigate reflection/JNI only for an existing entry point and an actual attribution failure. Missing `setContext()` alone does not set the minimum SDK; `setTransferMode()` itself needs no API 23 replacement.
4. Keep optional default-dialer and audio-backend selections in the collapsed Advanced section. Choosing direct telephony devices can avoid modern helper APIs/flags on suitable releases. For the current port, establish audio access through stock helper grants; Telecom qualification and HAL support remain separate requirements.
5. `MODE_CALL_REDIRECT`, `CALL_AUDIO_INTERCEPTION`, and the dedicated call-interception helpers are absent here. Report the current handset-suppression implementation as unavailable below its supported boundary.
6. Preserve 8/16 kHz bridge-format agreement and the helper's modem-driven clock. Test end-to-end cellular audio and handset microphone/output behavior after effective audio access is established. Android 7–9 native audio and Telecom still need release-specific verification before support is claimed.
