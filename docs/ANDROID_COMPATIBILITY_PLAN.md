# Android compatibility and optional platform integration

## Objective and assumptions

Target API 30 provisionally for the stock unrooted Shizuku port, based on the supplied Android 11 grants and native routing path. Shizuku is assumed to be available. Implement directly in the existing application; a separate demo or diagnostic harness is not a prerequisite. Keep capture and playback in the existing helper, preserving its socket transport and modem-driven clock. Confirm helper execution, call access, native loading, and physical two-way audio through the implemented app before claiming support. Keep the existing compile SDK, target SDK, and JVM toolchain unless a verified build constraint requires a change.

Xposed hooks, hook-aware app integration, and API 23–29 backports are deferred. The conditional permission-hook comparison below is retained as research; it is not an assumed dependency or implementation task. The immediate work is API 30 audio construction, default-dialer call access where required, API 30–33 compatibility, and a small Advanced settings section for existing platform choices.

The deferred sections retain construction recipes, runtime backports, optional hook designs, and validation requirements for future use. Their inclusion does not make those features part of the current port.

Default-dialer selection controls Telecom call access; it does not supply the privileged permissions needed for downlink capture or uplink playback. Those operations continue in the Shizuku service.

Keep one APK and the existing Telecom-in-app/Shizuku-audio process arrangement. Use the existing module boundaries and interfaces for the compatibility work. Direct privileged operation for a LineageOS/Magisk installation is an optional follow-up through those interfaces; see [companion and bridge refactor](#companion-and-bridge-refactor). Separate-process or independent-APK designs remain future options. This does not change the provisional API 30 stock minimum or bring the deferred older/Xposed work into scope.

The initial default-dialer implementation provides gateway operation and a recovery screen. When SIP is disconnected, bridging is disabled, or another essential gateway dependency is unavailable, refuse new gateway operations and offer a switch back to the user's usual dialer. A limited manual-call fallback is a deferred follow-up after gateway audio works; see [dialer scope and phased fallback](#dialer-scope-and-phased-fallback). It is not a requirement for the initial compatibility port or the audited role qualifier.

Separate three questions in support reporting:

1. Can this APK load and run on the Android version?
2. Can the selected call-control and audio implementations be opened with the available permissions?
3. Has two-way cellular audio been verified on this device?

## Source findings that determine scope

| Audit | Coverage and outcome |
| --- | --- |
| [Android 6–9](ANDROID6_9_SOURCE_AUDIT.md) | Shell lacks recording/output-capture grants on the audited API 23, 25, 27, and 28 tags. Native audio and Telecom were inspected for Android 6; Android 7–9 coverage is framework-only. The routing APIs already exist. Android 9 adds hidden-API compatibility checks. |
| [Android 10](ANDROID10_SOURCE_AUDIT.md) | Framework and native audio confirm the downlink permission blocker. Incall-music uplink checks phone-state permission and can retry local output on denial. Telecom companion classification/binding exist, but test-list registration requires `userdebug`; the current `MANAGE_ONGOING_CALLS` app-op is absent. RoleManager exists and public playback capture is insufficient. Generic role qualification and physical-device behavior remain unverified. |
| [Android 11](ANDROID11_SOURCE_AUDIT.md) | Framework, native audio, and Telecom establish the first audited stock unrooted candidate: shell has recording, output-capture, and phone-state grants; ordinary voice-communication playback can select telephony TX. Companion classification is incomplete on this tag, so stock call control requires explicit default-dialer selection with a UI-declared service. Helper/device verification remains outstanding. |
| [Android 12L](ANDROID12_SOURCE_AUDIT.md) | Audited API 32 has a record context setter and a context-free track, so process attribution still matters. Direct telephony routing remains available under the required privileges; modern redirection is absent. Telecom admits a non-UI service with permitted `MANAGE_ONGOING_CALLS` alongside the user's dialer. |
| [Android 13](ANDROID13_SOURCE_AUDIT.md) | API 33 retains non-UI AppOp admission without a companion override. Default attribution now prefers the Application source; tracks remain context-free. Redirection helpers and shell interception permission are present, while direct routing remains available. |

The [Android 12 admission patches and test evidence](ANDROID12_SOURCE_AUDIT.md#release-history-and-test-evidence) establish the origin of this non-UI path. The supplied release information places both its introduction (`4cc5a541`) and the attribution-aware permission-check follow-up (`e3f82cc7`) in the initial public Android 12/API 31 release. API 31+ readiness requires the enabled service, allowed `MANAGE_ONGOING_CALLS`, and calling permissions, without a companion override or default-dialer role. Full local tag audits cover API 32 and 33; these patches are not a full API 31 audio audit. A focused [current Android 17 Telecom check](ANDROID13_SOURCE_AUDIT.md#current-framework-follow-up-android-17) confirms independent NON_UI admission and removes the former app-imposed API 34+ override requirement. Android 14–16 tags were not separately audited.

The [audits index](README.md) records exact tags and coverage. These results do not establish every initial or vendor release, and no historical candidate has been tested for physical two-way audio. Check actual grants/capabilities at runtime. Matching [Android 11 PermissionController qualification](ANDROID11_SOURCE_AUDIT.md#permissioncontroller-dialer-qualification) is audited: one enabled gateway/recovery activity with two `ACTION_DIAL` filters, both carrying `CATEGORY_DEFAULT`, can qualify without a normal dial pad. One filter has no data scheme; the other handles `tel`. Role qualification and actual Telecom service binding remain separate checks. Role permission grants are limited to requested permissions in the configured ordinary permission sets.

The source findings support API 30 as the provisional stock minimum. Older releases have useful routing APIs but lack the audited shell capture grants; supporting them would require a separate deployment decision. API 23–29 compatibility is outside the current implementation scope. Missing HAL audio support on one vendor device does not, by itself, establish a higher universal minimum SDK.

Missing `Builder.setContext()` is an adaptable construction/attribution difference and does not, by itself, justify a higher minimum SDK. The current shell-context workaround makes caller attribution match the helper's UID so it can use existing shell permissions. Use release-specific construction, including reflection into existing hidden APIs where needed; keep attribution failures separate from missing native caller permissions. Reflection or changing an operation-package name does not grant those permissions.

Other recording options include manufacturer-specific support, a privileged system recorder, root, a potential Xposed module with root, and microphone capture with speakerphone in a quiet environment. See [alternative recording options](ANDROID6_9_SOURCE_AUDIT.md#alternative-recording-options); acoustic capture still requires separate uplink support for gateway operation. These are separate deployment possibilities outside the current port.

## Current implementation

The implementation below is already present. Later implementation steps retain the design and review checklist;
helper/device validation and the explicitly deferred features remain outstanding.

- All four Android modules currently declare `minSdk 30`; Android 11 support remains provisional pending helper/device verification.
- Telecom integration supports a companion non-UI `InCallService` or an explicitly selected default-dialer UI service with a gateway/recovery activity. Manual-call controls remain deferred; the recovery screen offers switching to the usual dialer.
- API 30 requires default-dialer mode. API 31+ companion readiness requires an enabled non-UI service, allowed `MANAGE_ONGOING_CALLS`, and calling permissions. The companion override remains diagnostic and a setup operation; its absence does not block readiness. Periodic setup retries unavailable companion call access while idle, without automatically requesting the dialer role.
- Collapsed Advanced settings expose call access, `AudioMethod` (`AUTO`, `OFFICIAL`, `LEGACY`), local output mute, loopback, debug logging, and diagnostics. The call log remains visible outside Advanced.
- The redirection (`OFFICIAL`) path uses `AudioManager.getCallDownlinkExtractionAudioRecord()` and `getCallUplinkInjectionAudioTrack()` when process-default attribution matches the helper. If helpers are unusable, it constructs equivalent PSTN record/track objects with attributed construction. Explicit redirection never falls back to direct routing; Automatic may fall back after cleanup, preserving the requested PCM format and failure details.
- The direct (`LEGACY`) path skips redirection helpers and flags, using `VOICE_DOWNLINK` and a track explicitly preferring `TYPE_TELEPHONY`. Construction detects each builder's `setContext(Context)` independently and prepares default attribution when it is absent; the documented introduction levels are API 31 for record and API 34 for track. `AudioRecord.Builder`, `AudioTrack.Builder`, and `AudioTrack.Builder.setTransferMode()` already exist in API 23.
- Local output suppression uses `MODE_CALL_REDIRECT` for supported redirection taps from API 33, and voice-call stream mute for direct taps, including API 30–32. Cleanup restores owned changes and preserves an initially-muted stream. Effectiveness and interactions with capture/injection remain subject to physical verification.
- The default-dialer role provides call access, not privileged call-audio capture/injection permissions; audio remains in the Shizuku helper.
- SIP exchanges PCM directly through the media ports. It does not currently inject into another Android VoIP application's capture stream.
- The current media implementation supports explicit 8 kHz and 16 kHz formats. Preserve the rule that both sides of a bridge have the same format; do not silently retry at another rate.

## Companion and bridge refactor

Keep one APK, the existing modules, and the current process arrangement: Telecom and the SIP/cellular bridge run in the app, while Shizuku owns the audio tap. The compatibility port needs clear ownership and small implementation choices within these boundaries. A new service architecture is not a prerequisite for proving API 30 support.

### Potential architectural benefits

The limited refactor has value within the current deployment. More extensive separation has additional potential benefits if a later deployment justifies its cost:

| Change | Potential benefit | What is needed to obtain it |
| --- | --- | --- |
| Keep cellular/audio implementations behind the existing interfaces | Android-version and vendor adaptations can remain local to the cellular side while SIP and bridge behavior stay reusable. Failure and cleanup paths can be exercised with fake audio openers without a PBX or modem. | Keep dependencies and configuration explicit; use focused tests for actual ownership/failure behavior. Physical audio still requires a phone. |
| Give the cellular controller clear call/audio ownership | Call end, late audio-open completion, helper loss, and takeover changes have one place to decide whether a tap remains valid. This reduces races and accidental interference with manual calls. | Implement and verify serialized ownership transitions and cleanup. Organizing code alone does not guarantee correct behavior. |
| Add optional local privileged operation through `CallAudioOpener` | A system-installed deployment can reuse the gateway without Shizuku audio-helper startup or shell attribution adaptation. The app's real UID/package identity simplifies audio construction and diagnostics. | Effective installed permissions, release-appropriate construction, and lifecycle/device verification. This is available within one APK/process. |
| Use privileged non-UI Telecom access where supported | Gateway call access can coexist with the user's normal dialer, reducing the need for gateway recovery UI to substitute for ordinary phone controls. | Verified service qualification and effective control permissions; audited Android 11 provides this path through `CONTROL_INCALL_EXPERIENCE`. Keep stock default-dialer support separate. |
| Later move Telecom and the tap into a dedicated process | The cellular runtime can have its own lifecycle and failure handling when SIP or native codec code crashes or restarts. Keeping calls and taps together makes service-side validity checks possible. | New IPC, startup/recovery logic, and demonstrated crash behavior. Client loss still needs an explicit cleanup policy; a process in the same APK shares its permissions. |
| Later package the cellular runtime independently | A separate UID can keep cellular privileges out of the SIP/UI package. The runtime could also serve another frontend or be maintained independently of the gateway. | Narrow authenticated IPC, service-side ownership enforcement, and compatible installation/update contracts. Only an actual independent package creates this permission boundary. |

Preserve the inexpensive dependency and ownership improvements now. The later process/APK benefits are reasons to keep extraction possible, not additional requirements for the compatibility port. None of these changes creates a missing HAL/modem route or establishes audio delivery by itself.

### Changes needed for the compatibility port

- Reuse `:telephony`, `:privileged`, `CallEndpoint`, `CallLeg`, `MediaPort`, and `CallAudioOpener`. Add or extend an interface only when a concrete implementation needs it. Keep `CallBridge`/`MediaBridge`, SIP/RTP, codecs, settings, and UI in their existing layers.
- Let the cellular controller own gateway-call eligibility and the audio-handle lifecycle. Check gateway ownership and active cellular state before opening or muting audio. Discard and close an asynchronous open result if its call has ended or ownership changed. Track extra/manual calls and stop or refuse tapping when global telephony routing makes isolation unsafe.
- Release the tap and restore owned mute/audio-mode changes when a call ends, audio is lost, takeover is disabled at the permitted transition, or setup fails. Retain helper/socket death handling and call recovery without claiming manual calls. Preserve PBX emergency rejection separately from manual/system emergency handling.
- Keep the existing narrow Shizuku AIDL, caller checks, and helper restart after APK updates. Extend its results only as needed for selected modes, capabilities, and observed failures. Preserve the `SOCK_SEQPACKET` transport, one 20 ms mono PCM16 frame per packet, explicit 8/16 kHz formats, and modem-driven clock.
- Keep Telecom `Call` objects internal to `:telephony`, cellular/audio code independent of SIP and UI, and necessary configuration explicit. Separate shell setup and attribution from reusable audio operations where implementation requires it. These dependency boundaries leave later extraction practical without introducing a new contract library or general cellular backend/client framework now.

Validate ownership and cleanup through the existing call/audio path, including late audio-open completion, helper death, extra calls, takeover changes, and normal handset restoration. Physical two-way audio at both formats remains the support gate.

### Optional direct privileged operation

After the stock gateway/recovery path and Advanced settings work, a system-installed deployment can implement `CallAudioOpener` locally in the existing app process using the reusable tap and the application's real context/UID attribution. Telecom remains in the app. Retain the PCM socket/clock behavior where practical; no new process, gateway-facing Binder service, or Telecom IPC protocol is required. Keep shell setup and shell attribution in the Shizuku implementation. Direct operation uses effective installed permissions rather than replaying shell setup.

For LineageOS/Magisk, install the single APK with the release-appropriate manifest permissions, privileged allowlist, runtime grants/app-ops, signing, and any required hidden-API or SELinux configuration. On audited API 30, direct audio requires `RECORD_AUDIO`, its app-op, `CAPTURE_AUDIO_OUTPUT`, and `MODIFY_PHONE_STATE`. Granting those permissions does not establish modem/HAL support; verify construction and physical audio under the actual app UID. Modern redirection and handset suppression retain their release/backend-specific requirements.

On the audited Android 11 tag, an effectively granted `CONTROL_INCALL_EXPERIENCE` allows a non-UI `InCallService` alongside the selected dialer. Retain `BIND_INCALL_SERVICE` protection and verify qualification on other releases. This can make default-dialer takeover unnecessary in a privileged deployment; it is separate from the stock API 30 call-access recipe.

### Future process or APK split

Consider a dedicated cellular process or independent companion APK only when a concrete deployment benefits from it. A future split would introduce Telecom Binder commands/events, snapshots, stale-session/generation handling, protocol versioning, and a contract library as needed. It would also need process-specific startup, reconnection/cleanup, and, for an independent APK, authenticated cross-package binding and installation/update validation. Those are future design tasks, not requirements of this port or the local privileged implementation.

A separate process in one package shares its UID and permissions; an independent APK with its own UID can provide a permission boundary. One APK remains the default distribution. Keep application work out of `system_server`.

## Settings and defaults

The implemented Advanced section is collapsed by default. Keep ordinary SIP configuration and the master gateway switch visible. Surface a required setup action in the main health/status area even when Advanced is closed.

| Setting | Choices | Default and behavior |
| --- | --- | --- |
| Call access | Automatic, alongside existing dialer, use as default dialer | Automatic uses default-dialer integration when the app holds the role or on API 30, and companion integration otherwise. On the unmodified audited API 30 baseline, companion integration is unavailable: show the required default-dialer setup action and wait for user selection; refusing it leaves gateway call access unavailable. Default-dialer mode remains selectable on newer Android versions. Explain that this mode supports gateway operation and shows a switch-dialer recovery action when SIP is disconnected. |
| Cellular audio | Automatic, direct telephony device, call redirection | Automatic prefers modern PSTN redirection when usable, using framework helpers first and equivalent manual construction when a construction/attribution workaround is required. It may fall back to direct telephony routing after complete cleanup, preserving failure details and the PCM format. API 30–32 use the legacy path when required audio access is available. Explicit direct telephony skips interception helpers/flags; explicit redirection never silently switches to direct routing. Otherwise report audio unavailable. |
| Mute local call output | On, off | On attempts mode-based suppression for supported redirection taps and voice-call stream mute for direct taps. Off keeps normal call output for local listening; the handset microphone stays muted. Changes apply from the next call. Show unavailable/ineffective status instead of claiming the handset was silenced. Keep existing users' explicit preference. |
| Diagnostics | Show capabilities and last audio-open result | Present Android/API version, requested and effective modes, privileged permission checks, advertised endpoints, and setup failures. Avoid displaying object creation or frame counters as proof of remote audio delivery. |

Use product labels in the UI; put API names and error details in diagnostics. Keep requested settings separate from effective capabilities so an unavailable feature does not erase the user's preference.

VoIP redirection is a separate extension point, disabled by default if implemented later. Do not label the existing PSTN backend as VoIP support. The compatibility work does not require adding injection into unrelated Android VoIP applications. If that feature is subsequently required, give it a separate backend and enabled setting, gated by the relevant APIs and permissions. Cellular-only operation must not initialize it.

## Workload by API level

| API group | Implementation and remaining checks |
| --- | --- |
| 30 | Context-free construction, direct telephony audio, and gateway/recovery default-dialer integration are implemented. Verify helper startup, native loading, callbacks, mute/restoration, and two-way audio on a real device. |
| 31-32 | Independent builder detection and attribution adaptation are implemented; call access uses non-UI AppOp admission. Full source coverage is API 32, with API 31 admission-patch evidence only. Verify runtime construction, service binding, and physical audio. |
| 33+ | Direct and modern redirection choices, optional local suppression, and settings/lifecycle guards are implemented. Verify release-specific attribution, routing, and cleanup on devices; full local source coverage is API 33 with a focused current API 37 Telecom follow-up. |

Keep diagnostics limited to the selected call-access/audio path, observed failures, and format. Preserve cleanup, dependency/native-library compatibility, settings migration, and physical two-way audio checks at the requested 8/16 kHz formats. Establish the API 30 path before expanding the device/version matrix.

## Deferred Xposed scenario

No Xposed module, Telecom hook, hook-selection setting, or hook-specific diagnostics are planned for now. The following comparison records what would change if effective audio-permission hooks were already available in a future deployment.

If `CAPTURE_AUDIO_OUTPUT` were already overridden, satisfying `RECORD_AUDIO` and its record app-op would be a small extension of that setup. Both PCM directions could remain in the Shizuku helper. API 23 would then be worth evaluating, with runtime compatibility, Telecom access, native loading, and device audio still to verify. This conditional result does not make API 23 a current target. Hooks cannot create missing HAL/modem routes.

| Deferred API group | Work remaining if effective audio-permission hooks were already available |
| --- | --- |
| 23-25 | Most runtime backports: pre-26 service/notification/process APIs, API 23 network handling, older emergency-number checks, intent-based dialer selection, and legacy audio construction/routing. Android 6 native routing is audited; Android 7 native audio/Telecom behavior still needs checking. |
| 26-27 | Fewer runtime backports because channels, foreground-service launch, and timed process APIs exist. Retain older dialer selection/emergency checks and legacy audio construction. Native routing/Telecom coverage remains incomplete. |
| 28-29 | Hidden-API validation from API 28 and the RoleManager transition at API 29. Android 10 uplink checks phone-state permission, which shell has, and can fall back to local playback on denial; verify the effective route. Its Telecom companion-list test API is restricted to `userdebug`. Android 9 native behavior remains unaudited. |

For API 30+, the [current workload table](#workload-by-api-level) still applies. Stock shell capture grants make audio-permission hooks unnecessary on the audited API 30 tag. All groups would still need capability reporting, cleanup, dependency/native-library compatibility, settings migration, and physical two-way audio verification at the requested 8/16 kHz formats. Under the conditional hook assumption, permission handling would no longer be the main pre-30 workload; API 30 could serve as the audited legacy reference while API 23 became a separate compatibility target.

### Audio permission diagnostics for a future hooked deployment

Effective `CAPTURE_AUDIO_OUTPUT` access would have to be established for the helper, together with `RECORD_AUDIO` and its record app-op. This would not require moving capture into the app, splitting the PCM directions, or adding a root audio helper. Keep both directions in the helper with the existing socket transport and modem-driven clock. The source audits' stock grants and native checks remain valid.

Such an integration would need to report selected deployment and effective capture access separately from declared package grants. A package permission query can still report denial when a scoped hook makes the actual audio check pass. Bounded helper construction/start checks would be needed rather than rejecting the backend solely on that query. The hook would need to cover recording checks and the app-op at creation and start; missing effective access would remain a setup failure. Hook/module presence alone would not prove that audio can be opened.

Audio permission handling would remain separate from Telecom qualification and HAL support. Use explicit default-dialer selection or a separately verified call-access integration. Hooks cannot create missing telephony endpoints, compatible profiles, or HAL/modem routes.

### Optional Xposed call access (deferred)

Xposed could make default-dialer replacement optional on the audited Android 11 baseline by changing Telecom's InCallService qualification. No module or app-side integration is planned for now. The [Android 11 hook alternatives](ANDROID11_SOURCE_AUDIT.md#optional-xposed-call-access) and [Android 10 differences](ANDROID10_SOURCE_AUDIT.md#optional-xposed-call-access) remain reference material.

The preferred candidate is a hook in the process hosting Telecom that classifies only the exact gateway package/component as `IN_CALL_SERVICE_TYPE_NON_UI`, bypassing its `CONTROL_INCALL_EXPERIENCE` qualification. Keep `BIND_INCALL_SERVICE` protection and non-UI/non-car-UI declarations. Telecom already enumerates non-UI services alongside the selected dialer and supplies the normal InCallAdapter after binding. Ordinary call-placement permissions and gateway call-ownership checks still apply.

Alternatively, restore the Android 11 companion classifier branch and ensure the gateway is included in the companion package list. Changing a companion permission result alone is insufficient on that tag because the classifier has no branch returning the companion type. This alternative needs both classification and list membership; the non-UI approach avoids the companion-list dependency. Android 10 already has companion classification, but its test-list registration is restricted to `userdebug`, so its alternative would address list membership/registration instead. Match any hook to the actual release/vendor implementation and scope it to the intended component/user rather than changing permission results for every app.

If implemented later, Xposed call access would be an explicit experimental choice under collapsed Advanced settings; automatic selection would retain the ordinary platform policy. It would need its own setup/readiness results. The non-UI approach would need neither a companion override nor the absent `MANAGE_ONGOING_CALLS` app-op. Module installation/enabling alone would not prove Telecom is hooked or the service binds. Validate the configured component while idle and actual binding/callbacks during calls; an existing idle binding must not be required for the first call. If the hook became unavailable, refuse new gateway operations and report unavailable call access without silently choosing another backend or replacing the dialer. Takeover off could disable the non-UI component, with active-call changes deferred, because that mode would not require holding the dialer role.

Call access would remain separate from audio. API 30 could retain the audited direct-routing recipe with existing shell grants. A Telecom-only hook would not address older capture checks; an effective audio-permission hook with its recording/app-op extension could address those separately while both PCM directions stayed in the helper. A root helper would remain an alternative deployment, not a prerequisite. Xposed cannot create missing HAL/modem routing support. Default-dialer selection remains the current API 30 path, and the stock unrooted recommendation remains provisional API 30.

Any future implementation would need validation with the original dialer still selected: service binding, incoming/outgoing callbacks, answer/disconnect, manual-call isolation, takeover off, hook loss, Telecom restart/reboot, and cleanup. Verify the selected audio backend independently at 8 and 16 kHz. These checks are deferred with that feature.

### Older Android construction and runtime compatibility (deferred)

For a future API 23 deployment with sufficient effective audio access, remove unsupported `Builder.setContext()` calls while retaining the already-supported builders and `setTransferMode()`. Account for `ActivityThread` operation-package selection and old AppOps behavior instead of loading the modern attribution implementation. Android 6 shell AppOps normalization may make an attribution adapter unnecessary; validate record and track construction in the actual helper before adding one. Reflection/JNI can use existing entry points but cannot invoke a method absent from the framework or change the real Binder caller identity.

Isolate `AttributionSource` and other newer classes from shared signatures, static initialization, and eagerly constructed objects. For pre-29 support, isolate `RoleManager` and select it only from API 29. An SDK check around a call alone does not cover every class-loading case.

The detailed runtime backports retained for a future older port are:

- Foreground-service launch before API 26, notification channels from API 26, and foreground-service type overloads from API 29; special-use handling starts at API 34.
- Notification permission grants from API 33 and other runtime permissions only where defined and requested.
- Default-network callbacks below API 24 using an appropriate network callback or connectivity mechanism.
- Emergency-number checks below API 29 using the release-appropriate API, keeping PBX dialing fail-closed on genuine check failure.
- `Process.waitFor(timeout)` and `destroyForcibly()` below API 26 using bounded polling/termination.
- Default-dialer selection through `ACTION_CHANGE_DEFAULT_DIALER` on API 23–28, and `RoleManager.ROLE_DIALER` on API 29+.
- Context device identity and attribution only on versions that support them.
- Hidden-API validation from Android 9 onward in the actual Shizuku helper and app process. An attribution adapter does not confer audio permissions, and an operation-package change does not establish the shell package's hidden-API treatment for loaded app code.
- Version-appropriate gateway/recovery notifications and navigation to the system dialer selector/settings.

Audit AndroidX AAR manifests and Java library API calls at the proposed minimum. Pin compatible dependencies or enable appropriate desugaring where needed; desugaring does not replace Android framework API guards. Lower module/native minimums consistently, rebuild the native dependencies and wrapper, and verify loading on the oldest intended release before claiming support.

### Validation of deferred deployments

This matrix retains the earlier checks for future use. None is a gate for the current stock API 30 port.

| Deferred deployment | Checks before claiming support |
| --- | --- |
| Android 6–9 with effective audio hooks | Verify effective capture/recording/app-op access in the helper, old construction/attribution, native loading, notifications/service compatibility, and older dialer selection. Check unmodified systems report unavailable without effective access. Android 9 needs hidden-API checks. Native audio policy and Telecom remain unaudited for Android 7–9. |
| API 29 with effective audio hooks | Verify effective downlink access. Incall-music playback checks `MODIFY_PHONE_STATE` and can retry local output on denial. Verify the requested/effective route and actual remote receipt. Telecom classification/binding are source-audited; production-build companion-list registration is restricted. Generic dialer-role qualification and physical-device behavior remain to verify. |
| API 30 with a Telecom hook | Verify the exact gateway service binds alongside the selected dialer, ordinary call control, mode-specific readiness without the absent app-op, hook loss/restart/reboot, takeover off, manual-call isolation, and cleanup. Verify shell audio independently; this would not establish stock companion support. |

On any future API 23 candidate, check effective recording/capture access, release-appropriate construction, selected call integration, native loading, telephony TX/RX endpoints and profiles, and physical two-way audio. Keep declared grants separate from effective hook results. Check microphone mute and handset output independently; a preferred device does not create a HAL route. Run bounded start/read/write checks and verify known signals at both ends, at 8 kHz and then 16 kHz without silent rate fallback. Emulator results cannot establish modem audio support.

## Implementation sequence

### 1. Implement the API 30 helper and audio path

The source gate, including PermissionController dialer qualification, is complete for `android-11.0.0_r48`; see the [Android 11 audit](ANDROID11_SOURCE_AUDIT.md) for the construction/routing recipe, role declarations/grants, and native error behavior. The remaining work is helper execution, actual role selection/callbacks, native loading, and physical audio. Historical Android 6–9 details stay in the [combined audit](ANDROID6_9_SOURCE_AUDIT.md).

Use API 30 as the first implementation candidate. Begin with release-appropriate audio construction and capability reporting in the existing privileged implementation, followed by minimal default-dialer integration and runtime/build compatibility. Verify those changes through the application, without a separate demo phase.

Established Android 11 findings:

- Native downlink capture requires `RECORD_AUDIO` plus its app-op and `CAPTURE_AUDIO_OUTPUT`. Shell's manifest, build, allowlist, and runtime-grant policy provide the required grants.
- Android 11's landed `8a1095a9...` patch rejects telephony TX playback without `MODIFY_PHONE_STATE` rather than silently retrying local output. The landed `dbf0a2ee...` patch separately enables privileged voice-communication playback capture using `CAPTURE_VOICE_COMMUNICATION_OUTPUT`; shell lacks that grant, and this does not change the cellular downlink permission recipe. See the [commit history and read/write permission table](ANDROID11_SOURCE_AUDIT.md#android-11-permission-changes-in-the-supplied-patches), including policy-registration requirements and the remote-submix Binder-identity caveat.
- Ordinary `USAGE_VOICE_COMMUNICATION` playback with explicit telephony TX, linear PCM, and an accessible call mode automatically selects `AUDIO_OUTPUT_FLAG_INCALL_MUSIC`. Telephony TX classification requires `MODIFY_PHONE_STATE`, which shell has. This recipe avoids system usage and strategy-wide routing paths that require the audio-routing permission shell lacks.
- The builders lack `setContext()` and `AttributionSource` is absent. Start with context-free construction; Android 11 AppOps normalizes shell UID operations to `com.android.shell`. Verify actual package handling and hidden-API access in the helper.
- Telecom's companion override command changes the list, but its InCallService classifier never returns companion type. Mark ordinary companion mode unavailable on this tag. Default-dialer binding requires `BIND_INCALL_SERVICE` protection and `IN_CALL_SERVICE_UI=true` metadata.
- Output HAL patch failures are not consistently propagated. A successful preference setter, reported route, playback state, or frame counters cannot establish delivery. Record start failures can leave Java recording state stopped without an exception.
- The redirection backend and `MODE_CALL_REDIRECT` handset suppression are absent. Leave redirection unavailable on API 30; direct taps can attempt the voice-call stream-mute fallback described below.

Next checks:

- Verify the actual API 30 helper's permission grants, record app-op, operation package, hidden-API access, context-free record/track construction, and bounded start/I/O behavior.
- Verify actual user role selection and both gateway/recovery dial-intent forms on a test device/emulator. Matching PermissionController source confirms qualification through the two default-category dial filters; the checker does not inspect UI functionality. Confirm UI-service binding and callbacks when a call occurs. While idle, validate the role and enabled/protected component configuration; Telecom binds in response to calls, so an existing idle binding is not a prerequisite for the first gateway call.
- Keep the release boundary precise: Android 7.0/API 24 and Android 8.0/API 26 were not separately audited, and this audit covers a later Android 11 tag. If a claim of all API 30 builds is needed, compare an initial Android 11 baseline; regardless, use actual capabilities at runtime.
- Verify telephony TX/RX advertisement, compatible output/input profiles, and actual PCM delivery on the candidate device. A preferred device does not create an unsupported HAL route.
- Interactions between Telecom microphone mute, injected PCM, and handset output.
- Default-dialer gateway/recovery behavior on API 30, including refusal of new gateway operations when essential dependencies are unavailable.

Preflight helper grants in the production audio-open path and stop with a precise unsupported result when required access is absent. Use the implemented app on a real API 30 candidate phone to verify a known remote signal in captured downlink and a known injected signal at the far end. Check microphone mute independently. Exercise 8 kHz first, then 16 kHz without silent rate fallback. These are support-verification checks, not a prerequisite demo before changing code.

Support exit criteria after implementation: verified helper construction, default-dialer callbacks and native loading, plus observed two-way audio on a physical API 30 device. Keep API 30 provisional until this passes. Emulator tests cannot establish modem audio support.

### 2. Introduce capability reporting and implementation boundaries

Extend existing health/open results with the selected call-access/audio path, helper UID, required permission results, and last construction/start failure. Distinguish available, unavailable, unknown, and last-attempt failure. Shizuku running and default-dialer status are separate from audio readiness. Distinguish attribution mismatch, unavailable construction APIs, and HAL/route failures wherever observable. Record the requested and effective backend, whether redirection uses framework helpers or manual construction, and the PCM format after opening audio. Method presence alone is not helper usability: include hidden-API access, actual caller permissions, compatible cellular-call mode, attribution, and construction results. Hook-specific deployment results remain part of the deferred reference notes.

Split platform-dependent operations into small implementations selected by API and capability:

- Telecom access: companion or default dialer.
- Cellular audio: legacy telephony device routing or modern PSTN redirection.
- Shell attribution and audio construction: release-appropriate implementations.
- Handset output suppression: supported implementation or explicitly unavailable.

Isolate newer classes such as `AttributionSource` so API 30 does not load them through shared signatures, static initialization, or eagerly constructed objects. `RoleManager` is available throughout the current target range. An SDK check around a call alone is not sufficient for every class-loading case.

Extend existing health/open responses and the narrow Shizuku AIDL only where selected modes and observed failures require it. Keep the service's caller checks and restart after APK updates. Retain the current control/audio transport.

Use the existing `CallEndpoint` and `CallAudioOpener` boundaries as described in the [companion refactor](#companion-and-bridge-refactor). Keep ownership and cleanup in the cellular controller and shell-specific setup/attribution in the privileged implementation. Add small implementations as the compatibility work needs them; a general backend/client framework, new contract module, dedicated process, and Telecom IPC protocol are deferred. Direct privileged operation is a later `CallAudioOpener` implementation in the same APK/process. Call-access choice, audio implementation, and deployment mechanism remain distinct capabilities.

### 3. Implement the older cellular audio backend

Adapt the existing legacy backend with release-specific construction and the recipe verified in phase 1. Keep record and track in the helper with the existing transport and clock.

Android documents [`AudioRecord.Builder.setContext(Context)`](https://developer.android.com/reference/android/media/AudioRecord.Builder#setContext(android.content.Context)) from API 31 and [`AudioTrack.Builder.setContext(Context)`](https://developer.android.com/reference/android/media/AudioTrack.Builder#setContext(android.content.Context)) from API 34. Their construction boundaries differ:

| Android API | Record builder context setter | Track builder context setter | Helper construction |
| --- | --- | --- | --- |
| 30 | Absent | Absent | Context-free builders with verified operation-package attribution. |
| 31–33 | Available | Absent | Explicit record context; verified process attribution for the context-free track. |
| 34+ | Available | Available | Explicit contexts for both builders where usable. |

These are documented introduction levels. Keep runtime detection separate for each builder to handle vendor differences, and report invocation failures instead of treating them as an absent method. The supplied Android 11 source confirms the API 30 row; the supplied [Android 12L source](ANDROID12_SOURCE_AUDIT.md#construction-and-attribution-differences) and inspected API 32 device confirm the record/track difference on that release. This is not a full API 31–34 source audit.

For API 30, implement the verified native-policy recipe: `VOICE_DOWNLINK` recording and ordinary `USAGE_VOICE_COMMUNICATION` playback preferring telephony TX during an active cellular call. Policy supplies the incall-music flag automatically; do not set a hidden output flag or request a new audio mode. Neither builder has `setContext()`, and the `AttributionSource` class is absent. Android 11 AppOps resolves shell operations to `com.android.shell`; prepare and verify the helper's operation package before context-free construction, using the adapter described below when bootstrap attribution is mismatched. Do not select the current redirection backend: its helper APIs and redirection flags are absent. `USAGE_CALL_ASSISTANT` alone is not an equivalent backend, and its validation requires routing permission that this shell lacks.

If a candidate release still rejects caller attribution, inspect its available constructors and hidden initialization entry points before deciding it is unsupported. Use a narrow, version-specific reflection or JNI adapter only when needed and validate it in the actual helper process. Do not attempt to reflectively call an absent `setContext()` method or treat supplied package/UID values as a way to acquire privileges. Permission preflight and device audio verification remain required for any alternative construction path.

On Android 11, distinguish recording AppOps normalization from playback's earlier package check. `OpPlayAudioMonitor::createIfNeeded()` validates a nonempty package against the actual UID's packages before querying AppOps. An empty supplied package resolves to a package for that UID; a nonempty gateway-app package paired with shell UID 2000 forces track muting even if creation and writes succeed. Use natural helper attribution when it produces an empty or correct shell package. If the helper supplies a mismatched app package, adapt the actual track-construction path. Using `com.android.shell` for UID 2000 is correct attribution and does not confer permissions. Passing a shell context only to `AudioManager` does not fix context-free builders.

Landed Shizuku commit [284db86](https://github.com/RikkaApps/Shizuku-API/commit/284db86e8cae49030546a5fed7b5bd1a211a5278) ("Pass Application instead of Context to UserService") introduced the inspected Application bootstrap. It creates an application for the gateway package, installs it as `ActivityThread.mInitialApplication`, and passes that application to the UserService constructor. Android 11's `currentOpPackageName()` therefore returns the gateway operation package for an ordinary gateway app. Context-free track construction hits the playback package mismatch with shell UID 2000. Inspect the effective helper identity at runtime.

The installed Shizuku server controls this bootstrap. Changing the gateway's client API dependency does not restore older startup behavior or establish the installed server version. Keep the current client dependency and adapt the effective runtime attribution where needed.

Before that commit, Shizuku passes a package Context without installing an initial Application. On the audited Android 11 path, provided nothing else installs one, `currentOpPackageName()` is null: `PlayerBase` supplies an empty playback package and AudioFlinger selects a package associated with the actual UID. `AudioRecord` instead falls back to `uid:<callingUid>`, and recording AppOps normalizes shell UID operations to `com.android.shell`. That bootstrap normally needs no operation-package adapter for these builders. Passing the package Context to the service does not itself determine their attribution.

Keep the Android framework boundary separate from the Shizuku bootstrap boundary. API 30 has operation-package strings and no `AttributionSource`; API 31 introduces `AttributionSource`, whose classes must stay isolated from API 30 code. Shizuku's bootstrap affects the process Application used for default attribution, but does not add framework APIs or change its Linux UID. Retain explicit shell-context construction on newer releases where required and supported; do not infer that the older Context-only bootstrap on Android 11 proves newer default `AttributionSource` handling is correct. Inspect each release's actual attribution path and validate it in the helper.

The inspected client API has no `UserServiceArgs` option to select Context-only startup or override attribution. A no-argument service constructor does not avoid Application creation, which happens before constructor selection. Retain UserService for Java/JNI audio execution. `ShizukuBinderWrapper`/`transactRemote()` forward selected Binder transactions with the server's identity but do not transparently relocate native `AudioRecord`/`AudioTrack` execution. `rish` could launch a custom helper with additional lifecycle/transport work; legacy `newProcess()` is deprecated and private in this checkout. These are not prerequisites for the port.

Separate bootstrap failure from audio-attribution failure in readiness reporting. The landed bootstrap invokes default Application creation (`makeApplication(true, null)`), not the gateway's custom Application lifecycle, but still runs framework/vendor initialization without a normal AMS app binding. A ROM exception before the service constructor requires a server-side fix or another launch path; the helper-local audio adapter cannot run early enough to repair it.

For this bootstrap path, the preferred API 30 construction adapter is a one-time helper-local application-context adjustment before creating audio objects. Wrap the existing application's base context with a `ContextWrapper` overriding only `getOpPackageName()` to `com.android.shell`, and reflectively replace that application's inherited `ContextWrapper.mBase`. Preserve the existing application object, package name, application metadata, class loader, real process UID, and service caller checks. This changes the operation package read by `ActivityThread.currentOpPackageName()` and lets the ordinary audio builders supply valid attribution. Apply only to the intended shell helper/version path, guard initialization, and verify the effective operation package after installation; a failed adapter must report unavailable audio rather than silently continue with a mismatched package. Do not reuse the current shell wrapper that also changes `getPackageName()` and references newer attribution classes. Actual hidden-field access remains a runtime compatibility check; no additional Shizuku source clone or separate demo is required before implementing this candidate.

For modern PSTN redirection, prefer `AudioManager.getCallUplinkInjectionAudioTrack()` when it is usable. Prefer the corresponding `getCallDownlinkExtractionAudioRecord()` where usable as well. Invoke hidden helpers through release-appropriate access, using the requested 8/16 kHz format. They provide the required redirection attributes and mode-change lifecycle handling. Before invocation, require gateway ownership of an active cellular call and a compatible PSTN audio mode; these helpers also support VoIP redirection according to the current mode, so do not accidentally select an unrelated VoIP path for a cellular bridge.

Inspect effective attribution rather than assuming every Shizuku process has an app-package/shell-UID mismatch; modern default attribution can also resolve a package from the actual UID. When process-level default attribution is correct, call the real framework helpers instead of recreating their objects. Reflectively invoking an unchanged helper does not fix its internally selected attribution, and supplying a shell context only to `AudioManager` does not pass it to that helper's context-free builders. Use identity handling appropriate to the actual helper UID rather than reusing the UID 2000 context for root.

If helper construction requires an attribution or release-compatibility workaround, retain equivalent manual redirection construction within the same backend. It must change the actual construction/attribution path, preserve required capture/playback attributes and PSTN redirection mode, and implement equivalent mode-change cleanup. Required permissions and call-mode restrictions still apply; manual construction is not a bypass for a permission denial or unsupported call route. Expose the effective construction path and original helper failure in diagnostics.

Treat helper-managed release on an incompatible mode change as audio loss: close the paired objects and socket, notify the existing call/audio owner, and restore owned mute/mode state. Manual redirection construction must release its objects on the same incompatible mode transitions. Central cleanup must remain idempotent, including partial helper construction, fallback, call end, helper death, and late open completion.

Reflection can implement the shell-context plumbing: on a release providing `Builder.setContext(Context)`, a reflective invocation passing the shell context is equivalent to the direct builder call for attribution. Treat this as a valid attribution workaround, distinct from merely reflecting into the unchanged helper. On releases without that method, inspect equivalent existing initialization paths instead. Correct attribution enables use of existing caller permissions and does not replace the native permission checks.

API 32 device-framework follow-up (2026-10-12): the observed call reached active cellular and SIP legs, but audio setup failed because the implementation assumed both builders provided `setContext(Context)` from API 31. Inspection of the connected device's `/system/framework/framework.jar` confirmed that `AudioRecord.Builder` provides the setter and `AudioTrack.Builder` does not, consistent with the documented API 31/34 introduction levels above. Its track constructor supplies `PlayerBase.getCurrentOpPackageName()` to native setup, which reads `ActivityThread.currentOpPackageName()`. Its `AttributionSource.myAttributionSource()` likewise derives attribution from the process UID and current operation package. These are findings for that inspected framework, not a full API 31–33 source audit.

The fix detects each builder's setter independently. Where either is absent, prepare and verify helper-local default attribution before construction. On API 31+, wrap the existing Application base context and override only `getOpPackageName()` and `getAttributionSource()`, preserving the Application object, its real package metadata, and the Linux UID. This covers the inspected operation-package path and frameworks that read the Application attribution source. Keep explicit builder contexts where supported. Report adapter or setter-invocation failures instead of constructing with unverified attribution; retain the separate API 30 operation-package adapter.

Validation after this fix: all 10 privileged unit tests passed, including four builder-context regression tests; debug and release APK builds succeeded; app debug lint reported zero errors. Physical two-way audio with the rebuilt APK remains unverified. Missing modern redirection APIs on API 32 are expected and do not prevent trying the direct telephony backend when its prerequisites are available. Neither permission preflight nor successful construction proves modem delivery.

Handset-output follow-up (2026-10-12): direct tap construction and cellular-to-SIP voice were observed on the API 32 device, but handset output remained audible. Direct taps now attempt `adjustStreamVolume(STREAM_VOICE_CALL, ADJUST_MUTE, 0)` as the helper, retaining IN_CALL. Audited Android 11 AudioService admits voice-call stream mute with `MODIFY_PHONE_STATE`; a nonzero minimum volume does not prohibit this mute operation. Preserve the original mute state without changing the volume index, verify the observed mute flag after a bounded wait, and undo only owned changes with ADJUST_UNMUTE. Supported redirection taps retain MODE_CALL_REDIRECT. Telecom microphone mute remains separately owned by the cellular controller.

Mute failures are best effort and reported in diagnostics; they do not fail an otherwise working audio tap. Tap close and helper teardown attempt restoration, and failed restoration remains owned for retry. Register tap ownership before starting its threads, serialize mute with open/close, and clear Binder caller identity on restoration as well as acquisition. Global stream mute has no process-death lease: a forcibly killed helper can leave it set. Raising call volume can unmute it. Unlike mode-based suppression, this fallback does not tear down handset audio patches; the mute flag does not prove physical silence or unchanged capture/injection. Verify local output, both remote audio directions, normal restoration, and forced-death recovery on each device.

Keep the capture/injection implementation independent of call-access mode. A default-dialer app still invokes the Shizuku audio service.

For automatic selection, skip unavailable implementations before calling them, prefer usable framework helpers within modern redirection, preserve failure details, and allow same-format fallback after complete cleanup. Explicit direct-telephony selection must not initialize interception helpers or redirection flags. Explicit redirection may use its documented manual construction workaround, but must report failure if that backend remains unusable instead of switching to direct telephony. Keep the requested 8/16 kHz format consistent with the SIP media ports.

Check record/track initialization, `recordingState` after start, bounded read/write progress, error codes, partial writes, and teardown. Java start calls do not reliably report native start failure; track playback state also does not prove successful start or routing. Handle zero-progress writes without an endless tight loop. Restore any changed mode/mute state when setup fails, a call ends, or the helper dies. Report permission denial, unsupported API/format, missing endpoint, creation/start failure, and runtime audio loss distinctly where the framework permits it; retain generic failure details when no precise cause is exposed.

Account for native routing behavior within a chosen backend: the traced Android 11 creation chain propagates telephony TX permission denial, while Android 10 retries on the default output when denied. Ordinary Android 11 output routing can ignore a HAL patch failure. A successful builder, preferred-device setter, or write is insufficient. Inspect the effective route where available, preserve the original request in diagnostics, and reject an observable local-output fallback for gateway injection. Route reporting still does not prove remote PCM delivery.

### 4. Add optional default-dialer integration

Provide separate companion and dialer InCallService components sharing a common call-controller interface. The dialer component declares `IN_CALL_SERVICE_UI=true` and `BIND_INCALL_SERVICE` protection and presents gateway status or recovery rather than a normal dialer UI. Matching Android 11 PermissionController requires only the two default-category dial-intent activity handlers for role qualification; Telecom separately requires the UI-declared service for call control. Prevent duplicate callbacks or duplicate bridge ownership if both components are bound during transitions.

Implement the initial gateway/recovery phase:

- An exported, enabled gateway/recovery activity with two `ACTION_DIAL` filters, both containing `CATEGORY_DEFAULT`: one without a data scheme and one with `tel`. These declarations satisfy the audited qualifier. Number entry and manual call placement are deferred to the fallback phase below.
- Default-dialer selection via `RoleManager.ROLE_DIALER` throughout the API 30+ target range. Pre-29 selection is deferred with the older port.
- A minimal gateway status/recovery screen and appropriate notifications for Telecom callbacks. Manual answer/reject, number entry, and a DTMF keypad are deferred; custom call-route controls remain outside the planned minimal scope.
- A readiness gate: do not start or answer new gateway calls while SIP is disconnected, takeover is off, or the privileged audio dependency is unavailable. Show the reason and a prominent "Switch back to your usual dialer" action, using the release-appropriate system selector/settings with a fallback explanation.
- Re-evaluate readiness on SIP connection changes. If SIP is lost during a bridged call, use the bridge's teardown/error handling, restore audio state, and display recovery. Reconnection can restore gateway readiness without changing the selected dialer.
- For calls or dial intents outside gateway operation, show the recovery action rather than providing ordinary dialer functionality. Preserve emergency-number rejection for PBX-originated calls and the platform's emergency-call handling; this does not require a new manual emergency-dialing UI.
- Detection of role loss and component binding changes. Validate role/component configuration while idle and actual binding/callbacks during a call; do not block the first call merely because Telecom has not yet bound the service.

Default-dialer mode must not depend on companion app-ops or the companion shell command. Companion mode retains its existing prerequisites where verified. On the unmodified audited API 30 baseline, do not attempt companion setup, repeatedly add overrides, or set the absent `MANAGE_ONGOING_CALLS` app-op. Command success and an override entry do not prove functional service binding.

Change takeover semantics: while the app holds the dialer role, turning bridging off stops the bridge/SIP takeover and puts the initial implementation into recovery state. Keep the role-required service enabled so it can report unavailability and present the switch-dialer action. Restoring another dialer is a separate explicit system selection; never change the role silently. The deferred fallback phase would also keep manual-call handling available while takeover is off, without restarting SIP or opening an audio tap.

Apply app-controlled call-access changes only when no Telecom call is active. Defer backend changes to the next call. Keep recovery navigation available and handle user-driven role changes reported by the system. Avoid switching services, rebuilding a bridge, or releasing an active tap as an incidental consequence of saving unrelated settings.

#### Dialer scope and phased fallback

Build gateway operation with recovery first. Add the manual-call fallback as a separate follow-up once the older audio backend works in both directions on a physical phone. The fallback is useful when the app replaces the default dialer and SIP or Shizuku is unavailable, but it must not delay proving the gateway path. The initial dialer is not a general-purpose replacement phone app.

**Work shared by both phases:**

- Track asynchronous Telecom call state and service reconnection/process restart. Reconcile existing calls when callbacks resume; do not assume the screen's previous state still describes a live call.
- Present incoming/ongoing gateway status and recovery through suitable notifications and screens, including background and lock-screen behavior. Integrate with platform ringing, audio routing, and emergency handling rather than implementing replacements.
- Handle role loss, service/component changes, and readiness transitions. Keep dialer components and the switch-dialer recovery action usable while selected, including with takeover off.
- Make call ownership explicit. Bridge only gateway-owned calls, prevent duplicate handling by multiple services, and track additional or out-of-scope calls even without advanced controls. During an existing bridge, admit no second gateway call; surface other call state and recovery without silently answering, muting, tapping, or disconnecting calls the gateway does not own.

**Initial gateway/recovery phase:** show gateway state, failure reasons, and the switch-dialer action. Route both dial-intent forms to that screen. Refuse new gateway operations when dependencies are unavailable; use the existing bridge teardown and audio restoration for failures during a bridged call. Manual-call controls and number entry are not part of this phase. Role qualification on the audited Android 11 tag does not require those features, but obtaining the role also does not provide an automatic full dialer UI when our service successfully binds.

**Deferred minimal manual-call fallback:**

- Show incoming caller number with answer/reject controls, and an ongoing-call screen with call state and hang-up. Restore this view from current Telecom calls after reconnection.
- Accept a number from a dial intent or simple number-entry screen and offer an explicit "Call using this phone" action. Use a separate manual Telecom placement path; do not send this action through the SIP gateway. Require user confirmation before placing the call. On multi-SIM devices, offer account selection or clearly scope the first implementation to a configured phone account; do not choose an arbitrary SIM.
- Provide an in-call DTMF keypad for voicemail and telephone menus, including stopping a tone when the key is released or the call ends.
- Keep manual calls on normal handset audio and independent of SIP, Shizuku, and audio-permission hooks. Mark them as manual before they become eligible for bridge ownership; gateway reconnection must not claim them, mute their microphone, or open a tap. Use the shared controller and serialized ownership transitions to prevent manual controls and the bridge from acting on the same call concurrently.
- Retain the recovery/switch-dialer action. Continue to delegate emergency behavior to Telecom and the system dialer; do not apply the PBX-specific emergency rejection rule indiscriminately to user-initiated phone calls.

Fallback applies to new or already unbridged calls. Converting an active SIP bridge into a manual handset call, automatic answering when SIP fails, and sophisticated call waiting/hold/switching are outside this follow-up. Handle extra calls explicitly and keep ownership stable; advanced multi-call features may be omitted, but extra callbacks must not be ignored or sent to the wrong call.

Leave out contacts browsing, call history, favorites/search/speed dial, recording/transcription, voicemail integration, caller-identification services, conferencing, transfer, video calling, custom Bluetooth routing, and custom ringtone handling. The system supplies the underlying call and audio machinery; reliable lifecycle integration and gateway/manual ownership account for most of this feature's work, rather than drawing the dial pad.

Validate the first phase's incoming/background/lock-screen presentation, role changes, process/service recovery, extra-call behavior, unavailable dependencies, and normal audio restoration. For the later phase, add manual incoming/outgoing calls and DTMF tests with SIP disconnected, Shizuku unavailable, and takeover off. Verify that SIP reconnection leaves manual calls alone and that no bridge or audio tap is created for them. Include configured-SIM/account behavior and return to ordinary handset audio.

### 5. Remove other runtime barriers below API 34

Audit direct Android and Java platform API calls for the API 30 target and add only the compatibility branches needed for API 30–33:

- Special-use foreground-service handling from API 34; retain the supported foreground-service APIs/types on older targets.
- Notification permission grants from API 33 and other runtime permissions only where defined and requested.
- Context device identity and attribution only on versions that support them.
- Hidden-API access: verify each required reflective entry point in the actual Shizuku helper and app process. A permitted attribution adapter does not confer missing audio permissions. Do not assume the shell package's hidden-API treatment automatically applies to code loaded in an app_process helper.
- Version-appropriate gateway/recovery notifications and navigation to the system dialer selector/settings.

The full pre-30 construction, class-loading, service/notification/process, network, emergency-check, and dialer-selection details are retained under [older Android compatibility](#older-android-construction-and-runtime-compatibility-deferred). They are deferred with API 23–29 support.

Audit the selected AndroidX AAR manifests and Java library API calls. Pin compatible dependencies or enable appropriate desugaring where needed. Desugaring is not a replacement for Android framework API guards.

### 6. Lower module and native build minimums

After the audio feasibility decision, set the chosen minimum consistently in `:app`, `:telephony`, `:privileged`, and `:sip-pjsua2`.

Rebuild PJSIP, OpenSSL, Opus, and the SWIG/JNI wrapper for that minimum; the native build now defaults to API 30. Check native imports and loading on the oldest supported release. Preserve the current null sound device, format agreement, codec setup, release shrinking rules, and JNI/reflection entry points.

Retain arm64-only packaging initially if the test phone supports it. Add an armeabi-v7a build only if intended older devices require 32-bit support; keep ABI scope explicit.

### 7. Wire Advanced settings and migrations

Persist call-access mode, audio backend, and handset-silencing preference in `SettingsStore`. Map existing `audio_method` values to the new audio choices without changing existing explicit selections. Preserve existing SIP credentials and media settings.

Update `MainActivity`, `GatewayRuntime`, `HealthKeys`, `AdbControlReceiver`, and setup/health responses together. Readiness must depend on the selected effective integration instead of the current hard-coded companion conditions.

Expose supported choices on newer versions too. Show a short reason when a choice is unavailable. Basic setup should offer required actions without requiring the user to discover Advanced. Keep requested/effective settings visible in diagnostic output.

Do not automatically reclaim the dialer role during periodic health checks. Avoid continuously retrying an unavailable API or an unsupported companion command.

### 8. Verify behavior and define the support claim

Run the existing SIP/gateway unit tests and add tests for capability selection, explicit-versus-automatic fallback, role loss, deferred setting changes, settings migration, and recovery when SIP is disconnected or takeover is off while holding the dialer role. Verify that unavailable gateway operation never answers a new call or starts a tap and that the switch-dialer action remains accessible.

Cover framework-helper preference, attribution-compatible manual redirection, explicit direct selection skipping helpers, explicit redirection refusing a direct fallback, partial-construction cleanup, and loss/cleanup when the audio mode becomes incompatible. Check requested/effective construction reporting and preservation of the PCM format across automatic fallback. Exercise these through the production implementation and existing tests; no separate demo is required.

Build and lint debug/release APKs. Test actual runtime class loading and native library loading on the minimum release. Test the release APK because shrinking can affect reflection and Shizuku/SWIG entry points.

Use emulators for compatibility, role qualification, and gateway/recovery screens where useful, and real phones for cellular audio. Cover:

| Android versions | Main checks |
| --- | --- |
| API 30 provisional stock unrooted minimum | Native capture/injection and Telecom audits are complete. Verify actual grants, context-free construction/attribution, dialer-role qualification and UI-service callbacks, native loading, voice-call stream mute/restoration, and physical two-way audio. Companion mode and modern redirection are unavailable on the audited tag. |
| API 31-32 | RoleManager selection, record context setter from API 31, context-free track attribution, and release-specific companion capabilities. |
| API 33 | Modern PSTN redirection and optional handset suppression; the track still requires default process attribution. |
| API 34+ | Track context setter from API 34, companion and dialer choices, direct and modern audio choices, current foreground-service rules. |

On physical devices verify inbound/outbound gateway calls, remote receipt of injected audio, downlink audio, mute behavior, SIP-disconnected refusal, out-of-scope dial-intent recovery, takeover off, switching back to another dialer, dialer-role loss, Shizuku death, call termination, network reconnection, and return to normal handset routing. Preserve the existing call-clock behavior.

Publish the minimum installable Android version separately from tested devices/audio backends. Object creation, device selection, playback state, and frame counters alone do not establish successful cellular audio.

## Suggested reviewable change groups

1. API 30 construction, backend selection, and capability reporting in the existing privileged implementation, followed by app-based verification.
2. Existing health/open-result updates, small compatibility implementations behind current interfaces, ownership/cleanup fixes where needed, and legacy audio backend.
3. Default-dialer service, gateway/recovery screen, and mode-specific lifecycle/readiness.
4. Runtime compatibility branches, dependency adjustments, and native rebuild.
5. Advanced settings, migration, and diagnostics.
6. Physical-device results, regression fixes, and updated support documentation.

Deferred follow-ups: API 23–29 support and any Xposed integration. The minimal manual-call fallback is also separate, with explicit ownership, native handset audio, number entry, incoming/ongoing controls, and DTMF; review and validate it after the gateway/recovery implementation and two-way audio checks pass.

The [direct privileged implementation](#optional-direct-privileged-operation) is another separate follow-up after the stock compatibility, gateway/recovery dialer, and Advanced settings work: reuse the current interfaces in the same APK/process, then verify LineageOS/Magisk permission/installation configuration, call access, cleanup, and physical audio. A [future process or APK split](#future-process-or-apk-split) is considered only for a demonstrated deployment need. Neither changes the stock deployment's minimum SDK claim.

## Remaining evidence

Prioritize helper execution, call-control callbacks, native loading, and physical two-way audio through the implemented API 30 app. Framework, native audio, Telecom, and matching PermissionController role definitions/qualification code are audited for the supplied Android 11 tag; another framework clone is not needed for that source gate. The supplied `UserService.java` establishes gateway-package application attribution for its bootstrap path; the implementation includes the helper-local operation-package adapter above. An initial Android 11 framework baseline can separately narrow the stock permission-grant release boundary if needed. Android 7–9 native audio/Telecom remain unaudited; filling that gap is deferred with the older port. Exact audited tags and repository coverage are listed in [README.md](README.md).
