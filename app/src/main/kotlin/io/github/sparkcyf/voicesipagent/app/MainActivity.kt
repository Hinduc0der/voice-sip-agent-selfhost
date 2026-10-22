package io.github.sparkcyf.voicesipagent.app

import android.os.Bundle
import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sparkcyf.voicesipagent.gateway.BusyPolicy
import io.github.sparkcyf.voicesipagent.privileged.HealthKeys
import io.github.sparkcyf.voicesipagent.privileged.AudioMethod
import io.github.sparkcyf.voicesipagent.privileged.CallAccess
import io.github.sparkcyf.voicesipagent.privileged.ShizukuConnector

class MainActivity : DialerRoleActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val runtime = GatewayRuntime.get(this)
        GatewayService.start(this)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                val message by roleMessage.collectAsState()
                Scaffold { pad -> Screen(runtime, Modifier.padding(pad), ::requestDialer, ::switchDialer,
                    ::chooseDialerInSettings, ::cancelDialerChange, ::setTakeoverFromUi, message) }
            }
        }
    }
}

@Composable
private fun Screen(rt: GatewayRuntime, modifier: Modifier, requestDialer: () -> Unit, switchDialer: () -> Unit,
    chooseDialer: () -> Unit, cancelDialer: () -> Unit, setTakeover: (Boolean) -> Unit, roleMessage: String) {
    val h by rt.health.collectAsState()
    val log by AppLog.tail.collectAsState()
    val cur by rt.settingsFlow.collectAsState()
    currentUiLang = UiLang.of(cur.uiLanguage)
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("GSMtoSIP", style = MaterialTheme.typography.headlineSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val lang = UiLang.of(cur.uiLanguage)
                TextButton(onClick = { rt.setUiLanguage("ru") }, enabled = lang != UiLang.RU) { Text("РУ") }
                TextButton(onClick = { rt.setUiLanguage("en") }, enabled = lang != UiLang.EN) { Text("EN") }
                InfoButton("language")
            }
        }
        Section(t("Перехват", "Takeover"), info = "takeover") {
            Toggle(t("Перехватывать звонки", "Take over calls"), cur.takeover, setTakeover) {
                when {
                    h.takeoverOffPending -> t("Выключится после текущего звонка", "Turning off after the current call")
                    h.takeoverOnPending -> t("Включится после завершения звонков", "Turning on after current calls end")
                    h.dialerChangeRunning -> t("Меняется звонилка по умолчанию", "Changing the default dialer")
                    h.dialerChangePending -> if (h.dialerChangeAcquire) t("Ожидание роли звонилки по умолчанию", "Waiting to become the default dialer") else t("Ожидание возврата прежней звонилки", "Waiting to restore your previous dialer")
                    cur.takeover -> if (h.cellularReady) t("Звонки переводятся на софтфон", "Calls are bridged to the PBX") else h.readinessDetail
                    h.holdsDialer && cur.keepDefaultDialer -> t("Шлюз выключен; роль звонилки сохранена", "Gateway off; keeping the default-dialer role")
                    h.holdsDialer -> t("Шлюз выключен; нужно вернуть звонилку", "Gateway off; dialer restoration needs attention")
                    else -> t("Шлюз выключен; звонки у вашей звонилки", "Gateway off; calls stay with your dialer")
                }
            }
            if (h.effectiveCallAccess == CallAccess.DIALER) {
                Text(t("Включение запросит роль звонилки по умолчанию. Выключение вернёт прежнюю, если не включено сохранение роли в Advanced.",
                        "Turning on requests the default-dialer role. Turning off restores your previous dialer unless you keep the role in Advanced settings."),
                    style = MaterialTheme.typography.bodySmall)
            }
            if (h.settingsPending) Text(t("Настройки применятся после завершения звонков", "Settings will apply after current calls end"))
        }
        Section(t("Состояние", "Health")) {
            val p = h.privileged
            Check("Shizuku", h.shizuku == ShizukuConnector.State.CONNECTED, "${h.shizuku} ${h.shizukuDetail}")
            Check(t("Доступ к звонкам", "Call access"), h.callAccessReady,
                if (h.effectiveCallAccess == CallAccess.DIALER) t("Звонилка по умолчанию", "Default dialer") else t("Рядом со звонилкой", "Alongside your dialer"))
            Check(t("Доступ к звуку вызова", "Call audio access"), h.audioReady)
            Check(t("Исключён из экономии батареи", "Doze allowlisted"), p?.get(HealthKeys.DOZE_ALLOWLISTED) == true)
            Check(t("Регистрация SIP", "SIP registration"), h.sip.ready, h.sip.detail)
            Text(h.readinessDetail, style = MaterialTheme.typography.bodySmall)
            if (h.effectiveCallAccess == CallAccess.DIALER && !h.holdsDialer) {
                Text(t("Режим звонилки по умолчанию мостит звонки. Для ручного набора верни обычную звонилку.",
                    "Default-dialer mode bridges calls through the PBX. For manual calling, switch back to your usual dialer."))
                if (cur.takeover && !h.dialerChangePending) {
                    Button(onClick = { setTakeover(true) }, enabled = h.dialerRoleAvailable && !h.dialerChangeRunning) { Text(t("Включить перехват", "Enable takeover")) }
                }
                if (!h.dialerRoleAvailable) Text(t("Выбор звонилки по умолчанию недоступен на этом устройстве.", "Default dialer selection is unavailable on this device."))
            }
            if (roleMessage.isNotEmpty()) Text(roleMessage)
            if (h.dialerChangeFailed || (h.holdsDialer && !cur.takeover && !cur.keepDefaultDialer)) {
                if (h.holdsDialer) OutlinedButton(onClick = switchDialer, enabled = !h.dialerChangeRunning) { Text(t("Вернуть прежнюю звонилку", "Restore previous dialer")) }
                OutlinedButton(onClick = chooseDialer, enabled = !h.dialerChangeRunning) { Text(t("Выбрать звонилку в настройках", "Choose dialer in Settings")) }
            }
            if (h.dialerChangePending) OutlinedButton(onClick = cancelDialer) { Text(t("Отменить смену звонилки", "Cancel dialer change")) }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (h.shizuku == ShizukuConnector.State.PERMISSION_REQUIRED || h.shizuku == ShizukuConnector.State.PERMISSION_DENIED) {
                    Button(onClick = { rt.shizuku.requestPermission() }) { Text(t("Выдать Shizuku", "Grant Shizuku")) }
                }
                OutlinedButton(onClick = { rt.runSetup() }) { Text(t("Перенастроить", "Re-run setup")) }
                OutlinedButton(onClick = { rt.restartSip() }) { Text(t("Перерегистрация", "Re-register")) }
            }
        }
        Section(t("Звонок", "Call")) {
            Text(h.calls.summary)
            Text(h.session ?: t("Нет активной сессии", "No active session"))
        }
        SettingsSection(rt)
        Section(t("Журнал", "Log")) {
            Text(log.takeLast(60).reversed().joinToString("\n"), fontFamily = FontFamily.Monospace,
                fontSize = 11.sp, lineHeight = 14.sp)
        }
        var advanced by remember { mutableStateOf(false) }
        OutlinedButton(onClick = { advanced = !advanced }) { Text(if (advanced) t("Скрыть доп. настройки", "Hide advanced settings") else t("Дополнительные настройки", "Advanced settings")) }
        if (advanced) {
            Section(t("Интеграция с системой", "Platform integration")) {
                Choice(t("Доступ к звонкам", "Call access"), listOf(CallAccess.AUTO, CallAccess.COMPANION, CallAccess.DIALER), cur.callAccess,
                    { when (it) { CallAccess.AUTO -> t("Авто", "Automatic"); CallAccess.COMPANION -> t("Рядом", "Alongside dialer"); else -> t("Звонилка", "Default dialer") } },
                    info = "call_access") {
                    rt.applySettings(cur.copy(callAccess = it))
                }
                Toggle(t("Сохранять роль звонилки при выключении", "Keep default-dialer role when gateway is off"), cur.keepDefaultDialer,
                    { rt.setKeepDefaultDialer(it) }, info = "keep_dialer") {
                    t("Применится при следующем выключении перехвата. Прежнюю звонилку можно вернуть кнопкой ниже.",
                        "Applies the next time takeover is switched off. Restore manually below when needed.")
                }
                if (h.holdsDialer) {
                    OutlinedButton(onClick = switchDialer, enabled = !h.dialerChangeRunning) { Text(t("Вернуть прежнюю звонилку", "Restore previous dialer")) }
                } else if (h.dialerRoleAvailable) {
                    OutlinedButton(onClick = requestDialer, enabled = !h.dialerChangeRunning) { Text(t("Стать звонилкой по умолчанию", "Become default dialer")) }
                }
                OutlinedButton(onClick = chooseDialer, enabled = !h.dialerChangeRunning) { Text(t("Выбрать звонилку в настройках", "Choose dialer in Settings")) }
                Choice(t("Способ захвата звука", "Cellular audio"), listOf(AudioMethod.AUTO, AudioMethod.LEGACY, AudioMethod.OFFICIAL), cur.audioMethod,
                    { when (it) { AudioMethod.AUTO -> t("Авто", "Automatic"); AudioMethod.LEGACY -> t("Прямой тракт", "Direct telephony"); else -> t("Переадресация", "Call redirection") } },
                    enabled = { it != AudioMethod.OFFICIAL || Build.VERSION.SDK_INT >= 33 }, info = "audio_method") {
                    rt.applySettings(cur.copy(audioMethod = it))
                }
                Text(t("★ Если звонок соединился, но никого не слышно — переключай именно этот параметр (подробно в ⓘ).",
                        "★ If a call connects but nobody is heard, switch this very setting (details in ⓘ)."),
                    style = MaterialTheme.typography.bodySmall)
                Toggle(t("Глушить динамик шлюза", "Mute local call output"), cur.muteEarpiece, { rt.setMuteEarpiece(it) }, info = "mute_earpiece") {
                    val backendAvailable = when (cur.audioMethod) {
                        AudioMethod.LEGACY -> h.privileged?.get(HealthKeys.DIRECT_AUDIO) == true
                        AudioMethod.OFFICIAL -> h.privileged?.get(HealthKeys.REDIRECT_AUDIO) == true
                        else -> true
                    }
                    if (!cur.muteEarpiece)
                        t("Оставляет звук вызова в динамике; микрофон глушится. С следующего звонка.",
                            "Keeps normal call output for local listening; microphone stays muted. Applies from next call.")
                    else if (h.privileged?.get(HealthKeys.LOCAL_SUPPRESSION) == true && backendAvailable)
                        t("Глушит звук вызова, если поддерживается; с следующего звонка", "Mutes call output when supported; applies from next call")
                    else t("Недоступно с текущей платформой или методом звука", "Unavailable with the current platform or audio method")
                }
                Toggle(t("Эхо-тест (зацикливание входящих)", "Loopback test (echo inbound calls)"), h.loopbackTest, { rt.setLoopbackTest(it) }, info = "loopback") { t("Возвращает входящие эхом вместо соединения с софтфоном", "Echoes incoming calls instead of connecting the PBX") }
                Toggle(t("Подробный журнал", "Debug log"), cur.sipDebugLog, { rt.setSipDebugLog(it) }, info = "debug_log") { t("Подробные логи, номера маскируются", "Info-level logs with masked numbers") }
            }
            Section(t("Диагностика", "Diagnostics")) {
                Text("Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT}")
                Text("requestedCallAccess=${cur.callAccess} effective=${h.effectiveCallAccess} audio=${cur.audioMethod}")
                Text(h.privileged?.entries?.joinToString("\n") { "${it.key}=${it.value}" } ?: t("Ожидание помощника", "Waiting for helper"))
                Text(h.privilegedDetail)
                Text(h.lastSetupLog)
                Text(t("Счётчики звука не доказывают доставку. Проверяй обе стороны на слух.",
                    "Audio access and frame counters do not verify remote delivery. Verify both directions on this device."))
            }
        }
        FaqSection()
        DonateFooter()
    }
}

/**
 * Optional, voluntary donation link (DonationAlerts). The app stays free (GPL-2.0-or-later);
 * this changes nothing.
 */
private const val DONATE_DA_URL = "https://www.donationalerts.com/r/hinducoder"

@Composable
private fun DonateFooter() {
    val uri = LocalUriHandler.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            t("♡ Поддержать разработку (добровольно)", "♡ Support development (optional)"),
            modifier = Modifier.clickable { runCatching { uri.openUri(DONATE_DA_URL) } },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
        )
    }
}

@Composable
private fun FaqSection() {
    var open by remember { mutableStateOf(false) }
    Section(t("Справка и частые вопросы", "Help & FAQ")) {
        OutlinedButton(onClick = { open = !open }) {
            Text(if (open) t("Скрыть", "Hide") else t("Показать справку / рекомендации клиентов", "Show help / recommended clients"))
        }
        if (open) {
            val lang = currentUiLang
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                faqEntries(lang).forEach { (q, a) ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(q, style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                        Text(a, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(rt: GatewayRuntime) {
    // Re-seed the form whenever settings change elsewhere (e.g. adb provisioning).
    val cur by rt.settingsFlow.collectAsState()
    var serverMode by remember(cur) { mutableStateOf(cur.serverMode) }
    var localPort by remember(cur) { mutableStateOf(cur.localSipPort.toString()) }
    var server by remember(cur) { mutableStateOf(cur.server) }
    var port by remember(cur) { mutableStateOf(cur.port.toString()) }
    var user by remember(cur) { mutableStateOf(cur.username) }
    var password by remember(cur) { mutableStateOf(cur.password) }
    var target by remember(cur) { mutableStateOf(cur.inboundTarget) }
    var prefixes by remember(cur) { mutableStateOf(cur.allowedPrefixes) }
    var domain by remember(cur) { mutableStateOf(cur.domain) }
    var stack by remember(cur) { mutableStateOf(cur.sipStack) }
    var transport by remember(cur) { mutableStateOf(SipTransport.of(cur.sipTransport) ?: SipTransport.UDP) }
    var srtp by remember(cur) { mutableStateOf(SrtpPolicy.of(cur.srtp) ?: SrtpPolicy.OPTIONAL) }
    var busy by remember(cur) { mutableStateOf(cur.busyPolicy) }
    var lease by remember(cur) { mutableStateOf(cur.registrarLeaseSec.toString()) }
    val builtin = stack == SipStack.BUILTIN
    Section(t("Настройки", "Settings")) {
        // Core mode: this phone is its own SIP server, or it registers to an external PBX.
        Toggle(t("Режим «устройство в себе» (встроенный SIP-сервер)", "Device-in-itself mode (built-in SIP server)"), serverMode, { serverMode = it }, info = "server_mode") {
            if (serverMode) t("Этот телефон сам — SIP-сервер; софтфон (Linphone) регистрируется прямо на него. Внешний сервер не нужен, поля PBX ниже необязательны.",
                "This phone hosts the SIP registrar; your softphone (e.g. Linphone) registers straight to it. No external PBX is needed, so the PBX fields below are optional.")
            else t("Выкл: шлюз регистрируется на внешнюю SIP-АТС. Заполни PBX host, user и password ниже.",
                "Off: this gateway registers to an external PBX / SIP server. Fill in PBX host, user and password below.")
        }
        Field(t("Порт регистратора", "Local SIP port (registrar listens here)"), localPort, KeyboardType.Number, info = "local_port") { localPort = it }
        if (serverMode) Text(t("Укажи в софтфоне  <IP этого телефона>:$localPort  по UDP.", "Point your softphone at  <this phone's IP>:$localPort  over UDP."),
            style = MaterialTheme.typography.bodySmall)
        Field(t("Аренда регистрации (сек)", "Registration lease (sec)"), lease, KeyboardType.Number, info = "lease") { lease = it }
        Choice(t("SIP-стек", "SIP stack"), SipStack.entries, stack, { it.id }, info = "sip_stack") {
            stack = it
            // The built-in stack is UDP only.
            if (it == SipStack.BUILTIN) transport = SipTransport.UDP
        }
        Choice(t("Транспорт", "Transport"), SipTransport.entries, transport, { it.id }, enabled = { !builtin || it == SipTransport.UDP }, info = "transport") {
            // Follow the default port when it was still the previous transport's default.
            if (port == transport.defaultPort.toString()) port = it.defaultPort.toString()
            transport = it
        }
        Choice(t("SRTP (только TLS)", "SRTP (TLS only)"), SrtpPolicy.entries, srtp, { it.id }, enabled = { !builtin && transport == SipTransport.TLS }, info = "srtp") {
            srtp = it
        }
        LabelWithInfo(if (serverMode) t("Внешняя АТС (необязательно в режиме «устройство в себе»)", "External PBX (optional in device-in-itself mode)") else t("Внешняя АТС", "External PBX"),
            "pbx", style = MaterialTheme.typography.bodySmall)
        Field(t("Адрес АТС (PBX host)", "PBX host"), server) { server = it }
        Field(t("Порт АТС", "PBX port"), port, KeyboardType.Number) { port = it }
        Field(t("SIP-домен (пусто = адрес АТС)", "SIP domain (empty = PBX host)"), domain) { domain = it }
        Field(t("Логин / extension", "Extension / user"), user) { user = it }
        OutlinedTextField(
            value = password, onValueChange = { password = it }, label = { Text(t("Пароль", "Password")) },
            visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Field(t("Куда переводить входящие (пусто = логин)", "Cellular inbound -> SIP target (blank = username)"), target, info = "inbound_target") { target = it }
        Field(t("Префиксы для исходящих (через запятую, пусто = любой)", "Allowed dial-out prefixes (comma separated, empty = any)"), prefixes, info = "prefixes") { prefixes = it }
        Choice(t("Второй звонок при занятости", "Second call while busy"), BusyPolicy.entries, busy,
            { when (it) { BusyPolicy.REJECT -> t("Отклонять", "Reject"); BusyPolicy.IGNORE -> t("Отдать звонилке", "Leave to phone") } }, info = "busy") {
            busy = it
        }
        Button(onClick = {
            rt.applySettings(
                cur.copy(
                    serverMode = serverMode,
                    localSipPort = localPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: cur.localSipPort,
                    registrarLeaseSec = lease.toIntOrNull()?.takeIf { it in 30..86400 } ?: cur.registrarLeaseSec,
                    server = server, port = port.toIntOrNull() ?: transport.defaultPort, username = user, password = password,
                    inboundTarget = target, allowedPrefixes = prefixes, domain = domain,
                    sipStack = stack, sipTransport = transport.id, srtp = srtp.id, busyPolicy = busy,
                ),
            )
        }) { Text(t("Сохранить и применить", "Save & apply")) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Choice(
    label: String,
    options: List<T>,
    selected: T,
    name: (T) -> String,
    enabled: (T) -> Boolean = { true },
    info: String? = null,
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LabelWithInfo(label, info, style = MaterialTheme.typography.bodySmall)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            options.forEachIndexed { i, opt ->
                SegmentedButton(
                    modifier = Modifier.fillMaxHeight(),
                    selected = opt == selected,
                    onClick = { onSelect(opt) },
                    enabled = enabled(opt),
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                ) { Text(name(opt)) }
            }
        }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit, info: String? = null, detail: () -> String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            LabelWithInfo(label, info)
            Text(detail(), style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Field(label: String, value: String, type: KeyboardType = KeyboardType.Text, info: String? = null, onChange: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = type), modifier = Modifier.weight(1f),
        )
        if (info != null) InfoButton(info)
    }
}

@Composable
private fun Section(title: String, info: String? = null, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LabelWithInfo(title, info, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Check(label: String, ok: Boolean, detail: String = "") {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (ok) "●" else "○", color = if (ok) Color(0xFF2E7D32) else Color(0xFFC62828))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(label)
            if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}
