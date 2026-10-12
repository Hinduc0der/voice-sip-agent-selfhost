# Voice SIP Agent

[English](README.md)

把一台**未 root** 的 Android 手机变成单线 **蜂窝 ↔ SIP 网关**。手机以 SIP trunk 的方式注册到你的 PBX
（Asterisk / FreePBX / IssabelPBX）：

- **蜂窝来电 → SIP**：蜂窝来电会呼叫 PBX 上的一个 DID；SIP 侧有人接听后才接听蜂窝来电，主叫号码通过
  `P-Asserted-Identity` 传递。
- **SIP 去电 → 蜂窝**：PBX 把呼叫送到 trunk，由手机经蜂窝网络拨出。
- 通话音频通过 Android 的通话重定向 API 截取和注入，桥接期间手机自己的听筒和麦克风可以保持静音。

特权操作（通话音频拦截、Telecom 伴侣覆盖、appops）通过以 shell 用户（uid 2000）运行的
[Shizuku](https://shizuku.rikka.app/) 完成，**不需要 root**。项目依赖隐藏的系统 API，这也是它只在 GitHub Releases
发布、不上 Google Play 的原因。

> [!WARNING]
> 这是一个基于未公开 Android 行为的个人项目，任何一次系统更新都可能让它失效，也可能掉话或把呼叫送错地方。
> 本软件**按原样提供，不附带任何形式的担保**。使用前请阅读[免责声明](#免责声明)。

## 状态

测试过的设备：

| 设备 | Android | 备注 |
|---|---|---|
| Google Pixel 11 Pro | 17 | 主力开发机 |
| Motorola | 16 | 正常 |

已验证：来电与去电、双向语音、DTMF（RFC 4733）、Wi-Fi 断线重连（含通话中）、Doze 一整晚待机、长通话无时钟漂移、
本机听筒静音；局域网 UDP 通话，以及经 Flexisip 代理的公网 TLS + SRTP 通话。

尚未验证：通话等待、pjsua2 栈下的 DTMF 与断网恢复、仅用蜂窝数据的公网通话。

默认 pjsua2 栈优先使用单声道 Opus（16 kHz PCM、20 ms 帧、目标码率 20 kbit/s），并保留 G.711
（PCMU/PCMA）作为回退。pjsua2 的 Android 采集、注入和音频桥使用 16 kHz 单声道 PCM；内置 Kotlin/G.711
栈使用 8 kHz。每个 20 ms socket 数据包分别为 640 或 320 字节。PJMEDIA 在 16 kHz 音频桥和 G.711 之间
重采样。不自动从 16 kHz 回退到 8 kHz：Android 打开、启动、读写失败会报错并关闭音频通道。
16 kHz 流仍可能是窄带音频的上采样，取决于蜂窝通话和设备。SDP 使用标准的 `opus/48000/2`，并声明 `maxplaybackrate=16000` 和
`sprop-maxcapturerate=16000`。G.722 已禁用。Opus 默认启用 VAD/DTX，减少蜂窝到 SIP 方向静音时的
音频载荷带宽；SDP 声明 `usedtx=1`，同时请求 SIP 对端使用 DTX，并遵循对端协商的 DTX 偏好。
PCMU/PCMA 保持 VAD 关闭并连续发送。PCM 采集和音频桥仍每 20 ms 运行一次。
轻声语音、呼叫提示音、等待音乐以及长时间静音时的 PBX/NAT 超时行为仍需在设备上验证。
声学回声消除应由实际使用麦克风的客户端完成。
16 kHz 音频通道和 Opus 通话尚未在设备上验证。

限制：同一时间只有一路通话；APK 包含 arm64-v8a 和 x86_64；最低为 Android 11（minSdk 30）。Android 11–13 的兼容性和双向蜂窝通话音频尚未经过真机验证，
仅在上表设备上测试过，其他机型的隐藏 API 和调制解调器路由可能不同。
默认拨号器模式提供网关状态和恢复界面，可通过切换拨号器恢复手动通话。紧急号码一律拒绝，绝不会经网关拨出。

## 安装

1. 安装并启动 [Shizuku](https://shizuku.rikka.app/zh-hans/guide/setup/)（无线调试或 adb）。每次重启后都需要手动重新启动 Shizuku。
2. 从 [Releases](../../releases) 下载 `voice-sip-agent-<版本>.apk` 并安装。可以校验签名证书：

   ```bash
   apksigner verify --print-certs voice-sip-agent-0.1.0.apk
   ```

   正式版签名证书的 SHA-256 为 `b8fe38aa02f9657c6785fb6faeb20abb20deba410f9c643fcd93269b642850c4`。
3. 打开 App，点 **Grant Shizuku** 并允许。App 会自动执行 setup（appops、适用时的 Telecom 伴侣覆盖、Doze 白名单、运行时权限），
   升级后会重新执行。每 60 s 检查一次健康状态；开启接管且伴侣模式的通话访问不可用时，会在空闲期间重试 setup。
   Android 11 需要通过主界面的 **Select as default dialer** 操作取得通话访问权限；App 不会自动请求该角色。
   较新版本的自动通话访问模式默认使用伴侣集成，已持有默认拨号器角色时使用拨号器集成；也可在高级设置中显式选择。
4. 在 **Settings** 中填写 PBX 地址/端口、传输方式、SRTP、用户名、密码等，点 **Save & apply**。
5. **Take over calls** 是总开关。关闭时会等待当前通话结束，再停止桥接和伴侣集成。
   若本 App 仍为默认拨号器，恢复界面会继续可用；通过 **Switch dialer** 切回惯用拨号器，恢复普通通话处理。

高级设置默认折叠。直接电话音频模式会通过通话音频流静音来抑制本机输出，也适用于 API 30–32；支持重定向的
音频通道仍使用音频模式切换。关闭音频通道时会恢复本应用的修改，原本已经静音的音频流会保持不变。
开启后仍需在设备上验证本机静音和双向音频。若特权进程被强制终止，通话音频流可能保持静音；提高通话音量可解除。

若要在手机旁监听，在通话开始前到 **Advanced settings** 关闭 **Mute local call output**。
下行音频按当前通话路由（听筒、扬声器或耳机）和音量播放。桥接期间本机麦克风仍保持静音，因此本地监听不会启用本机语音输入。

## 配置

| 设置项 | 含义 |
|---|---|
| SIP stack | `pjsua2`（默认；UDP/TCP/TLS、SRTP）或 `builtin`（纯 Kotlin，仅 UDP） |
| PBX host / port | 注册服务器 / 代理地址 |
| Transport | `udp`（局域网）、`tcp` 或 `tls`（仅 pjsua2） |
| SRTP | `off`、`optional`（提供 SDES，对端不支持时退回 RTP）或 `mandatory`（不支持则 488）；仅在 TLS 下生效 |
| SIP domain | 默认等于 PBX host |
| User / password | PBX 上 trunk 的凭据 |
| Cellular inbound → SIP target | 蜂窝来电时在 PBX 上呼叫的 DID；留空则使用 SIP 用户名 |
| Allowed dial-out prefixes | 逗号分隔；留空表示允许拨出任何号码 |
| Silence handset | 桥接期间保持本机听筒和麦克风关闭 |

TLS 用 Android 系统 CA 库校验服务器证书链和主机名，仅支持 TLS 1.2/1.3，接受最左一级的 `*.` 通配证书（RFC 6125）。
不做降级：TLS 失败就注册失败，每 30 s 重试。

### adb 控制

也可以用 adb 控制 App（receiver 需要 `DUMP` 权限，只有 shell 用户具备）：

```bash
R=io.github.sparkcyf.voicesipagent/io.github.sparkcyf.voicesipagent.app.AdbControlReceiver
adb shell am broadcast -n $R --es op provision --es server sip.example.org --ei port 5061 \
  --es transport tls --es srtp optional --es domain sip.example.org \
  --es user android-gw --es password "'<secret>'" --es target android-gw   # 只覆盖给出的字段
adb shell am broadcast -n $R --es op status            # 输出到 logcat
adb shell am broadcast -n $R --es op takeover --ez on false
adb shell am broadcast -n $R --es op earpiece-mute --ez on true
adb shell am broadcast -n $R --es op sip-stack --es stack pjsua2
adb shell am broadcast -n $R --es op loopback --ez on true   # 回声测试：蜂窝来电直接回声
adb shell am broadcast -n $R --es op sip-debug --ez on true  # pjsip 详细日志
adb logcat -s VoiceSipAgent VsaPrivileged VsaTap
```

日志中的电话号码只保留后两位；通话音频不会写入日志，也不会落盘。

## 呼叫路由

网关以 **trunk** 而不是分机的身份接入 PBX，所有路由都在 PBX 的 Inbound/Outbound Routes 中管理。

- **蜂窝来电**：手机发送 `INVITE sip:<target>@<domain>`，`target` 是一个 DID（默认就是 trunk 的用户名），主叫号码放在
  `P-Asserted-Identity` 中。PBX 上的 Inbound Route 按 DID 把来电送到你想要的地方。
- **SIP 去电**：要拨的号码取自 `X-VSA-Dial` 头，没有该头时取 Request-URI 的用户部分（局域网直连时用
  `PJSIP/$OUTNUM$@<trunk>` 即可）。蜂窝通话接通后才向 PBX 回 200 OK。
  - 中间隔着按注册表路由的代理（Flexisip 等）时，Request-URI 的用户部分必须保持为网关自己的 AoR，否则代理会按号码查注册表并返回
    404，所以号码要放进 `X-VSA-Dial`（见下方拨号方案）。

### PBX 配置示例（FreePBX / IssabelPBX + chan_pjsip）

`pjsip_custom_post.conf`：

```ini
[android-gw]
type=endpoint
transport=transport-udp
context=from-trunk          ; 来电按 Inbound Route 处理，不具备内部分机权限
aors=android-gw
auth=android-gw-auth
identify_by=username
disallow=all
allow=ulaw,alaw
dtmf_mode=rfc4733
direct_media=no
rtp_symmetric=yes
force_rport=yes
rewrite_contact=no          ; 经 Flexisip 时 Contact 已由代理改写，不能再改
media_encryption=sdes       ; 与 SRTP optional 配合；局域网明文 RTP 照常
media_encryption_optimistic=yes
trust_id_inbound=yes        ; 采信网关的 P-Asserted-Identity 作为主叫号码

[android-gw-auth]
type=auth
auth_type=userpass
username=android-gw
password=<secret>

[android-gw]
type=aor
max_contacts=1
remove_existing=yes         ; 手机换 IP/端口后立即接管注册
qualify_frequency=60
```

`extensions_custom.conf`（Request-URI 保持为网关 AoR，号码放在头里）：

```ini
[vsa-android-gw-out]
exten => _X.,1,Dial(PJSIP/android-gw,,b(vsa-android-gw-hdr^s^1(${EXTEN})))
exten => _X.,n,Hangup(${HANGUPCAUSE})

[vsa-android-gw-hdr]
exten => s,1,Set(PJSIP_HEADER(add,X-VSA-Dial)=${ARG1})
exten => s,n,Return()
```

Web 后台：

- **Trunk**：Custom，拨号串 `Local/$OUTNUM$@vsa-android-gw-out`（局域网直连、没有代理时也可以直接用 `PJSIP/$OUTNUM$@android-gw`）。
- **Outbound Route**：专用前缀（如 `9#7#`），匹配 `X.`，走上述 trunk；必须排在 `9.` 之类更宽的路由之前。
- **Inbound Route**：DID `android-gw` → 任意目标。

### 公网接入：Flexisip

手机以 TLS 注册到 [Flexisip](https://www.linphone.org/en/flexisip-sip-server/)（`sips:<domain>:5061`），Flexisip
把请求转给 Asterisk（例如经 WireGuard），并用 MediaRelay 中继媒体（SRTP 在手机与 Asterisk 之间端到端加密）。
Flexisip 不需要为网关做任何专门配置：

- REGISTER 由 Asterisk 鉴权（Flexisip 关闭 Authentication，`reg-on-response=true`）。`ContactRouteInserter` 把 Contact
  改写成指向 Flexisip，NatHelper 修正 NAT 地址，来电沿手机已建立的 TLS 连接送达，手机无需开放端口。
- Asterisk 发回的请求按 Request-URI 的用户部分路由，所以需要上面的 `X-VSA-Dial` 拨号方案。
- 手机配置：`server = domain = <flexisip 域名>`、`port = 5061`、`transport = tls`、`srtp = optional`。

## 从源码构建

需要：JDK 17+，带 NDK 的 Android SDK，`swig`（如 `brew install swig`）。

```bash
sip-pjsua2/native/build-pjsua2.sh   # 只需一次：为 arm64-v8a 编译 OpenSSL + libopus + pjproject，产物在 git 忽略的 src/generated/
./gradlew :sip:test :gateway:test   # JVM 单元测试
./gradlew :app:assembleDebug
```

原生构建脚本会下载版本固定、经 SHA-256 校验的上游源码包，并应用 `sip-pjsua2/native/` 中的补丁。

源码包和共享依赖库的源码树直接放在 `sip-pjsua2/build/pjproject/`
顶层（可用 `PJ_WORK` 指定其他目录）。每个 ABI 下保留 `openssl-build/`、`opus-build/`、
`prefix/{openssl,opus}/` 和独立的 PJSIP 源码/构建目录，因为 PJSIP 的构建系统会在源码树内生成目标相关的配置和头文件。
例如，设置 `ABIS="arm64-v8a x86_64"` 可依次构建多个目标；APK 实际包含的 ABI 仍由 Gradle 的 ABI filters 决定。
每次都会重新创建指定的 ABI 目录并完整构建，包括依赖安装目录、PJSIP 补丁和 JNI 产物。
共享依赖源码和经校验的源码包会保留；不使用构建指纹或增量目标文件缓存。
所有 ABI 成功后才发布暂存的绑定，构建失败会保留原有产物。
不要同时运行共用工作目录或输出目录的构建。

原生 C/C++ 使用 ThinLTO、隐藏符号可见性和 section 垃圾回收。最终导出表仅保留 PJSUA2 JNI 入口，
OpenSSL、Opus 和 PJSIP 符号均为本地符号。构建会检查导出表，并在 ABI 工作目录生成 `pjsua2.exports.txt`。
JNI 包装入口和 OpenSSL 内部算法注册表仍会保留可达代码；仅缩减 Java 不会删除原生包装入口。
Release 构建启用 R8，通过 consumer rules 保留 SWIG 绑定、director 回调及 Shizuku 反射加载的服务构造函数，
其余应用和依赖代码可以优化。修改缩减规则后，应验证 release APK 的 Shizuku 启动、SIP 回调和 TLS/SRTP 音频。

只有在 `~/.gradle/gradle.properties` 中设置了 `vsa.signing.storeFile`、`vsa.signing.storePassword`、
`vsa.signing.keyAlias`、`vsa.signing.keyPassword` 时，release 构建才会签名；否则 `assembleRelease` 产出未签名的 APK。

## 架构

| 模块 | 类型 | 职责 |
|---|---|---|
| `:voice-api` | JVM | 解耦契约：`CallLeg` / `CallEndpoint` / `MediaPort`（8/16 kHz 单声道 PCM16，20 ms 帧）/ `HangupCause` |
| `:sip` | JVM | 自研 SIP UA：UDP、事务层、Digest、REGISTER、INVITE/CANCEL/BYE/re-INVITE、SDP、RTP + 抖动缓冲、G.711、RFC 4733 |
| `:gateway` | JVM | `CallBridge` 状态机（任意两个 `CallEndpoint` 互桥）、`MediaBridge`（单时钟域）、`EchoEndpoint` |
| `:telephony` | Android | 伴侣 `InCallService` + `CellularEndpoint`：把 Telecom 通话映射为 `CallLeg` |
| `:privileged` | Android | Shizuku UserService（AIDL）：setup/health、通话音频 tap（SOCK_SEQPACKET socketpair） |
| `:sip-pjsua2` | Android | pjsua2 实现的 `CallEndpoint`：空声卡 + `AudioMediaPort`，pjsua 不碰 Android 音频 |
| `:app` | Android | 运行时装配、前台服务、设置（Keystore 加密密码）、adb 控制、Compose 界面 |

接入新的语音/控制后端：实现一个 `CallEndpoint`（及其 `CallLeg` / `MediaPort`），交给 `CallBridge` 即可，网关核心不需要改。
设计细节和踩过的坑见 [AGENT.md](AGENT.md)。

## 参考

- [AirSIM](https://github.com/kai-wu-cortex/AirSIM)：通话音频拦截的思路最初来自阅读它的代码；本项目是独立实现，不依赖它。
- [Shizuku](https://github.com/RikkaApps/Shizuku)、[pjsip](https://github.com/pjsip/pjproject)、
  [Flexisip](https://gitlab.linphone.org/BC/public/flexisip)。

## 许可证

Copyright (C) 2026 sparkcyf

本程序是自由软件：你可以依据自由软件基金会发布的 GNU 通用公共许可证第 2 版或（由你选择）任何更新的版本，再分发和/或修改它。详见 [LICENSE](LICENSE)（以英文原文为准）。例外：允许将本程序与任何依据 Apache License 2.0 许可的库链接，前提是其余所有代码均遵守 GPL。

Release APK 静态链接了 pjsip（GPL-2.0-or-later）、OpenSSL（Apache-2.0）和 libopus（BSD-3-Clause），见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
每个 release 对应的源码是本仓库对应 tag 的提交，加上 `sip-pjsua2/native/build-pjsua2.sh` 下载的版本固定的上游源码包。

## 免责声明

本软件按“原样”提供，不附带任何明示或暗示的担保，包括但不限于对适销性、特定用途适用性和不侵权的担保。在任何情况下，作者或版权持有人
均不对因本软件、使用本软件或以其他方式处理本软件而产生的任何索赔、损害或其他责任负责，无论是合同诉讼、侵权诉讼还是其他诉讼。
使用本软件的风险完全由你自行承担。如与英文版有出入，以英文版为准。

特别提醒：

- **不能用于紧急呼叫。** 紧急号码按设计会被拒绝。不要依赖本网关或运行它的手机联系紧急服务。
- **通话可能失败。** 通话可能被挂断、拒接、送错目标或接通后没有声音，任何系统更新都可能让 App 失效。漏接电话及其后果由你自行承担。
- **费用。** 经网关拨出的电话由你的运营商计费。请限制允许拨出的号码前缀并保护好 PBX；被入侵的 PBX 可能让手机拨打高额付费号码。
- **法律与服务条款。** 在你所在的司法辖区，转接、录制或截取通话，以及把移动线路接入 PBX，可能受到法律或运营商条款的限制。
  遵守这些规定、并取得通话各方必要的同意，完全是你自己的责任。
- **安全。** Shizuku 会授予本 App shell 级权限。只安装你信任的构建，并妥善保管 SIP 凭据和 PBX。

本项目与 Google、Motorola、Shizuku、pjsip、Asterisk、Flexisip 等项目或任何运营商均无关联，也未获得其认可。
