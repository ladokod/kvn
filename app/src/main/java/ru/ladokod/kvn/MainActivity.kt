package ru.ladokod.kvn

import android.app.Activity
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

private val Navy = Color(0xFF09111D)
private val Panel = Color(0xFF142332)
private val Mint = Color(0xFF6DEBCE)
private val Muted = Color(0xFFA3B6C7)
private val Light = Color(0xFFF1FAFF)
private val Warning = Color(0xFFFFB39C)

@OptIn(ExperimentalFoundationApi::class)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = SecretStore(this)
        CrashMonitor.previousAbnormalExit(this)?.takeIf { VpnState.state.value == ConnectionState.Off }?.let { previous ->
            VpnState.publish(ConnectionState.Failed(previous))
        }
        setContent {
            val status by VpnState.state.collectAsState()
            var savedProfile by remember { mutableStateOf(runCatching { store.load() }.getOrNull()) }
            var diagnostics by remember { mutableStateOf(false) }
            var diagnosticResult by remember { mutableStateOf("Автоматическая проверка не выполнялась") }
            var editing by remember { mutableStateOf(savedProfile == null) }
            val launcher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) { result ->
                if (result.resultCode == Activity.RESULT_OK &&
                    VpnService.prepare(this) == null) {
                    runCatching { KvnVpnService.connect(this) }
                        .onFailure {
                            VpnState.publish(ConnectionState.Failed("Не удалось запустить VPN"))
                        }
                } else {
                    VpnState.publish(ConnectionState.Failed("Android не разрешил подключение VPN"))
                }
            }
            val connect: () -> Unit = {
                if (savedProfile == null) {
                    editing = true
                } else if ((status is ConnectionState.Failed && (status as ConnectionState.Failed).tunnelActive) || status == ConnectionState.TunnelCreated || status == ConnectionState.On || status == ConnectionState.Starting || status == ConnectionState.Checking) {
                    KvnVpnService.disconnect(this)
                } else {
                    val consent = VpnService.prepare(this)
                    if (consent != null) launcher.launch(consent)
                    else runCatching { KvnVpnService.connect(this) }
                        .onFailure {
                            VpnState.publish(ConnectionState.Failed("Не удалось запустить VPN"))
                        }
                }
            }

            MaterialTheme(colorScheme = darkColorScheme(
                primary = Mint, background = Navy, surface = Panel,
                onSurface = Light, onBackground = Light
            )) {
                MainScreen(
                    profile = savedProfile,
                    state = status,
                    onConnect = connect,
                    onSettings = { editing = true },
                    onDiagnostics = { diagnostics = true }
                )
                if (diagnostics) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { diagnostics = false },
                        title = { Text("Диагностика · эксперимент") },
                        text = { Text((status as? ConnectionState.Failed)?.message.orEmpty() + "\n" + diagnosticResult) },
                        confirmButton = { TextButton(onClick = {
                            diagnosticResult = "Проверяем HTTPS через VPN…"
                            val checkedRevision = VpnState.revision
                            Thread {
                                val problem = runCatching { NetworkChecks.testHttpsInsideVpn(this) }
                                    .getOrElse { "Проверка недоступна в этом контексте Android" }
                                runOnUiThread {
                                    if (checkedRevision != VpnState.revision) {
                                        diagnosticResult = "Состояние VPN изменилось; повтори проверку"
                                        return@runOnUiThread
                                    }
                                    diagnosticResult = problem ?: "HTTPS-запрос через VPN выполнен. Это не проверка всех приложений."
                                    if (problem == null && VpnState.state.value == ConnectionState.TunnelCreated)
                                        VpnState.publish(ConnectionState.On)
                                }
                            }.start()
                        }) { Text("Проверить HTTPS") } },
                        dismissButton = { TextButton(onClick = { diagnostics = false }) { Text("Закрыть") } }
                    )
                }
                if (editing) {
                    SettingsDialog(
                        profile = savedProfile,
                        onDismiss = { if (savedProfile != null) editing = false },
                        onSave = { server, login, pass ->
                            val current = VpnState.state.value
                            require(current == ConnectionState.Off || (current is ConnectionState.Failed && !current.tunnelActive)) {
                                "Сначала отключи VPN, чтобы изменить сервер"
                            }
                            val next = ProfileValidator.validate(server, login, pass)
                            store.save(next)
                            savedProfile = next
                            editing = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun MainScreen(
    profile: ServerProfile?,
    state: ConnectionState,
    onConnect: () -> Unit,
    onSettings: () -> Unit,
    onDiagnostics: () -> Unit
) {
    val connected = state == ConnectionState.On || state == ConnectionState.TunnelCreated
    val loading = state == ConnectionState.Starting || state == ConnectionState.Checking
    val accent by animateFloatAsState(
        targetValue = if (connected) 1f else 0f, label = "connection-glow"
    )
    val status = when (state) {
        is ConnectionState.On -> "СОЕДИНЕНИЕ ПРОВЕРЕНО"
        is ConnectionState.TunnelCreated -> "ТУННЕЛЬ СОЗДАН · ДОСТУП НЕ ПРОВЕРЕН"
        is ConnectionState.Starting -> "ПОДКЛЮЧАЕМСЯ"
        is ConnectionState.Checking -> "ПРОВЕРЯЕМ ИНТЕРНЕТ"
        is ConnectionState.Failed -> "ОШИБКА ПОДКЛЮЧЕНИЯ"
        is ConnectionState.Off -> "НЕ ПОДКЛЮЧЕНО"
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Navy, Color(0xFF102334), Navy)))
            .systemBarsPadding()
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).background(Mint, CircleShape))
                    Spacer(Modifier.width(10.dp))
                    Text("KVN", modifier = Modifier.combinedClickable(onClick = {}, onLongClick = onDiagnostics), fontSize = 29.sp, fontWeight = FontWeight.ExtraBold, color = Light)
                }
                Surface(
                    onClick = onSettings,
                    shape = RoundedCornerShape(16.dp),
                    color = Color.White.copy(alpha = 0.07f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
                ) {
                    Text(
                        "Настройки",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        fontSize = 13.sp, color = Light
                    )
                }
            }

            Spacer(Modifier.weight(0.8f))
            Text("ТВОЙ ИНТЕРНЕТ", color = Muted, letterSpacing = 2.sp, fontSize = 11.sp)
            Spacer(Modifier.height(10.dp))
            Text(
                if (state == ConnectionState.On) "На связи" else if (connected) "Туннель создан" else "Одно касание",
                fontSize = 35.sp, fontWeight = FontWeight.Bold, color = Light
            )
            Spacer(Modifier.height(12.dp))
            Text(
                if (state == ConnectionState.On) "HTTPS проверен · проверь приложения"
                else if (connected) "Доступ ещё не подтверждён"
                else "Подключайся к своему серверу",
                textAlign = TextAlign.Center, color = Muted, fontSize = 15.sp
            )
            Spacer(Modifier.height(42.dp))

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(252.dp)
                    .shadow(if (connected) 32.dp else 15.dp, CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(
                                if (connected) Mint.copy(alpha = 0.27f) else Color(0xFF264456),
                                Color(0xFF112A3A),
                                Color(0xFF0C1725)
                            )
                        ),
                        CircleShape
                    )
                    .border(
                        width = 2.dp,
                        color = if (connected) Mint.copy(alpha = 0.7f + 0.3f * accent)
                                else Color.White.copy(alpha = 0.2f),
                        shape = CircleShape
                    )
                    .clickable(
                        enabled = true,
                        role = Role.Button,
                        onClick = onConnect
                    )
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (loading) {
                        CircularProgressIndicator(
                            color = Mint,
                            strokeWidth = 4.dp,
                            modifier = Modifier.size(58.dp)
                        )
                    } else {
                        Text(
                            "⏻",
                            color = if (connected) Mint else Light,
                            fontSize = 76.sp,
                            lineHeight = 80.sp
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (loading) "ОТКЛЮЧИТЬ"
                        else if (connected) "ОТКЛЮЧИТЬ"
                        else if (state is ConnectionState.Failed && state.tunnelActive) "ОТКЛЮЧИТЬ"
                        else "ПОДКЛЮЧИТЬ",
                        color = if (connected) Mint else Light,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        letterSpacing = 1.sp
                    )
                }
            }

            Spacer(Modifier.height(27.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(7.dp).background(
                        if (connected) Mint else if (state is ConnectionState.Failed) Warning else Muted,
                        CircleShape
                    )
                )
                Spacer(Modifier.width(9.dp))
                Text(status, color = if (connected) Mint else Muted, fontSize = 11.sp, letterSpacing = 1.sp)
            }
            if (state is ConnectionState.Failed) {
                Spacer(Modifier.height(12.dp))
                Text(
                    if (state.tunnelActive) "Ошибка ядра. Трафик заблокирован до отключения VPN." else "VPN отключён. Долгое нажатие на KVN открывает диагностику.",
                    color = Warning,
                    textAlign = TextAlign.Center,
                    fontSize = 13.sp
                )
            }
            Spacer(Modifier.weight(1f))

            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color.White.copy(alpha = 0.055f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.09f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text("СЕРВЕР", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp)
                    Spacer(Modifier.height(7.dp))
                    Text(
                        profile?.host ?: "Не настроен",
                        color = Light,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )

                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "Личный VPN · без подписок и рекламы",
                color = Muted.copy(alpha = 0.7f), fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun SettingsDialog(
    profile: ServerProfile?,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit
) {
    var host by remember(profile) { mutableStateOf(profile?.host.orEmpty()) }
    var login by remember(profile) { mutableStateOf(profile?.username.orEmpty()) }
    var password by remember(profile) { mutableStateOf(profile?.password.orEmpty()) }
    var passwordVisible by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = Panel,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text("Настройки", color = Light, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Данные от твоего сервера. Соединение настроим автоматически.",
                    color = Muted, fontSize = 13.sp
                )
                Spacer(Modifier.height(22.dp))
                OutlinedTextField(
                    value = host, onValueChange = { host = it; error = null },
                    label = { Text("Адрес сервера") },
                    placeholder = { Text("vpn.example.com") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = login, onValueChange = { login = it; error = null },
                    label = { Text("Логин") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password, onValueChange = { password = it; error = null },
                    label = { Text("Пароль") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = if (passwordVisible) VisualTransformation.None
                                           else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { passwordVisible = !passwordVisible }) {
                            Text(if (passwordVisible) "Скрыть" else "Показать", fontSize = 12.sp)
                        }
                    }
                )
                if (error != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(error!!, color = Warning, fontSize = 12.sp)
                }
                Spacer(Modifier.height(23.dp))
                Button(
                    onClick = {
                        try { onSave(host, login, password) }
                        catch (e: IllegalArgumentException) {
                            error = e.message ?: "Проверь введённые данные"
                        } catch (_: Exception) {
                            error = "Не удалось сохранить настройки на устройстве"
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Navy),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().height(54.dp)
                ) {
                    Text("Сохранить", fontWeight = FontWeight.Bold)
                }
                if (profile != null) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Отмена", color = Muted) }
                }
            }
        }
    }
}
