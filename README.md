# android-voice-sip-agent

把一台未 root 的 Android 手机变成单线 **蜂窝 ↔ SIP 网关**，接入局域网 Asterisk。
特权操作（通话音频拦截、Telecom 伴侣覆盖、appops）通过 **Shizuku（shell uid 2000）** 完成；SIP 侧可选自研纯 Kotlin 协议栈或 pjsip（pjsua2）。

设计依据：`AirSIM/sip-gateway-notes/DESIGN.md`（Pixel 11 Pro / Android 17 实测）。

## 模块

| 模块 | 类型 | 职责 |
|---|---|---|
| `:voice-api` | JVM | 解耦契约：`CallLeg` / `CallEndpoint` / `MediaPort`（8 kHz PCM16，20 ms 帧）/ `HangupCause` |
| `:sip` | JVM | SIP UA：UDP、事务层、Digest、REGISTER、INVITE/CANCEL/BYE/re-INVITE、SDP、RTP + 抖动缓冲、G.711、RFC 4733 |
| `:gateway` | JVM | `CallBridge` 状态机（任意两个 `CallEndpoint` 互桥）、`MediaBridge`（单时钟域）、`EchoEndpoint`（回环测试） |
| `:telephony` | Android | 伴侣 `InCallService` + `CellularEndpoint`：把 Telecom 通话映射为 `CallLeg` |
| `:privileged` | Android | Shizuku UserService（AIDL）：setup/health、通话音频 tap（SOCK_SEQPACKET socketpair）；App 侧 `ShizukuConnector`、`PcmSocketPort` |
| `:sip-pjsua2` | Android | pjsua2 实现的 `CallEndpoint`：空声卡 + `AudioMediaPort` 与 tap 交换 PCM，pjsua 不碰 Android 音频；原生库由 `native/build-pjsua2.sh` 生成 |
| `:app` | Android | 运行时装配、前台服务、开机自启、设置（Keystore 加密密码）、adb 控制、Compose 界面 |

接入新的语音/控制 SDK：实现一个 `CallEndpoint`（及其 `CallLeg` / `MediaPort`），交给 `CallBridge` 即可，网关核心不需要改。

## 构建与测试

`:sip-pjsua2` 依赖本地编译的 pjproject（git 忽略的 `sip-pjsua2/src/generated/`），首次构建前执行一次（需要 NDK r28c、`brew install swig`）：

```bash
sip-pjsua2/native/build-pjsua2.sh   # pjproject 2.17，arm64-v8a，约 3 分钟
./gradlew test                 # JVM 单元测试（sip、gateway）
./gradlew :app:assembleDebug
# 在开发机上直连 PBX 做 SIP 实测（读取 git 忽略的 ./sip_secret）
./gradlew :sip:sipProbe -PprobeArgs="echo *43 40"
```

## 部署（adb）

```bash
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
# 配置 SIP（只有 shell 能发送：receiver 受 DUMP 权限保护）
adb shell am broadcast -n com.voicesipagent/com.voicesipagent.app.AdbControlReceiver \
  --es op provision --es server <pbx> --ei port 5066 --es user pixel11-gw --es password '<secret>' --es target pixel11-gw
adb shell am broadcast -n com.voicesipagent/com.voicesipagent.app.AdbControlReceiver --es op status
adb shell am broadcast -n com.voicesipagent/com.voicesipagent.app.AdbControlReceiver --es op loopback --ez on true
# SIP 实现：builtin（默认）或 pjsua2
adb shell am broadcast -n com.voicesipagent/com.voicesipagent.app.AdbControlReceiver --es op sip-stack --es stack pjsua2
# 经公网 SBC（如 Flexisip）：TLS 信令 + SDES-SRTP（仅 pjsua2；srtp = off | optional | mandatory）
adb shell am broadcast -n com.voicesipagent/com.voicesipagent.app.AdbControlReceiver \
  --es op provision --es server sip.example.org --ei port 5061 --es transport tls --es domain sip.example.org --es srtp optional
# pjsip info 级日志（开发调试用，默认关；错误/警告始终输出）
adb shell am broadcast -n com.voicesipagent/com.voicesipagent.app.AdbControlReceiver --es op sip-debug --ez on true
# 接管总开关：关闭后禁用 InCallService、撤销伴侣覆盖、注销 SIP，通话行为与未安装本 App 相同（通话中关闭则挂断后生效）
adb shell am broadcast -n com.voicesipagent/com.voicesipagent.app.AdbControlReceiver --es op takeover --ez on false
# 桥接期间静音本机听筒/麦克风（默认开；切到 MODE_CALL_REDIRECT，tap 关闭即恢复）
adb shell am broadcast -n com.voicesipagent/com.voicesipagent.app.AdbControlReceiver --es op earpiece-mute --ez on true
adb logcat -s VoiceSipAgent VsaPrivileged VsaTap
```

首次运行需要在 Shizuku 中授权本 App；之后开机、升级后都会自动重新执行 setup（appops、伴侣覆盖、Doze 白名单、运行时权限授予）。

## 呼叫路由

网关在 PBX 上作为 **trunk** 接入（不是分机）：手机向 PBX 注册，PBX 侧的路由全部在 Inbound/Outbound Routes 中管理。

- **蜂窝来电**：手机 INVITE `sip:<target>@pbx`，`target` 是 DID（如 `pixel11-gw`），带 `P-Asserted-Identity` 传主叫号码；PBX 用 Inbound Route 按 DID 分配（如转 302）。SIP 接听后才接听蜂窝来电。
- **SIP 去电**：PBX 呼叫网关，要拨的号码取自 `X-VSA-Dial` 头；没有该头时取 Request-URI 的用户部分（`PJSIP/$OUTNUM$@<trunk>` 直连时即号码）。蜂窝 ACTIVE 后回 200 OK。紧急号码一律拒绝（403），`prefixes` 可限制允许拨出的号码前缀。
  - 中间隔着按注册表路由的代理（Flexisip 等）时，Request-URI 的用户部分必须保持网关自己的 AoR，否则代理按号码查注册必然 404；所以号码放进 `X-VSA-Dial`（见下方拨号方案）。

### 传输与加密（pjsua2）

- `transport`：`udp`（局域网）、`tcp`、`tls`。TLS 以 Android 系统 CA 库校验服务器证书链和主机名，仅 TLS 1.2/1.3；接受最左一级的 `*.` 通配证书（RFC 6125，原版 pjsip 按 RFC 5922 拒绝，见 `native/pjsip-tls-wildcard.patch`）。**信令不做降级**：TLS 失败只会注册失败并每 30 s 重试，不会退回明文。
- `srtp`：仅在 TLS 下生效（明文信令里交换密钥没有意义）。`optional` 同时提供 RTP 与 SDES，对端不支持时退回 RTP；`mandatory` 不支持则 488。

### PBX 配置示例（IssabelPBX / FreePBX + chan_pjsip）

`pjsip_custom_post.conf`：

```ini
[pixel11-gw]
type=endpoint
transport=transport-udp
context=from-trunk          ; 来电按 Inbound Route 处理，不具备内部分机权限
aors=pixel11-gw
auth=pixel11-gw-auth
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

[pixel11-gw-auth]
type=auth
auth_type=userpass
username=pixel11-gw
password=<secret>

[pixel11-gw]
type=aor
max_contacts=1
remove_existing=yes         ; 手机换 IP/端口后立即接管注册
qualify_frequency=60
```

`extensions_custom.conf`（新增独立上下文；保持 Request-URI 为网关 AoR，号码放头里）：

```ini
[vsa-pixel11-gw-out]
exten => _X.,1,Dial(PJSIP/pixel11-gw,,b(vsa-pixel11-gw-hdr^s^1(${EXTEN})))
exten => _X.,n,Hangup(${HANGUPCAUSE})

[vsa-pixel11-gw-hdr]
exten => s,1,Set(PJSIP_HEADER(add,X-VSA-Dial)=${ARG1})
exten => s,n,Return()
```

Web 后台：

- **Trunk**：Custom，拨号串 `Local/$OUTNUM$@vsa-pixel11-gw-out`（局域网直连、无代理时也可直接用 `PJSIP/$OUTNUM$@pixel11-gw`）。
- **Outbound Route**：前缀 `9#7#`、匹配 `X.`，走上述 trunk；须排在 `9.` 之类更宽的路由之前。
- **Inbound Route**：DID `pixel11-gw` → 目标分机（如 302）。

### 公网接入：Flexisip

手机以 TLS 注册到 Flexisip（`sips:<domain>:5061`），Flexisip 按域名把请求经 WireGuard 转给 Asterisk，
MediaRelay 中继媒体（SRTP 端到端在手机与 Asterisk 之间，中继不解密）。Flexisip 不需要为网关做任何专门配置：

- REGISTER 由 Asterisk 鉴权（Flexisip 的 Authentication 关闭、`reg-on-response=true`）；`ContactRouteInserter` 把 Contact 改写成指向 Flexisip，NatHelper 修正 NAT 地址，来电沿手机已建立的 TLS 连接（`fs-conn-id`）送达，手机无需开放端口。
- Asterisk 发回的请求由 Router 按 Request-URI 的用户部分查注册表，所以 trunk 去电必须用上面的 `X-VSA-Dial` 方案。
- 手机配置：`server=domain=<flexisip 域名>`、`port=5061`、`transport=tls`、`srtp=optional`。
