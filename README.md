# Voice SIP Agent

[中文说明](README.zh.md)

Turn an **unrooted** Android phone into a single-line **cellular ↔ SIP gateway**. The phone registers to your PBX
(Asterisk / FreePBX / IssabelPBX) as a SIP trunk:

- **Cellular → SIP**: an incoming cellular call rings a DID on the PBX; the cellular call is answered only once
  someone picks up on the SIP side, and the caller ID is passed in `P-Asserted-Identity`.
- **SIP → cellular**: the PBX sends a call to the trunk and the phone places it on the cellular network.
- Call audio is captured and injected with Android's call-redirection APIs, so the phone's own earpiece and
  microphone can stay silent while a call is bridged.

Privileged operations (call-audio interception, the Telecom companion override, appops) are performed through
[Shizuku](https://shizuku.rikka.app/) running as the shell user (uid 2000) — **no root required**. This relies on
hidden platform APIs, which is also why the app is distributed through GitHub Releases rather than Google Play.

> [!WARNING]
> This is a hobby project built on undocumented Android behaviour. It may stop working with any OS update, may
> drop or misroute calls, and is provided **as is, without warranty of any kind**. Read the
> [Disclaimer](#disclaimer) before using it.

## Status

Tested on:

| Device | Android | Notes |
|---|---|---|
| Google Pixel 11 Pro | 17 | Main development device |
| Motorola | 16 | Works |

Verified: inbound and outbound calls, two-way audio, DTMF (RFC 4733), Wi-Fi reconnect (also mid-call), overnight
Doze standby, long calls without clock drift, handset muting, LAN calls over UDP and public calls over TLS + SRTP
through a Flexisip proxy.

Not yet verified: call waiting, DTMF and network-loss recovery with the pjsua2 stack, public calls over cellular
data only.

The default pjsua2 SIP stack prefers mono Opus (16 kHz PCM, 20 ms frames, 20 kbit/s target), with G.711
(PCMU/PCMA) fallback. Android capture/injection and the bridge use 16 kHz mono PCM for pjsua2, and 8 kHz
for the built-in Kotlin/G.711 stack. Each 20 ms socket packet is respectively 640 or 320 bytes. PJMEDIA
resamples between the 16 kHz bridge and G.711. There is no automatic 16-to-8 kHz fallback: Android
open/start/read/write failures are reported and terminate the tap. A 16 kHz stream can still contain
upsampled narrowband audio, depending on the cellular call and device. Opus uses the standard
`opus/48000/2` SDP mapping with `maxplaybackrate=16000` and `sprop-maxcapturerate=16000`. G.722 is disabled.
Opus VAD/DTX is enabled by default to reduce cellular-to-SIP silence payload bandwidth; SDP advertises
`usedtx=1` to request DTX from the SIP peer too. The peer's negotiated DTX preference is honored.
PCMU/PCMA keep VAD disabled and transmit continuously. PCM capture and bridging continue every 20 ms.
Quiet speech, call-progress tones, hold music and prolonged silence with PBX/NAT timeouts still need
device testing. Acoustic echo cancellation belongs at the microphone endpoint.
The 16 kHz tap and Opus calls have not yet been verified on a device.

Limitations: one call at a time; arm64-v8a only; requires Android 14+
(minSdk 34), but only the devices above have been tested — hidden APIs may behave differently elsewhere.
Emergency numbers are always refused and are never dialed through the gateway.

## Installation

1. Install and start [Shizuku](https://shizuku.rikka.app/guide/setup/) (wireless debugging or adb). Shizuku must
   be started again by hand after every reboot.
2. Download `voice-sip-agent-<version>.apk` from [Releases](../../releases) and install it.
   Verify the signing certificate if you like:

   ```bash
   apksigner verify --print-certs voice-sip-agent-0.1.0.apk
   ```

   The SHA-256 digest of the release signing certificate is
   `b8fe38aa02f9657c6785fb6faeb20abb20deba410f9c643fcd93269b642850c4`.
3. Open the app, tap **Grant Shizuku** and allow access. The app then runs its setup automatically (appops,
   Telecom companion override where applicable, Doze allowlist, runtime permissions) and repeats it after upgrades.
   A health check runs every 60 s and retries setup while idle if takeover is enabled and companion call access is unavailable.
   On Android 11, use the visible **Select as default dialer** setup action to grant call access.
   The app never requests this role automatically. On newer versions, Automatic call access keeps companion
   integration unless the app already holds the dialer role; Advanced settings can select either mode explicitly.
4. Fill in **Settings** (PBX host/port, transport, SRTP, user, password, …) and tap **Save & apply**.
5. **Take over calls** is the master switch. Switching it off waits for current calls to end, then stops bridging
   and companion integration. If this app is the default dialer, its recovery screen stays available;
   use **Switch dialer** to return normal call handling to your usual dialer.

Advanced settings are collapsed by default. They contain call-access and cellular-audio choices, automatic
handset suppression when supported, loopback, logging, and diagnostics. Direct telephony audio does not use
the modern redirection helpers. Direct telephony audio uses voice-call stream mute for handset output,
including on API 30–32; supported redirection taps retain mode-based suppression. The helper restores its
changes when the tap closes and preserves an already-muted stream. Device policy/HAL support and both
audio directions still need testing with suppression enabled. If the helper is forcibly killed, stream
mute can remain active; raising the call volume can unmute it.

For local listening, turn **Mute local call output** off in **Advanced settings** before the call.
Downlink output follows the phone's current call audio route (earpiece, speaker, or headset) and volume.
The handset microphone stays muted while bridging, so local listening does not enable local speech.

## Configuration

| Setting | Meaning |
|---|---|
| SIP stack | `pjsua2` (default; UDP/TCP/TLS, SRTP) or `builtin` (pure Kotlin, UDP only) |
| PBX host / port | Registrar / proxy address |
| Transport | `udp` (LAN), `tcp` or `tls` (pjsua2 only) |
| SRTP | `off`, `optional` (offer SDES, fall back to RTP) or `mandatory` (488 otherwise); only effective with TLS |
| SIP domain | Defaults to the PBX host |
| User / password | Trunk credentials on the PBX |
| Cellular inbound → SIP target | DID dialed on the PBX for incoming cellular calls; blank = the SIP user name |
| Allowed dial-out prefixes | Comma-separated; empty = any number may be dialed out |
| Silence handset | Keeps the phone's earpiece and microphone off while a call is bridged |

TLS validates the server certificate chain and host name against the Android system CA store (TLS 1.2/1.3 only)
and accepts `*.` wildcard certificates in the left-most label (RFC 6125). There is no downgrade: if TLS fails,
registration fails and is retried every 30 s.

### adb control

The app can also be driven over adb (the receiver requires the `DUMP` permission, which only the shell user has):

```bash
R=io.github.sparkcyf.voicesipagent/io.github.sparkcyf.voicesipagent.app.AdbControlReceiver
adb shell am broadcast -n $R --es op provision --es server sip.example.org --ei port 5061 \
  --es transport tls --es srtp optional --es domain sip.example.org \
  --es user android-gw --es password "'<secret>'" --es target android-gw   # only overwrites the given fields
adb shell am broadcast -n $R --es op status            # printed to logcat
adb shell am broadcast -n $R --es op takeover --ez on false
adb shell am broadcast -n $R --es op earpiece-mute --ez on true
adb shell am broadcast -n $R --es op sip-stack --es stack pjsua2
adb shell am broadcast -n $R --es op loopback --ez on true   # echo incoming cellular calls (test mode)
adb shell am broadcast -n $R --es op sip-debug --ez on true  # verbose pjsip logging
adb logcat -s VoiceSipAgent VsaPrivileged VsaTap
```

Phone numbers are logged with only their last two digits; call audio is never logged or stored.

## Call routing

The gateway joins the PBX as a **trunk**, not an extension; all routing lives in the PBX's inbound/outbound
routes.

- **Cellular inbound**: the phone sends `INVITE sip:<target>@<domain>`, where `target` is a DID (by default the
  trunk's own user name), with the caller number in `P-Asserted-Identity`. An Inbound Route on the PBX sends the
  DID wherever you like.
- **SIP outbound**: the number to dial is taken from the `X-VSA-Dial` header, falling back to the Request-URI user
  part (`PJSIP/$OUTNUM$@<trunk>` on a direct LAN connection). The PBX gets 200 OK once the cellular call is active.
  - With a registrar-routing proxy in between (Flexisip etc.), the Request-URI user part must stay the gateway's
    own AoR, otherwise the proxy looks up the number in its registrar and answers 404 — so the number goes in
    `X-VSA-Dial` (see the dial plan below).

### PBX example (FreePBX / IssabelPBX with chan_pjsip)

`pjsip_custom_post.conf`:

```ini
[android-gw]
type=endpoint
transport=transport-udp
context=from-trunk          ; inbound calls go through Inbound Routes, no internal-extension privileges
aors=android-gw
auth=android-gw-auth
identify_by=username
disallow=all
allow=ulaw,alaw
dtmf_mode=rfc4733
direct_media=no
rtp_symmetric=yes
force_rport=yes
rewrite_contact=no          ; behind Flexisip the proxy already rewrote the Contact
media_encryption=sdes       ; pairs with SRTP "optional"; plain RTP still works on a LAN
media_encryption_optimistic=yes
trust_id_inbound=yes        ; trust the gateway's P-Asserted-Identity as caller ID

[android-gw-auth]
type=auth
auth_type=userpass
username=android-gw
password=<secret>

[android-gw]
type=aor
max_contacts=1
remove_existing=yes         ; take over the registration as soon as the phone changes IP/port
qualify_frequency=60
```

`extensions_custom.conf` (keeps the Request-URI on the gateway's AoR and puts the number in a header):

```ini
[vsa-android-gw-out]
exten => _X.,1,Dial(PJSIP/android-gw,,b(vsa-android-gw-hdr^s^1(${EXTEN})))
exten => _X.,n,Hangup(${HANGUPCAUSE})

[vsa-android-gw-hdr]
exten => s,1,Set(PJSIP_HEADER(add,X-VSA-Dial)=${ARG1})
exten => s,n,Return()
```

Web UI:

- **Trunk**: Custom, dial string `Local/$OUTNUM$@vsa-android-gw-out` (on a direct LAN connection without a proxy,
  `PJSIP/$OUTNUM$@android-gw` also works).
- **Outbound Route**: a dedicated prefix (e.g. `9#7#`) with pattern `X.` using that trunk; it must be ordered
  before broader routes such as `9.`.
- **Inbound Route**: DID `android-gw` → the destination of your choice.

### Reaching the PBX over the internet: Flexisip

The phone registers over TLS to [Flexisip](https://www.linphone.org/en/flexisip-sip-server/)
(`sips:<domain>:5061`), which forwards requests to Asterisk (e.g. over WireGuard) and relays media with
MediaRelay (SRTP stays end-to-end between the phone and Asterisk). Flexisip needs no gateway-specific
configuration:

- REGISTER is authenticated by Asterisk (Flexisip authentication off, `reg-on-response=true`).
  `ContactRouteInserter` rewrites the Contact to point at Flexisip and NatHelper fixes the NAT address, so incoming
  calls arrive over the TLS connection the phone already opened — no open ports on the phone.
- Requests from Asterisk are routed by Request-URI user part, hence the `X-VSA-Dial` dial plan above.
- Phone settings: `server = domain = <flexisip domain>`, `port = 5061`, `transport = tls`, `srtp = optional`.

## Building from source

Requirements: JDK 17+, Android SDK with NDK, `swig` (e.g. `brew install swig`).

```bash
sip-pjsua2/native/build-pjsua2.sh   # once: OpenSSL + libopus + pjproject for arm64-v8a, into git-ignored src/generated/
./gradlew :sip:test :gateway:test   # JVM unit tests
./gradlew :app:assembleDebug
```

The native build script downloads pinned, SHA-256-verified upstream tarballs and applies the patches in
`sip-pjsua2/native/`.

Tarballs and shared library source trees live directly under
`sip-pjsua2/build/pjproject/` (override with `PJ_WORK`). Each ABI has `openssl-build/`, `opus-build/`,
`prefix/{openssl,opus}/` and its own PJSIP source/build tree, since PJSIP generates target-specific
configuration and headers inside its source tree. Set, for example,
`ABIS="arm64-v8a x86_64"` to build multiple targets sequentially; APK packaging still follows Gradle's ABI filters.
Each requested ABI directory is recreated and rebuilt from scratch, including dependency installations,
PJSIP patches and JNI output. Shared dependency sources and verified tarballs are retained; there are no
build fingerprints or incremental object caches. New bindings are staged and published only after all
requested ABIs succeed, preserving the previous output on a build failure. Do not run builds sharing the
same work/output directories concurrently.

Native C/C++ code uses ThinLTO, hidden visibility and section garbage collection. The final export map
exposes only PJSUA2 JNI entry points; OpenSSL, Opus and PJSIP symbols are local. The build checks exports
and writes `pjsua2.exports.txt` under the ABI work directory. Exported JNI wrappers and OpenSSL's internal
algorithm registries still retain reachable code; Java shrinking alone does not remove native wrappers.
Release builds enable R8 with consumer rules preserving SWIG bindings/director callbacks and Shizuku's
reflectively loaded service constructor. Other app and dependency code can be optimized. Validate a release
APK's Shizuku startup, SIP callbacks and TLS/SRTP audio before relying on new shrinking rules.

Release builds are signed only when `vsa.signing.storeFile`, `vsa.signing.storePassword`, `vsa.signing.keyAlias`
and `vsa.signing.keyPassword` are set in `~/.gradle/gradle.properties`; otherwise `assembleRelease` produces an
unsigned APK.

## Architecture

| Module | Type | Responsibility |
|---|---|---|
| `:voice-api` | JVM | Contract: `CallLeg` / `CallEndpoint` / `MediaPort` (8/16 kHz mono PCM16, 20 ms frames) / `HangupCause` |
| `:sip` | JVM | Built-in SIP UA: UDP, transactions, digest auth, REGISTER, INVITE/CANCEL/BYE/re-INVITE, SDP, RTP + jitter buffer, G.711, RFC 4733 |
| `:gateway` | JVM | `CallBridge` state machine (bridges any two `CallEndpoint`s), `MediaBridge` (single clock domain), `EchoEndpoint` |
| `:telephony` | Android | Companion `InCallService` + `CellularEndpoint`: Telecom calls as `CallLeg`s |
| `:privileged` | Android | Shizuku UserService (AIDL): setup/health, call-audio tap over a SOCK_SEQPACKET socketpair |
| `:sip-pjsua2` | Android | pjsua2-backed `CallEndpoint`: null sound device + `AudioMediaPort`, pjsua never touches Android audio |
| `:app` | Android | Runtime wiring, foreground service, settings (Keystore-sealed password), adb control, Compose UI |

To plug in another voice/control backend, implement a `CallEndpoint` (with its `CallLeg` / `MediaPort`) and hand it
to `CallBridge`; the gateway core does not change. See [AGENT.md](AGENT.md) for design notes and the pitfalls
found along the way.

## References

- [AirSIM](https://github.com/kai-wu-cortex/AirSIM) — reading its code was the starting point for the call-audio
  interception approach; this project is an independent implementation and does not depend on it.
- [Shizuku](https://github.com/RikkaApps/Shizuku), [pjsip](https://github.com/pjsip/pjproject),
  [Flexisip](https://gitlab.linphone.org/BC/public/flexisip).

## License

Copyright (C) 2026 sparkcyf

This program is free software; you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation; either version 2 of the License, or (at your option) any later version. See [LICENSE](LICENSE). As a special exception, linking with any library licensed under the Apache License, Version 2.0 is permitted, provided all other code complies with the GPL.

Release APKs statically link pjsip (GPL-2.0-or-later), OpenSSL (Apache-2.0) and libopus (BSD-3-Clause); see
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). The corresponding source for every release is the tagged commit
of this repository plus the pinned upstream tarballs fetched by `sip-pjsua2/native/build-pjsua2.sh`.

## Disclaimer

THIS SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO
THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,
TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE. You use it entirely at your own risk.

In particular:

- **Not for emergency calls.** Emergency numbers are refused by design. Do not rely on this gateway, or on a phone
  running it, to reach emergency services.
- **Calls may fail.** Calls can be dropped, rejected, misrouted or answered without audio, and an OS update can
  break the app at any time. Missed calls and their consequences are your responsibility.
- **Charges.** Calls placed through the gateway are billed by your carrier. Restrict dial-out prefixes and secure
  your PBX; a compromised PBX can make the phone dial premium-rate numbers.
- **Laws and terms of service.** Relaying, recording or intercepting calls, and connecting a mobile line to a PBX,
  may be restricted by law or by your carrier's terms in your jurisdiction. You are solely responsible for
  complying with them and for obtaining any consent required from the parties to a call.
- **Security.** Shizuku grants this app shell-level privileges. Only install builds you trust, and keep your SIP
  credentials and PBX secure.

This project is not affiliated with or endorsed by Google, Motorola, the Shizuku, pjsip, Asterisk or Flexisip
projects, or any carrier.
