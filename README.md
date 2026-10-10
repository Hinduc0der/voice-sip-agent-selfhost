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
- **SIP 去电**：Outbound Route 经 trunk `PJSIP/$OUTNUM$@<trunk>` 呼叫网关，Request-URI 的用户部分即拨出号码；蜂窝 ACTIVE 后回 200 OK。紧急号码一律拒绝（403），`prefixes` 可限制允许拨出的号码前缀。

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
rewrite_contact=yes
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

Web 后台：

- **Trunk**：Custom，拨号串 `PJSIP/$OUTNUM$@pixel11-gw`。
- **Outbound Route**：前缀 `9#7#`、匹配 `X.`，走上述 trunk；须排在 `9.` 之类更宽的路由之前。
- **Inbound Route**：DID `pixel11-gw` → 目标分机（如 302）。
