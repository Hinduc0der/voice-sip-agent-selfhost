# AGENT.md

This file provides guidance to coding agents (Claude Code etc.) when working with code in this repository.

与用户讨论请使用中文；代码、注释、commit message 用英文。

## 项目概况

把一台**未 root** 的 Android 手机（Pixel 11 Pro / Android 17）变成单线 **蜂窝 ↔ SIP 网关**，以 trunk 方式接入 Asterisk（IssabelPBX）。特权操作通过 **Shizuku（shell uid 2000）** 完成；SIP 侧有两套可切换实现：自研纯 Kotlin（`:sip`，仅 UDP）和 pjsip 2.17 的 pjsua2（`:sip-pjsua2`，UDP/TCP/TLS + SDES-SRTP，当前手机使用这一套）。设计依据与实验记录在 `../AirSIM/sip-gateway-notes`（DESIGN.md、tools/probe）。

已在真机验证：蜂窝来电 → PBX（DID `pixel11-gw` → 分机 302）、302 拨 `9#7#<号码>` 经手机外呼、双向语音、DTMF、Wi-Fi 断线重连（含通话中）、桥接期间本机听筒静音、"接管通话"总开关（以上用自研栈）；pjsua2 在局域网 UDP 和经公网 Flexisip（TLS + SRTP 实际生效）下两个方向的通话。尚未验证：Doze 长时间待机、长通话时钟漂移、通话等待、pjsua2 下的 DTMF 与断网重连、关掉 Wi-Fi 纯蜂窝数据的公网通话。

## 常用命令

```bash
sip-pjsua2/native/build-pjsua2.sh                # 首次/升级时：编 OpenSSL 3.5.8 + pjproject 2.17（arm64），产物在 git 忽略的 src/generated/
./gradlew test                                   # JVM 单元测试（:sip、:gateway）
./gradlew :sip-pjsua2:testDebugUnitTest          # INVITE 解析等
./gradlew :sip:test --tests 'com.voicesipagent.sip.SipMessageTest'          # 单个测试类
./gradlew :gateway:test --tests 'com.voicesipagent.gateway.CallBridgeTest.cellularInboundToSipFullCall'
./gradlew :app:assembleDebug
export ANDROID_SERIAL=<serial>             # zsh 不会对 A="adb -s …" 做分词，用环境变量
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -s VoiceSipAgent VsaPrivileged VsaTap
```

adb 控制（receiver 受 DUMP 权限保护，只有 shell 能发；**组件名必须写全类名**，`.AdbControlReceiver` 会解析到不存在的类，广播静默丢弃）：

```bash
R=com.voicesipagent/com.voicesipagent.app.AdbControlReceiver
adb shell am broadcast -n $R --es op status            # 状态输出到 logcat（VoiceSipAgent）
adb shell am broadcast -n $R --es op takeover --ez on false
adb shell am broadcast -n $R --es op earpiece-mute --ez on true
adb shell am broadcast -n $R --es op loopback --ez on true   # 来电回声测试，仅内存态，重装后复位
adb shell am broadcast -n $R --es op provision --es user … --es password '…' --es target …   # 只覆盖给出的字段
adb shell am broadcast -n $R --es op setup | sip-restart
adb shell am broadcast -n $R --es op sip-stack --es stack pjsua2|builtin
adb shell am broadcast -n $R --es op provision --es transport tls --es srtp optional …   # transport/srtp 仅 pjsua2
adb shell am broadcast -n $R --es op sip-debug --ez on true   # pjsip info 级日志，默认关（持久化）
adb shell "am broadcast -n $R --es op provision --es prefixes ''"   # 空字符串参数要套一层引号，否则 am 报 Argument expected
```

当前手机配置：`server=domain=sip.example.org`、`port=5061`、`transport=tls`、`srtp=optional`、stack=pjsua2、sip-debug 关。

`:sip:sipProbe`（`-PprobeArgs="echo *43 40"` / `"listen 60"`）读取 git 忽略的 `sip_secret`，为当初的分机 401 写的。401 已删除；若改用 trunk 账号运行，会因 `remove_existing=yes` 顶掉手机的注册，且 `*43` 等内部功能码在 `from-trunk` 上下文不可达。

工具链：Gradle 9.5.1 wrapper、AGP 9.3（内置 Kotlin）、Kotlin 2.2.10、compileSdk 37、minSdk 34、targetSdk 36；JVM 模块 `jvmToolchain(17)`。

## 架构

模块依赖方向：`:voice-api` ← `:sip`、`:gateway`（纯 JVM，可在主机上测）；`:telephony`、`:privileged`（Android）；`:app` 负责装配。

- **解耦契约（`:voice-api`）**：`CallEndpoint`（`dial()` 永不抛异常，失败以 ENDED 的 leg 返回）、`CallLeg`（状态只能前进：NEW→PROCEEDING→RINGING→CONNECTED→ENDED）、`MediaPort`（8 kHz mono PCM16，20 ms = 160 样本 = 320 字节，`readFrame()` 不阻塞）。新语音/控制接入 = 实现一个 `CallEndpoint`，核心不改。
- **`CallBridge`（`:gateway`）**：任意两个 endpoint 互桥。A 侧来电先 dial B 侧，**B 接听后才接听 A**；超时/失败映射为 `HangupCause` 拒接。`EchoEndpoint` 用于回环测试。
- **单时钟域**：`MediaBridge` 由 `isClockSource` 的端口驱动（蜂窝 tap，即基带时钟），两个方向都跟这一个时钟走；`RtpSession` 无时钟，靠抖动缓冲吸收网络抖动。没有时钟源时才用 20 ms ticker。
- **蜂窝侧（`:telephony`）**：App 不是默认拨号器，而是 Telecom **伴侣 InCallService**（CALL_COMPANION_APP + MANAGE_ONGOING_CALLS appop + `cmd telecom add-or-remove-call-companion-app`）。`CellularEndpoint` 只处理自己负责的通话：网关来电、自己拨出的去电（按号码后 8 位匹配）；用户手动拨打的、有其他通话时进来的一律不碰；紧急号码 fail-closed 拒绝。通话 ACTIVE 后 `setMuted(true)` 并打开音频 tap（最多重试 5 次）。
- **特权进程（`:privileged`）**：Shizuku UserService，运行在 shell uid 2000 的 app_process 中，AIDL 接口 `IPrivilegedService`。通话音频以 **SOCK_SEQPACKET socketpair** 交给 App（一个包 = 一帧），App 侧为 `PcmSocketPort`（时钟源）。UserService 的 `version` 取自 APK `lastUpdateTime`，所以每次重装都会重启 shell 进程、加载新代码。
- **SIP（`:sip`）**：UDP；所有协议状态都在单个 `sip-ua` 调度线程上执行（`post {}`）。外呼时带 `P-Asserted-Identity` 传主叫号码；来电时要拨的蜂窝号码取 `X-VSA-Dial` 头，没有则取 Request-URI 的 user 部分。只支持 PCMU/PCMA + telephone-event(101)。
- **SIP（`:sip-pjsua2`）**：`PjsipEndpoint` 实现 `CallEndpoint`，trunk 语义与 `:sip` 相同。要点：
  - pjsua 是进程单例，`PjsuaLib` 只初始化一次、永不销毁；endpoint 重启只增删 account，传输（按类型+端口）保留复用。所有 pjsua 调用和 SIP 回调都在单个 `pjsua` 线程（`threadCnt=0` + `mainThreadOnly`，循环里跑任务队列和 `libHandleEvents`）。
  - **不编译任何 Android 声卡后端**，只用空声卡：pjsua 完全不碰 AudioManager/音频模式。音频经 `AudioMediaPort`（`ConfPort`）进出会议桥（8 kHz/20 ms，无重采样、无 EC/VAD），两侧各一个最多 5 帧的 `FrameFifo` 吸收两套时钟的偏差。
  - SWIG 对象（Call/Account/AudioMediaPort）必须在 pjsua 线程上显式 `delete()`，回调里不能删自己（`finish()` 把删除投递到下一轮）。`Account` 先 `shutdown()` 再 `delete()`。
  - `stop()` 会等注销响应（≤1.5 s）：新 account 复用同一个 Contact，迟到的注销会把新注册删掉。
  - 构建补丁（`native/`）：`config_site.h`、`pjsua2-java-ext.i`（ByteVector 整块拷贝的 typemap，必须插在 `%template(ByteVector)` 之前；SWIG director 线程常驻 attach）、`pjsip-tls-wildcard.patch`（RFC 6125 通配证书匹配）。pjsua 的日志回调受 `consoleLevel` 门控，不只是 stdout。
- **运行时（`:app`）**：`GatewayRuntime` 单例，所有改状态的操作都在 `gateway-runtime` 单线程 executor 上执行；`GatewayApp.onCreate` 就启动它（Telecom 可能只为绑定 InCallService 拉起进程）。每 60 s 做一次健康检查，伴侣覆盖丢失时重跑 setup。设置保存在 SharedPreferences，SIP 密码用 Keystore AES-GCM 加密。

### 接管开关与本机静音

- **takeover=false**：禁用 `GatewayInCallService` 组件、移除 Telecom 伴侣覆盖、注销 SIP，行为等同于未安装本 App。通话中关闭会等通话结束再生效（`takeoverOffPending`）。
- **伴侣覆盖是一个普通列表**：添加会追加重复项，移除一次只删一条。`setup` 必须先数一下现有条目，再决定添加或逐条删除。
- **earpiece mute**：tap 打开后，shell 进程把音频模式从 `IN_CALL(2)` 切到 `MODE_CALL_REDIRECT(5)`。策略层会拆掉"基带 ↔ 听筒/麦克风"的链路，重定向的 record/track 照常工作。tap 关闭时（`onClosed`）恢复为 NORMAL，相当于撤回我们这个模式请求。AudioService 在进程死亡时也会自动撤回。`cmd audio adj-mute` 在 Android 17 上已不存在。

## 来之不易的坑（改音频/特权代码前必读）

- **AudioFlinger attribution**：Shizuku 进程的进程级身份是 App 包名，而 uid 是 2000，AudioFlinger 会以 `invalid attr` 拒绝。所有音频对象必须用 `ShellEnv.shellContext()`（包名/opPackage/AttributionSource 均为 `com.android.shell`）显式构建，不能用 `AudioManager.getCall*()` 这类 helper。
- **音频属性必须与 framework helper 完全一致**：record 的 capture preset 设为 `VOICE_DOWNLINK(3)`，track 的 system usage 设为 `USAGE_CALL_ASSISTANT(17)` 加 `CONTENT_TYPE_SPEECH`，再加 `setCallRedirectionMode(CALL_REDIRECT_PSTN)`；这些都是隐藏 API，走反射。缺了属性，record 会退回麦克风、track 会当媒体从本机扬声器播出，**但 Telecom 状态和帧计数看上去都完全正常**，只能靠 `VsaTap` 的 `levels`/`downVoiced` 日志和真人听感判断。
- **隐藏 API 的真实行为以设备为准**：`adb pull /system/framework/{framework,services}.jar`，解出 dex，用 `$ANDROID_SDK/build-tools/37.0.0/dexdump -d` 反汇编目标方法（例如 `AudioManager.getCallDownlinkExtractionAudioRecord`、`AudioService.setMode`）。
- 伴侣覆盖会在重启或 Telecom 重启后丢失，靠 setup/健康检查重新补上。重启后手机处于 BFU，非 root 的 Shizuku 也需要手动重新启动，不追求开机自启。

## PBX 集成（IssabelPBX 2.12 / Asterisk 16.28）

- 只读检查：`ssh pbx-host 'sudo -i asterisk -rx "…"'`。多行脚本写成文件后用 `ssh … 'sudo -i bash -s' < script` 执行；内联 heredoc 经过 `sudo -i` 会被压成一行。
- **任何 PBX 修改都必须先得到用户明确同意**，并且先备份（mysqldump + 相关 conf）。
- 网关是 **trunk**，不是分机：PJSIP endpoint/aor/auth `pixel11-gw` 手写在 `/etc/asterisk/pjsip_custom_post.conf`（`context=from-trunk`、`trust_id_inbound=yes`、`remove_existing=yes`、仅 ulaw/alaw）。网页后台对象（custom trunk `PJSIP/$OUTNUM$@pixel11-gw`、Outbound Route 前缀 `9#7#` + `X.`、Inbound Route DID `pixel11-gw` → 302）是直接写进 `asterisk` 库再 `module_admin reload` 的，在网页后台可见、可改。
- `9#7#` 路由必须排在 `9_outside`（`_9.`）之前：每条 outbound route 是独立的被包含上下文，按顺序先匹配者生效。
- 同机还有 another 蜂窝 trunk（`cell-trunk`，catch-all DID → 301）和 `extensions_custom.conf` 里的短信相关上下文，不要动。
- trunk 去电：custom trunk 拨号串为 `Local/$OUTNUM$@vsa-pixel11-gw-out`，`extensions_custom.conf` 里的 `[vsa-pixel11-gw-out]`/`[vsa-pixel11-gw-hdr]` 用 `Dial(PJSIP/pixel11-gw)` 并以 `X-VSA-Dial` 头带号码，使 Request-URI 的 user 保持 `pixel11-gw`。endpoint 已改为 `media_encryption=sdes` + optimistic、`rewrite_contact=no`（与经 Flexisip 的 301/302 一致）。备份：`<backup-dir>/`。
- 公网入口是 `sbc-host` 上的 Flexisip 2.6.1（`/opt/docker/flexisip`，TLS 5061 + MediaRelay），同时也是 WireGuard 中心节点（`wg0` 10.99.0.0/24，PBX 为 10.99.0.180）。改它同样需要用户同意。
  - Flexisip 为网关**没有任何专门配置**。`insert-domain=true` 时 `CtRt` 只记 `transport:domain`，Asterisk 发回的请求一律进 Router、按 Request-URI user 查注册表；PBX 若把 user 换成号码（`PJSIP/<号码>@trunk`）必然 404，这就是 `X-VSA-Dial` 方案的原因。来电沿手机建立的 TLS 连接送达（NatHelper 修正 Contact + `fs-conn-id`）。
  - 证书是 `*.example.org` 通配证书。

## 秘密与隐私

- `sip_secret`、`pixel11-gw.secret`（第 1 行用户名、第 2 行密码）、`*.secret`、`local.properties` 均已 git 忽略，**不得提交、不得在输出中回显密码**。下发到手机时从文件读进 shell 变量，直接塞进 adb 广播。
- 日志里电话号码只保留后 2 位（`redactNumber()`）；PCM 不写日志也不落盘，只记录电平/计数。
