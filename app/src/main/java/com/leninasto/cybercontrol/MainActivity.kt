package com.leninasto.cybercontrol

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import com.leninasto.cybercontrol.ui.theme.CyberControlTheme
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.sin

// --- MODELOS DE DATOS ---

data class ExtraItem(val id: String = UUID.randomUUID().toString(), val name: String, val price: Double)
enum class SessionMode { FREE, PREPAID }
data class PrepaidPreset(val label: String, val durationMillis: Long, val price: Double)

data class PriceGroup(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val cabinRange: String,
    val pricePerHour: Double,
    val presets: List<PrepaidPreset>
)

data class Sale(
    val id: Long = System.currentTimeMillis(),
    val cabinName: String,
    val amount: Double,
    val paymentMethod: String,
    val timestamp: Long = System.currentTimeMillis(),
    val startTime: Long = 0L,
    val endTime: Long = 0L
)

data class Cabin(
    val id: Int,
    val name: String,
    val isOccupied: Boolean = false,
    val isPaused: Boolean = false,
    val mode: SessionMode = SessionMode.FREE,
    val startTimeMillis: Long = 0L,
    val pausedTimeMillis: Long = 0L,
    val totalPausedDuration: Long = 0L,
    val prepaidDurationMillis: Long = 0L,
    val prepaidPrice: Double = 0.0,
    val extras: List<ExtraItem> = emptyList(),
    val notificationSent: Boolean = false
)

data class AppSettings(
    val cabinCount: Int = 10,
    val includeCabinZero: Boolean = false,
    val closingTime: String = "22:00",
    val salesRetentionDays: Int = 30,
    val priceGroups: List<PriceGroup> = listOf(
        PriceGroup(
            name = "Básico",
            cabinRange = "all",
            pricePerHour = 1.5,
            presets = listOf(
                PrepaidPreset("15 min", 15 * 60000L, 0.50),
                PrepaidPreset("30 min", 30 * 60000L, 1.00),
                PrepaidPreset("1 hora", 60 * 60000L, 1.50)
            )
        )
    ),
    val products: List<ExtraItem> = listOf(
        ExtraItem(name = "Gaseosa", price = 2.5),
        ExtraItem(name = "Galletas", price = 1.0)
    )
)

enum class AppDestinations(val label: String, val icon: ImageVector) {
    CABINS("Cabinas", Icons.Default.Home),
    STATS("Ventas", Icons.AutoMirrored.Filled.List),
    SETTINGS("Ajustes", Icons.Default.Settings),
}

// --- UTILIDADES ---

fun formatTime(millis: Long): String {
    val h = TimeUnit.MILLISECONDS.toHours(millis)
    val m = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
    val s = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
    
    return when {
        h > 0 -> "${h}h ${m}m"
        m >= 10 -> "${m}m"
        m >= 1 -> String.format(Locale.getDefault(), "%d:%02d", m, s)
        else -> s.toString()
    }
}

fun formatClockTime(millis: Long): String {
    if (millis == 0L) return "--:--"
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))
}

fun formatDate(timestamp: Long): String {
    return SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(timestamp))
}

fun isToday(timestamp: Long): Boolean {
    val fmt = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
    return fmt.format(Date(timestamp)) == fmt.format(Date())
}

fun getPriceGroupForCabin(cabinId: Int, settings: AppSettings): PriceGroup {
    val specific = settings.priceGroups.filter { it.cabinRange != "all" }.find { group ->
        val parts = group.cabinRange.split(",")
        parts.any { p ->
            if (p.contains("-")) {
                val r = p.split("-")
                val start = r[0].trim().toIntOrNull() ?: -1
                val end = r[1].trim().toIntOrNull() ?: -1
                cabinId in start..end
            } else p.trim().toIntOrNull() == cabinId
        }
    }
    return specific ?: settings.priceGroups.first { it.cabinRange == "all" }
}

fun sendNotification(context: Context, title: String, message: String) {
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val cid = "cyber_alerts"
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        nm.createNotificationChannel(NotificationChannel(cid, "Alertas", NotificationManager.IMPORTANCE_HIGH))
    }
    val n = NotificationCompat.Builder(context, cid).setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle(title).setContentText(message).setPriority(NotificationCompat.PRIORITY_HIGH).build()
    nm.notify(System.currentTimeMillis().toInt(), n)
}

// --- COMPONENTE WAVY ---

@Composable
fun CircularWavyProgressIndicator(
    progress: Float,
    isTimeUp: Boolean,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary
) {
    val infiniteTransition = rememberInfiniteTransition(label = "wavy")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(animation = tween(2000, easing = LinearEasing)),
        label = "phase"
    )

    Canvas(modifier = modifier) {
        val strokeWidth = 4.dp.toPx()
        val radius = (size.minDimension / 2) - strokeWidth
        val center = Offset(size.width / 2, size.height / 2)

        drawCircle(color = color.copy(alpha = 0.1f), radius = radius, style = Stroke(width = strokeWidth))

        val path = Path()
        val segments = 120
        val sweepAngle = 360f * progress
        
        for (i in 0..segments) {
            val angleDeg = -90f + (sweepAngle * i / segments)
            val angleRad = Math.toRadians(angleDeg.toDouble()).toFloat()
            val waveAmplitude = if (isTimeUp) 6f else 3f
            val wave = sin(i * 0.4f + phase * 6f) * waveAmplitude
            val r = radius + wave
            val x = center.x + r * cos(angleRad)
            val y = center.y + r * sin(angleRad)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        
        drawPath(
            path = path,
            color = if (isTimeUp) Color.Red else color,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )
    }
}

// --- ACTIVIDAD PRINCIPAL ---

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { CyberControlTheme { CyberControlApp() } }
    }
}

@Composable
fun CyberControlApp() {
    var currentDestination by rememberSaveable { mutableStateOf(AppDestinations.CABINS) }
    var settings by remember { mutableStateOf(AppSettings()) }
    val cabins = remember { mutableStateListOf<Cabin>() }
    val sales = remember { mutableStateListOf<Sale>() }

    LaunchedEffect(settings.cabinCount, settings.includeCabinZero) {
        val startId = if (settings.includeCabinZero) 0 else 1
        val currentActiveCabins = cabins.filter { it.isOccupied }.associateBy { it.id }
        cabins.clear()
        for (i in 0 until settings.cabinCount) {
            val id = startId + i
            cabins.add(currentActiveCabins[id] ?: Cabin(id = id, name = "PC $id"))
        }
    }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            AppDestinations.entries.forEach { dest ->
                item(
                    icon = { Icon(dest.icon, contentDescription = dest.label) },
                    label = { Text(dest.label) },
                    selected = dest == currentDestination,
                    onClick = { currentDestination = dest }
                )
            }
        }
    ) {
        Scaffold(modifier = Modifier.fillMaxSize(), topBar = { ClosingTimeBar(settings.closingTime) }) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding)) {
                when (currentDestination) {
                    AppDestinations.CABINS -> CabinsScreen(cabins, settings) { sale -> sales.add(sale) }
                    AppDestinations.STATS -> StatsScreen(sales)
                    AppDestinations.SETTINGS -> SettingsScreen(settings, onSettingsChange = { settings = it })
                }
            }
        }
    }
}

@Composable
fun ClosingTimeBar(closingTimeStr: String) {
    val currentTime = remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) { while (true) { currentTime.value = LocalTime.now(); delay(1000) } }
    val closingTime = try { LocalTime.parse(closingTimeStr, DateTimeFormatter.ofPattern("HH:mm")) } catch (e: Exception) { LocalTime.of(22, 0) }
    val minutesToClose = ChronoUnit.MINUTES.between(currentTime.value, closingTime)
    if (minutesToClose in 1..60) {
        Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.padding(8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(8.dp)); Text("Cierre en $minutesToClose min ($closingTimeStr)", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun CabinsScreen(cabins: MutableList<Cabin>, settings: AppSettings, onSaleRecorded: (Sale) -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Panel de Cabinas", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 80.dp)) {
            items(cabins, key = { it.id }) { cabin ->
                val group = remember(cabin.id, settings) { getPriceGroupForCabin(cabin.id, settings) }
                CabinCard(cabin, group, settings, onUpdate = { updated ->
                    val idx = cabins.indexOfFirst { it.id == cabin.id }
                    if (idx != -1) cabins[idx] = updated
                }, onStop = { sale ->
                    onSaleRecorded(sale)
                    val idx = cabins.indexOfFirst { it.id == cabin.id }
                    if (idx != -1) cabins[idx] = Cabin(id = cabin.id, name = cabin.name)
                })
            }
        }
    }
}

@Composable
fun CabinCard(cabin: Cabin, group: PriceGroup, settings: AppSettings, onUpdate: (Cabin) -> Unit, onStop: (Sale) -> Unit) {
    val context = LocalContext.current
    var currentTimeMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var showStartDialog by remember { mutableStateOf(false) }
    var showAddTimeDialog by remember { mutableStateOf(false) }
    var showExtraDialog by remember { mutableStateOf(false) }
    var showSummaryDialog by remember { mutableStateOf(false) }

    LaunchedEffect(cabin.isOccupied, cabin.isPaused) {
        while (cabin.isOccupied && !cabin.isPaused) { delay(1000); currentTimeMillis = System.currentTimeMillis() }
    }

    val elapsed = if (cabin.isOccupied) {
        if (cabin.isPaused) cabin.pausedTimeMillis - cabin.startTimeMillis - cabin.totalPausedDuration
        else currentTimeMillis - cabin.startTimeMillis - cabin.totalPausedDuration
    } else 0L

    val isTimeUp = cabin.mode == SessionMode.PREPAID && elapsed >= cabin.prepaidDurationMillis
    val timeRem = if (cabin.mode == SessionMode.PREPAID) (cabin.prepaidDurationMillis - elapsed).coerceAtLeast(0L) else 0L

    LaunchedEffect(isTimeUp) {
        if (isTimeUp && !cabin.notificationSent) {
            sendNotification(context, "Tiempo Agotado", "La ${cabin.name} ha terminado.")
            onUpdate(cabin.copy(notificationSent = true))
        }
    }

    val cost = if (cabin.mode == SessionMode.PREPAID) cabin.prepaidPrice + cabin.extras.sumOf { it.price }
    else (elapsed / 3600000.0) * group.pricePerHour + cabin.extras.sumOf { it.price }

    ElevatedCard(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.elevatedCardColors(
        containerColor = when {
            isTimeUp -> MaterialTheme.colorScheme.errorContainer
            cabin.isPaused -> MaterialTheme.colorScheme.secondaryContainer
            cabin.isOccupied -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surface
        }
    )) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(60.dp)) {
                Icon(Icons.Default.Computer, null, modifier = Modifier.size(40.dp), tint = when {
                    cabin.isPaused -> Color(0xFFFFC107); cabin.isOccupied -> Color.Green; else -> Color.Gray
                })
                Text(cabin.name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                if (cabin.isOccupied) {
                    if (cabin.mode == SessionMode.PREPAID) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val progressValue = (timeRem.toFloat() / (if (cabin.prepaidDurationMillis > 0) cabin.prepaidDurationMillis.toFloat() else 1f)).coerceIn(0f, 1f)
                            CircularWavyProgressIndicator(progress = progressValue, isTimeUp = isTimeUp, modifier = Modifier.size(40.dp))
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(formatTime(timeRem), fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleLarge)
                                Text("Inicio: ${formatClockTime(cabin.startTimeMillis)} | Fin: ${formatClockTime(cabin.startTimeMillis + cabin.prepaidDurationMillis + cabin.totalPausedDuration)}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    } else {
                        Text(formatTime(elapsed), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                        Text("Inicio: ${formatClockTime(cabin.startTimeMillis)}", style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Text("Disponible", style = MaterialTheme.typography.titleMedium, color = Color.Gray)
                }
                Text("${group.name}: S/ ${String.format(Locale.getDefault(), "%.2f", group.pricePerHour)}/h", style = MaterialTheme.typography.bodySmall)
            }
            Column(horizontalAlignment = Alignment.End) {
                if (cabin.isOccupied) Text(text = String.format(Locale.getDefault(), "S/ %.2f", cost), fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!cabin.isOccupied) Button(onClick = { showStartDialog = true }) { Text("INICIAR") }
                    else {
                        IconButton(onClick = { showExtraDialog = true }) { Icon(Icons.Default.ShoppingCart, null) }
                        if (cabin.mode == SessionMode.PREPAID) {
                            IconButton(onClick = { showAddTimeDialog = true }) { Icon(Icons.Default.AddAlarm, null) }
                        }
                        IconButton(onClick = {
                            if (cabin.isPaused) {
                                val pauseEnd = System.currentTimeMillis()
                                onUpdate(cabin.copy(isPaused = false, totalPausedDuration = cabin.totalPausedDuration + (pauseEnd - cabin.pausedTimeMillis)))
                            } else {
                                onUpdate(cabin.copy(isPaused = true, pausedTimeMillis = System.currentTimeMillis()))
                            }
                        }) { Icon(if (cabin.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause, null) }
                        IconButton(onClick = { showSummaryDialog = true }) { Icon(Icons.Default.Clear, null, tint = Color.Red) }
                    }
                }
            }
        }
    }

    if (showStartDialog) StartSessionDialog(group, { showStartDialog = false }, { m, d, p -> onUpdate(cabin.copy(isOccupied = true, mode = m, prepaidDurationMillis = d, prepaidPrice = p, startTimeMillis = System.currentTimeMillis())); showStartDialog = false })
    if (showAddTimeDialog) StartSessionDialog(group, { showAddTimeDialog = false }, { _, d, p -> onUpdate(cabin.copy(prepaidDurationMillis = cabin.prepaidDurationMillis + d, prepaidPrice = cabin.prepaidPrice + p, notificationSent = false)); showAddTimeDialog = false }, isAdding = true)
    if (showExtraDialog) AddExtraDialog(settings.products, { showExtraDialog = false }, { onUpdate(cabin.copy(extras = cabin.extras + it)); showExtraDialog = false })
    if (showSummaryDialog) SummaryDialog(cabin, cost, { showSummaryDialog = false }, { method -> onStop(Sale(cabinName = cabin.name, amount = cost, paymentMethod = method, startTime = cabin.startTimeMillis, endTime = System.currentTimeMillis())); showSummaryDialog = false })
}

@Composable
fun SummaryDialog(cabin: Cabin, cost: Double, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var selectedMethod by remember { mutableStateOf("Efectivo") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Cerrar Cuenta") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Sesión: ${cabin.name}", fontWeight = FontWeight.Bold)
            Text("Inicio: ${formatClockTime(cabin.startTimeMillis)}")
            Text("Fin: ${formatClockTime(System.currentTimeMillis())}")
            HorizontalDivider()
            Text(text = String.format(Locale.getDefault(), "Total: S/ %.2f", cost), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            if (cabin.extras.isNotEmpty()) {
                Text("Extras:", fontWeight = FontWeight.Bold)
                cabin.extras.forEach { Text("- ${it.name}: S/ ${String.format("%.2f", it.price)}") }
            }
            Text("Pago por:")
            Row(Modifier.selectableGroup()) {
                ModeOption("Efectivo", selectedMethod == "Efectivo") { selectedMethod = "Efectivo" }
                ModeOption("Yape", selectedMethod == "Yape") { selectedMethod = "Yape" }
            }
        }
    }, confirmButton = { Button(onClick = { onConfirm(selectedMethod) }) { Text("PAGAR") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("CANCELAR") } })
}

@Composable
fun StatsScreen(sales: MutableList<Sale>) {
    val groupedSales = sales.groupBy { formatDate(it.timestamp) }.toList().sortedByDescending { it.first }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Historial de Ventas", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(groupedSales) { (date, dailySales) ->
                val isToday = isToday(dailySales.first().timestamp)
                var expanded by remember { mutableStateOf(isToday) }
                val totalEfectivo = dailySales.filter { it.paymentMethod == "Efectivo" }.sumOf { it.amount }
                val totalYape = dailySales.filter { it.paymentMethod == "Yape" }.sumOf { it.amount }
                Card(modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
                    Column(Modifier.padding(16.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(if (isToday) "HOY ($date)" else date, fontWeight = FontWeight.Bold)
                            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                        }
                        Text(text = String.format(Locale.getDefault(), "Total Día: S/ %.2f", totalEfectivo + totalYape), style = MaterialTheme.typography.bodySmall)
                        if (expanded) {
                            HorizontalDivider(Modifier.padding(vertical = 8.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(text = String.format(Locale.getDefault(), "Efectivo: S/ %.2f", totalEfectivo), color = MaterialTheme.colorScheme.primary)
                                Text(text = String.format(Locale.getDefault(), "Yape: S/ %.2f", totalYape), color = Color(0xFF8E44AD))
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            dailySales.forEach { sale ->
                                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("${sale.cabinName} (${sale.paymentMethod})", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                        Text(text = String.format(Locale.getDefault(), "S/ %.2f", sale.amount), style = MaterialTheme.typography.bodySmall)
                                    }
                                    Text("${formatClockTime(sale.startTime)} - ${formatClockTime(sale.endTime)}", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                }
                            }
                            Button(onClick = { sales.removeAll(dailySales) }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), modifier = Modifier.padding(top = 8.dp).fillMaxWidth()) { Text("Borrar registros") }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StartSessionDialog(group: PriceGroup, onDismiss: () -> Unit, onStart: (SessionMode, Long, Double) -> Unit, isAdding: Boolean = false) {
    var mode by remember { mutableStateOf(if (isAdding) SessionMode.PREPAID else SessionMode.FREE) }
    var showCustomDuration by remember { mutableStateOf(false) }
    var customMinutes by remember { mutableStateOf("") }

    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (isAdding) "Aumentar Tiempo" else "Iniciar PC - ${group.name}") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!isAdding) {
                Row(Modifier.selectableGroup()) {
                    ModeOption("Libre", mode == SessionMode.FREE) { mode = SessionMode.FREE }
                    ModeOption("Prepago", mode == SessionMode.PREPAID) { mode = SessionMode.PREPAID }
                }
            }
            if (mode == SessionMode.PREPAID) {
                if (!showCustomDuration) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        group.presets.forEach { preset ->
                            FilterChip(selected = false, onClick = { onStart(mode, preset.durationMillis, preset.price) }, label = { Text("${preset.label} - S/ ${String.format(Locale.getDefault(), "%.2f", preset.price)}") })
                        }
                        FilterChip(selected = false, onClick = { showCustomDuration = true }, label = { Text("Personalizado...") }, leadingIcon = { Icon(Icons.Default.Edit, null, modifier = Modifier.size(16.dp)) })
                    }
                } else {
                    OutlinedTextField(
                        value = customMinutes,
                        onValueChange = { customMinutes = it.filter { char -> char.isDigit() } },
                        label = { Text("Minutos") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = { showCustomDuration = false }) { Text("Volver") }
                        Button(onClick = {
                            val mins = customMinutes.toLongOrNull() ?: 0L
                            val price = (mins / 60.0) * group.pricePerHour
                            onStart(mode, mins * 60000L, price)
                        }) { Text("Confirmar") }
                    }
                }
            }
        }
    }, confirmButton = { if (mode == SessionMode.FREE && !isAdding) Button(onClick = { onStart(mode, 0, 0.0) }) { Text("INICIAR") } })
}

@Composable
fun AddExtraDialog(products: List<ExtraItem>, onDismiss: () -> Unit, onAdd: (ExtraItem) -> Unit) {
    var showCustom by remember { mutableStateOf(false) }
    var cName by remember { mutableStateOf("") }
    var cPrice by remember { mutableStateOf("") }

    AlertDialog(onDismissRequest = onDismiss, confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } }, title = { Text("Añadir Consumo") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!showCustom) {
                Button(onClick = { showCustom = true }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.filledTonalButtonColors()) {
                    Icon(Icons.Default.Edit, null); Spacer(Modifier.width(8.dp)); Text("Producto Manual")
                }
                LazyColumn(modifier = Modifier.heightIn(max = 250.dp)) {
                    items(products) { p ->
                        ListItem(headlineContent = { Text(p.name) }, supportingContent = { Text("S/ ${String.format("%.2f", p.price)}") }, trailingContent = { IconButton(onClick = { onAdd(p) }) { Icon(Icons.Default.Add, null) } })
                    }
                }
            } else {
                OutlinedTextField(value = cName, onValueChange = { cName = it }, label = { Text("Nombre") })
                OutlinedTextField(value = cPrice, onValueChange = { cPrice = it }, label = { Text("Precio") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { showCustom = false }) { Text("Volver") }
                    Button(onClick = {
                        val price = cPrice.toDoubleOrNull() ?: 0.0
                        onAdd(ExtraItem(name = cName, price = price))
                        showCustom = false; cName = ""; cPrice = ""
                    }) { Text("Añadir") }
                }
            }
        }
    })
}

@Composable
fun ModeOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .padding(end = 8.dp)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .clip(MaterialTheme.shapes.small),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(label, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }
}

@Composable
fun SettingsScreen(settings: AppSettings, onSettingsChange: (AppSettings) -> Unit) {
    var showAddGroup by remember { mutableStateOf(false) }
    var showProductDialog by remember { mutableStateOf<ExtraItem?>(null) }
    var editingGroupPresets by remember { mutableStateOf<PriceGroup?>(null) }

    Column(Modifier.padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("Configuración", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        OutlinedTextField(value = settings.cabinCount.toString(), onValueChange = { it.toIntOrNull()?.let { n -> onSettingsChange(settings.copy(cabinCount = n)) } }, label = { Text("Número de Cabinas") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Incluir Cabina 0", modifier = Modifier.weight(1f))
            Switch(checked = settings.includeCabinZero, onCheckedChange = { onSettingsChange(settings.copy(includeCabinZero = it)) })
        }
        OutlinedTextField(value = settings.closingTime, onValueChange = { onSettingsChange(settings.copy(closingTime = it)) }, label = { Text("Hora Cierre (HH:mm)") }, modifier = Modifier.fillMaxWidth())

        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Zonas de Precios", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { showAddGroup = true }) { Icon(Icons.Default.Add, null) }
        }
        settings.priceGroups.forEach { group ->
            ListItem(
                headlineContent = { Text("${group.name} (${group.cabinRange})") },
                supportingContent = { Text("S/ ${String.format("%.2f", group.pricePerHour)}/h | ${group.presets.size} presets") },
                trailingContent = {
                    Row {
                        IconButton(onClick = { editingGroupPresets = group }) { Icon(Icons.Default.Settings, null) }
                        if (group.cabinRange != "all") {
                            IconButton(onClick = { onSettingsChange(settings.copy(priceGroups = settings.priceGroups - group)) }) {
                                Icon(Icons.Default.Delete, null, tint = Color.Red)
                            }
                        }
                    }
                }
            )
        }

        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Productos / Snacks", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { showProductDialog = ExtraItem(name = "", price = 0.0) }) { Icon(Icons.Default.Add, null) }
        }
        settings.products.forEach { p ->
            ListItem(
                modifier = Modifier.clickable { showProductDialog = p },
                headlineContent = { Text(p.name) },
                supportingContent = { Text("S/ ${String.format("%.2f", p.price)}") },
                trailingContent = {
                    IconButton(onClick = { onSettingsChange(settings.copy(products = settings.products - p)) }) {
                        Icon(Icons.Default.Delete, null, tint = Color.Red)
                    }
                }
            )
        }
    }

    if (showAddGroup) {
        var n by remember { mutableStateOf("") }; var r by remember { mutableStateOf("") }; var p by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { showAddGroup = false }, title = { Text("Nueva Zona") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = n, onValueChange = { n = it }, label = { Text("Nombre") })
                OutlinedTextField(value = r, onValueChange = { r = it }, label = { Text("Rango (ej. 1-3)") })
                OutlinedTextField(value = p, onValueChange = { p = it }, label = { Text("Precio/h") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
        }, confirmButton = {
            Button(onClick = {
                val price = p.toDoubleOrNull() ?: 0.0
                onSettingsChange(settings.copy(priceGroups = settings.priceGroups + PriceGroup(name = n, cabinRange = r, pricePerHour = price, presets = listOf(
                    PrepaidPreset("15 min", 15 * 60000L, price * 0.33),
                    PrepaidPreset("30 min", 30 * 60000L, price * 0.66),
                    PrepaidPreset("1 hora", 60 * 60000L, price)
                ))))
                showAddGroup = false
            }) { Text("Guardar") }
        }, dismissButton = { TextButton(onClick = { showAddGroup = false }) { Text("Cancelar") } })
    }

    editingGroupPresets?.let { group ->
        var tempPresets by remember { mutableStateOf(group.presets) }
        var tempPriceH by remember { mutableStateOf(group.pricePerHour.toString()) }
        AlertDialog(onDismissRequest = { editingGroupPresets = null }, title = { Text("Ajustes: ${group.name}") }, text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = tempPriceH, onValueChange = { tempPriceH = it }, label = { Text("Precio Hora Base") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                Text("Preajustes Prepago:", fontWeight = FontWeight.Bold)
                tempPresets.forEachIndexed { index, preset ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(preset.label, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = preset.price.toString(), onValueChange = { val p = it.toDoubleOrNull() ?: 0.0; tempPresets = tempPresets.toMutableList().apply { set(index, preset.copy(price = p)) } }, modifier = Modifier.width(80.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    }
                }
            }
        }, confirmButton = {
            Button(onClick = {
                val newList = settings.priceGroups.toMutableList()
                val idx = newList.indexOfFirst { it.id == group.id }
                if (idx != -1) newList[idx] = group.copy(presets = tempPresets, pricePerHour = tempPriceH.toDoubleOrNull() ?: group.pricePerHour)
                onSettingsChange(settings.copy(priceGroups = newList))
                editingGroupPresets = null
            }) { Text("Guardar") }
        })
    }

    showProductDialog?.let { editing ->
        var name by remember { mutableStateOf(editing.name) }
        var priceStr by remember { mutableStateOf(if(editing.price > 0) editing.price.toString() else "") }
        AlertDialog(onDismissRequest = { showProductDialog = null }, title = { Text(if(editing.name.isEmpty()) "Nuevo Producto" else "Editar Producto") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nombre") })
                OutlinedTextField(value = priceStr, onValueChange = { priceStr = it }, label = { Text("Precio") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
        }, confirmButton = {
            Button(onClick = {
                val price = priceStr.toDoubleOrNull() ?: 0.0
                val newList = settings.products.toMutableList()
                val idx = newList.indexOfFirst { it.id == editing.id }
                if (idx != -1) newList[idx] = editing.copy(name = name, price = price) else newList.add(ExtraItem(name = name, price = price))
                onSettingsChange(settings.copy(products = newList))
                showProductDialog = null
            }) { Text("Guardar") }
        }, dismissButton = { TextButton(onClick = { showProductDialog = null }) { Text("Cancelar") } })
    }
}
