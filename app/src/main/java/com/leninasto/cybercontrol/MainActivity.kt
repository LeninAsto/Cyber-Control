package com.leninasto.cybercontrol

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import com.leninasto.cybercontrol.ui.theme.CyberControlTheme
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.round
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
    val notificationSent: Boolean = false,
    val transferBalance: Double = 0.0
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
    ),
    val roundingStep: Double = 0.10
)

enum class AppDestinations(val label: String, val icon: ImageVector) {
    CABINS("Cabinas", Icons.Default.Computer),
    STATS("Ventas", Icons.AutoMirrored.Filled.List),
    SETTINGS("Ajustes", Icons.Default.Settings),
    INFO("Info", Icons.Default.Info),
}

// --- UTILIDADES ---

fun applyRounding(value: Double, step: Double): Double {
    if (step <= 0.01) return value
    return round(value / step) * step
}

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

// --- PERSISTENCIA ---

fun saveAppSettings(context: Context, settings: AppSettings) {
    val prefs = context.getSharedPreferences("cyber_prefs", Context.MODE_PRIVATE)
    val json = JSONObject().apply {
        put("cabinCount", settings.cabinCount)
        put("includeCabinZero", settings.includeCabinZero)
        put("closingTime", settings.closingTime)
        put("roundingStep", settings.roundingStep)
        put("priceGroups", JSONArray().apply {
            settings.priceGroups.forEach { group ->
                put(JSONObject().apply {
                    put("id", group.id)
                    put("name", group.name)
                    put("cabinRange", group.cabinRange)
                    put("pricePerHour", group.pricePerHour)
                    put("presets", JSONArray().apply {
                        group.presets.forEach { put(JSONObject().apply {
                            put("label", it.label)
                            put("durationMillis", it.durationMillis)
                            put("price", it.price)
                        }) }
                    })
                })
            }
        })
        put("products", JSONArray().apply {
            settings.products.forEach { put(JSONObject().apply {
                put("id", it.id)
                put("name", it.name)
                put("price", it.price)
            }) }
        })
    }
    prefs.edit().putString("settings", json.toString()).apply()
}

fun loadAppSettings(context: Context): AppSettings {
    val prefs = context.getSharedPreferences("cyber_prefs", Context.MODE_PRIVATE)
    val jsonStr = prefs.getString("settings", null) ?: return AppSettings()
    return try {
        val json = JSONObject(jsonStr)
        val groups = mutableListOf<PriceGroup>()
        val groupsArray = json.optJSONArray("priceGroups")
        if (groupsArray != null) {
            for (i in 0 until groupsArray.length()) {
                val g = groupsArray.getJSONObject(i)
                val presets = mutableListOf<PrepaidPreset>()
                val ps = g.optJSONArray("presets")
                if (ps != null) {
                    for (j in 0 until ps.length()) {
                        val p = ps.getJSONObject(j)
                        presets.add(PrepaidPreset(p.getString("label"), p.getLong("durationMillis"), p.getDouble("price")))
                    }
                }
                groups.add(PriceGroup(g.getString("id"), g.getString("name"), g.getString("cabinRange"), g.getDouble("pricePerHour"), presets))
            }
        }
        val prods = mutableListOf<ExtraItem>()
        val prodsArray = json.optJSONArray("products")
        if (prodsArray != null) {
            for (i in 0 until prodsArray.length()) {
                val p = prodsArray.getJSONObject(i)
                prods.add(ExtraItem(p.getString("id"), p.getString("name"), p.getDouble("price")))
            }
        }
        AppSettings(
            cabinCount = json.optInt("cabinCount", 10),
            includeCabinZero = json.optBoolean("includeCabinZero", false),
            closingTime = json.optString("closingTime", "22:00"),
            roundingStep = json.optDouble("roundingStep", 0.10),
            priceGroups = if (groups.isEmpty()) AppSettings().priceGroups else groups,
            products = if (prods.isEmpty()) AppSettings().products else prods
        )
    } catch (e: Exception) { AppSettings() }
}

fun saveSales(context: Context, sales: List<Sale>) {
    val prefs = context.getSharedPreferences("cyber_prefs", Context.MODE_PRIVATE)
    val array = JSONArray()
    sales.forEach { s ->
        array.put(JSONObject().apply {
            put("id", s.id); put("cabinName", s.cabinName); put("amount", s.amount)
            put("paymentMethod", s.paymentMethod); put("timestamp", s.timestamp)
            put("startTime", s.startTime); put("endTime", s.endTime)
        })
    }
    prefs.edit().putString("sales", array.toString()).apply()
}

fun loadSales(context: Context): List<Sale> {
    val prefs = context.getSharedPreferences("cyber_prefs", Context.MODE_PRIVATE)
    val jsonStr = prefs.getString("sales", null) ?: return emptyList()
    return try {
        val array = JSONArray(jsonStr)
        val list = mutableListOf<Sale>()
        for (i in 0 until array.length()) {
            val s = array.getJSONObject(i)
            list.add(Sale(s.getLong("id"), s.getString("cabinName"), s.getDouble("amount"), s.getString("paymentMethod"), s.getLong("timestamp"), s.optLong("startTime"), s.optLong("endTime")))
        }
        list
    } catch (e: Exception) { emptyList() }
}

fun saveCabins(context: Context, cabins: List<Cabin>) {
    val prefs = context.getSharedPreferences("cyber_prefs", Context.MODE_PRIVATE)
    val array = JSONArray()
    cabins.forEach { c ->
        array.put(JSONObject().apply {
            put("id", c.id); put("name", c.name); put("isOccupied", c.isOccupied)
            put("isPaused", c.isPaused); put("mode", c.mode.name)
            put("startTimeMillis", c.startTimeMillis); put("pausedTimeMillis", c.pausedTimeMillis)
            put("totalPausedDuration", c.totalPausedDuration)
            put("prepaidDurationMillis", c.prepaidDurationMillis)
            put("prepaidPrice", c.prepaidPrice); put("notificationSent", c.notificationSent)
            put("transferBalance", c.transferBalance)
            val exArr = JSONArray()
            c.extras.forEach { e -> exArr.put(JSONObject().apply { put("id", e.id); put("name", e.name); put("price", e.price) }) }
            put("extras", exArr)
        })
    }
    prefs.edit().putString("cabins", array.toString()).apply()
}

fun loadCabins(context: Context): List<Cabin> {
    val prefs = context.getSharedPreferences("cyber_prefs", Context.MODE_PRIVATE)
    val jsonStr = prefs.getString("cabins", null) ?: return emptyList()
    return try {
        val array = JSONArray(jsonStr)
        val list = mutableListOf<Cabin>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val extras = mutableListOf<ExtraItem>()
            val exArr = obj.optJSONArray("extras")
            if (exArr != null) {
                for (j in 0 until exArr.length()) {
                    val e = exArr.getJSONObject(j)
                    extras.add(ExtraItem(e.getString("id"), e.getString("name"), e.getDouble("price")))
                }
            }
            list.add(Cabin(
                id = obj.getInt("id"), name = obj.getString("name"),
                isOccupied = obj.getBoolean("isOccupied"), isPaused = obj.getBoolean("isPaused"),
                mode = SessionMode.valueOf(obj.getString("mode")),
                startTimeMillis = obj.getLong("startTimeMillis"), pausedTimeMillis = obj.getLong("pausedTimeMillis"),
                totalPausedDuration = obj.getLong("totalPausedDuration"),
                prepaidDurationMillis = obj.getLong("prepaidDurationMillis"), prepaidPrice = obj.getDouble("prepaidPrice"),
                extras = extras, notificationSent = obj.optBoolean("notificationSent", false),
                transferBalance = obj.optDouble("transferBalance", 0.0)
            ))
        }
        list
    } catch (e: Exception) { emptyList() }
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
    
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(4000, easing = LinearEasing)),
        label = "rotation"
    )

    Canvas(modifier = modifier) {
        val strokeWidth = 4.dp.toPx()
        val radius = (size.minDimension / 2) - strokeWidth
        val center = Offset(size.width / 2, size.height / 2)
        drawCircle(color = color.copy(alpha = 0.1f), radius = radius, style = Stroke(width = strokeWidth))
        
        rotate(if (isTimeUp) rotation else 0f) {
            val path = Path()
            val actualProgress = if (isTimeUp) 1f else progress.coerceIn(0.01f, 1f)
            val sweepAngle = 360f * actualProgress
            val segments = (sweepAngle * 1.5f).toInt().coerceAtLeast(40)
            
            for (i in 0..segments) {
                val angleDeg = -90f + (sweepAngle * i / segments)
                val angleRad = Math.toRadians(angleDeg.toDouble()).toFloat()
                val waveAmplitude = if (isTimeUp) 6f else 3f
                val wave = sin(angleDeg * 0.15f + phase * 6f) * waveAmplitude
                
                val r = radius + wave
                val x = center.x + r * cos(angleRad)
                val y = center.y + r * sin(angleRad)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path = path, color = if (isTimeUp) Color.Red else color, style = Stroke(width = strokeWidth, cap = StrokeCap.Round))
        }
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
    val context = LocalContext.current
    var currentDestination by rememberSaveable { mutableStateOf(AppDestinations.CABINS) }
    var settings by remember { mutableStateOf(loadAppSettings(context)) }
    val cabins = remember { mutableStateListOf<Cabin>() }
    val sales = remember { mutableStateListOf<Sale>() }

    // CARGA INICIAL
    LaunchedEffect(Unit) {
        val loadedSales = loadSales(context)
        sales.clear()
        sales.addAll(loadedSales)
        val loaded = loadCabins(context)
        if (loaded.isNotEmpty()) {
            cabins.clear()
            cabins.addAll(loaded)
        }
    }

    // SINCRONIZACIÓN DE CABINAS
    LaunchedEffect(settings.cabinCount, settings.includeCabinZero) {
        val startId = if (settings.includeCabinZero) 0 else 1
        val activeMap = cabins.associateBy { it.id }
        val newCabins = mutableListOf<Cabin>()
        for (i in 0 until settings.cabinCount) {
            val id = startId + i
            newCabins.add(activeMap[id] ?: Cabin(id = id, name = "PC $id"))
        }
        cabins.clear()
        cabins.addAll(newCabins)
        saveCabins(context, cabins)
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
                    AppDestinations.CABINS -> CabinsScreen(cabins, settings, onSaleRecorded = { sale ->
                        sales.add(sale)
                        saveSales(context, sales)
                    })
                    AppDestinations.STATS -> StatsScreen(sales)
                    AppDestinations.SETTINGS -> SettingsScreen(settings, onSettingsChange = { 
                        settings = it
                        saveAppSettings(context, it) 
                    })
                    AppDestinations.INFO -> InfoScreen()
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
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Panel de Cabinas", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            val occupiedCount = cabins.count { it.isOccupied }
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = CircleShape) {
                Text("$occupiedCount/${cabins.size}", modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 80.dp)) {
            items(cabins, key = { it.id }) { cabin ->
                val group = remember(cabin.id, settings) { getPriceGroupForCabin(cabin.id, settings) }
                CabinCard(cabin, group, settings, cabins, onUpdate = { updated ->
                    val idx = cabins.indexOfFirst { it.id == cabin.id }
                    if (idx != -1) {
                        cabins[idx] = updated
                        saveCabins(context, cabins)
                    }
                }, onStop = { sale ->
                    onSaleRecorded(sale)
                    val idx = cabins.indexOfFirst { it.id == cabin.id }
                    if (idx != -1) {
                        cabins[idx] = Cabin(id = cabin.id, name = cabin.name)
                        saveCabins(context, cabins)
                    }
                })
            }
        }
    }
}

@Composable
fun CabinCard(cabin: Cabin, group: PriceGroup, settings: AppSettings, allCabins: List<Cabin>, onUpdate: (Cabin) -> Unit, onStop: (Sale) -> Unit) {
    val context = LocalContext.current
    var currentTimeMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var showStartDialog by remember { mutableStateOf(false) }
    var showAddTimeDialog by remember { mutableStateOf(false) }
    var showExtraDialog by remember { mutableStateOf(false) }
    var showSummaryDialog by remember { mutableStateOf(false) }
    var showTransferDialog by remember { mutableStateOf(false) }

    LaunchedEffect(cabin.isOccupied, cabin.isPaused) {
        while (cabin.isOccupied && !cabin.isPaused) { 
            delay(1000)
            currentTimeMillis = System.currentTimeMillis() 
        }
    }

    val elapsed = if (cabin.isOccupied) {
        if (cabin.isPaused) cabin.pausedTimeMillis - cabin.startTimeMillis - cabin.totalPausedDuration
        else currentTimeMillis - cabin.startTimeMillis - cabin.totalPausedDuration
    } else 0L

    val millisUntilClosing = remember(settings.closingTime, currentTimeMillis) {
        val now = LocalTime.now()
        val closing = try { LocalTime.parse(settings.closingTime, DateTimeFormatter.ofPattern("HH:mm")) } catch (e: Exception) { LocalTime.of(22, 0) }
        val diff = ChronoUnit.MILLIS.between(now, closing)
        if (diff > 0) diff else if (diff < 0) -1L else 0L
    }
    val isClosingSoon = millisUntilClosing in 1..3600000 
    val isAutoCountdown = cabin.isOccupied && cabin.mode == SessionMode.FREE && isClosingSoon

    val isTimeUp = (cabin.mode == SessionMode.PREPAID && elapsed >= cabin.prepaidDurationMillis) || (isAutoCountdown && millisUntilClosing <= 0)
    val timeRem = if (cabin.mode == SessionMode.PREPAID) (cabin.prepaidDurationMillis - elapsed).coerceAtLeast(0L) else if (isAutoCountdown) millisUntilClosing else 0L
    val overdueTime = if (isTimeUp) {
        if (cabin.mode == SessionMode.PREPAID) elapsed - cabin.prepaidDurationMillis 
        else if (isAutoCountdown) -millisUntilClosing
        else 0L
    } else 0L

    LaunchedEffect(isTimeUp) {
        if (isTimeUp && !cabin.notificationSent) {
            sendNotification(context, "Tiempo Agotado", "La ${cabin.name} ha terminado.")
            onUpdate(cabin.copy(notificationSent = true))
        }
    }

    val baseCost = if (cabin.mode == SessionMode.PREPAID) cabin.prepaidPrice
    else (elapsed / 3600000.0) * group.pricePerHour
    
    val totalAccumulated = baseCost + cabin.extras.sumOf { it.price } + cabin.transferBalance
    val finalCost = applyRounding(totalAccumulated, settings.roundingStep)

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = when {
                isTimeUp -> MaterialTheme.colorScheme.errorContainer
                cabin.isPaused -> MaterialTheme.colorScheme.secondaryContainer
                cabin.isOccupied -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Computer, null, modifier = Modifier.size(28.dp),
                        tint = when { cabin.isPaused -> Color(0xFFFFC107); cabin.isOccupied -> Color.Green; else -> Color.Gray }
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(cabin.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                }
                if (cabin.isOccupied) {
                    Text(
                        text = "S/ ${String.format(Locale.getDefault(), "%.2f", finalCost)}",
                        style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black,
                        color = if (isTimeUp) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (cabin.isOccupied && (cabin.mode == SessionMode.PREPAID || isAutoCountdown)) {
                    val totalForProgress = if (cabin.mode == SessionMode.PREPAID) cabin.prepaidDurationMillis.toFloat() else 3600000f
                    val progressValue = (timeRem.toFloat() / (if (totalForProgress > 0) totalForProgress else 1f)).coerceIn(0f, 1f)
                    CircularWavyProgressIndicator(progress = progressValue, isTimeUp = isTimeUp, modifier = Modifier.size(56.dp))
                    Spacer(Modifier.width(16.dp))
                }
                
                if (cabin.isOccupied) {
                    val timeText = when {
                        isTimeUp -> "+${formatTime(overdueTime)}"
                        cabin.mode == SessionMode.PREPAID || isAutoCountdown -> formatTime(timeRem)
                        else -> formatTime(elapsed)
                    }
                    Text(
                        text = timeText,
                        style = MaterialTheme.typography.displayMedium.copy(fontSize = 40.sp),
                        fontWeight = FontWeight.Black,
                        color = if (isTimeUp) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        softWrap = false
                    )
                } else {
                    Text("Disponible", style = MaterialTheme.typography.headlineMedium, color = Color.Gray.copy(alpha = 0.4f), fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    if (cabin.isOccupied) {
                        Text("Inició: ${formatClockTime(cabin.startTimeMillis)}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                        if (cabin.mode == SessionMode.PREPAID || isAutoCountdown) {
                            val duration = if (cabin.mode == SessionMode.PREPAID) cabin.prepaidDurationMillis else (millisUntilClosing + elapsed).coerceAtLeast(0L)
                            val endTime = cabin.startTimeMillis + duration + cabin.totalPausedDuration
                            Text("Termina: ${formatClockTime(endTime)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Text("${group.name} • S/ ${String.format(Locale.getDefault(), "%.2f", group.pricePerHour)}/h", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!cabin.isOccupied) {
                        Button(onClick = { showStartDialog = true }, shape = RoundedCornerShape(12.dp)) { Text("INICIAR", fontWeight = FontWeight.Bold) }
                    } else {
                        FilledTonalIconButton(onClick = { showExtraDialog = true }) { Icon(Icons.Default.ShoppingCart, null) }
                        if (cabin.mode == SessionMode.PREPAID) {
                            FilledTonalIconButton(onClick = { showAddTimeDialog = true }) { Icon(Icons.Default.AddAlarm, null) }
                        }
                        FilledTonalIconButton(onClick = { showTransferDialog = true }) { Icon(Icons.Default.SyncAlt, null) }
                        FilledTonalIconButton(onClick = {
                            if (cabin.isPaused) {
                                val pauseEnd = System.currentTimeMillis()
                                onUpdate(cabin.copy(isPaused = false, totalPausedDuration = cabin.totalPausedDuration + (pauseEnd - cabin.pausedTimeMillis)))
                            } else {
                                onUpdate(cabin.copy(isPaused = true, pausedTimeMillis = System.currentTimeMillis()))
                            }
                        }) { Icon(if (cabin.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause, null) }
                        IconButton(onClick = { showSummaryDialog = true }) { Icon(Icons.Default.Close, null, tint = Color.Red, modifier = Modifier.size(28.dp)) }
                    }
                }
            }
        }
    }

    if (showStartDialog) StartSessionDialog(group, { showStartDialog = false }, { m, d, p -> onUpdate(cabin.copy(isOccupied = true, mode = m, prepaidDurationMillis = d, prepaidPrice = p, startTimeMillis = System.currentTimeMillis())); showStartDialog = false })
    if (showAddTimeDialog) StartSessionDialog(group, { showAddTimeDialog = false }, { _, d, p -> onUpdate(cabin.copy(prepaidDurationMillis = cabin.prepaidDurationMillis + d, prepaidPrice = cabin.prepaidPrice + p, notificationSent = false)); showAddTimeDialog = false }, isAdding = true)
    if (showExtraDialog) AddExtraDialog(settings.products, { showExtraDialog = false }, { onUpdate(cabin.copy(extras = cabin.extras + it)); showExtraDialog = false })
    if (showSummaryDialog) SummaryDialog(
        cabin = cabin, 
        cost = finalCost, 
        allCabins = allCabins,
        onDismiss = { showSummaryDialog = false }, 
        onConfirm = { method -> onStop(Sale(cabinName = cabin.name, amount = finalCost, paymentMethod = method, startTime = cabin.startTimeMillis, endTime = System.currentTimeMillis())); showSummaryDialog = false },
        onAddToOther = { targetId ->
            val targetIdx = allCabins.indexOfFirst { it.id == targetId }
            if (targetIdx != -1) {
                val targetCabin = allCabins[targetIdx]
                val updatedTarget = targetCabin.copy(transferBalance = targetCabin.transferBalance + finalCost)
                (allCabins as? MutableList<Cabin>)?.let { list ->
                    list[targetIdx] = updatedTarget
                    saveCabins(context, list)
                }
                onUpdate(Cabin(id = cabin.id, name = cabin.name))
            }
            showSummaryDialog = false
        }
    )
    
    if (showTransferDialog) TransferDialog(
        currentCabin = cabin, 
        currentCost = finalCost,
        allCabins = allCabins, 
        settings = settings,
        onDismiss = { showTransferDialog = false }, 
        onConfirm = { targetId ->
            val targetIdx = allCabins.indexOfFirst { it.id == targetId }
            if (targetIdx != -1) {
                val transferredCabin = cabin.copy(
                    id = targetId, 
                    name = allCabins[targetIdx].name,
                    transferBalance = finalCost
                ).let { if (it.mode == SessionMode.FREE) it.copy(startTimeMillis = System.currentTimeMillis(), totalPausedDuration = 0) else it }
                
                onUpdate(Cabin(id = cabin.id, name = cabin.name))
                (allCabins as? MutableList<Cabin>)?.let { list ->
                    list[targetIdx] = transferredCabin
                    saveCabins(context, list)
                }
            }
            showTransferDialog = false
        }
    )
}

@Composable
fun TransferDialog(currentCabin: Cabin, currentCost: Double, allCabins: List<Cabin>, settings: AppSettings, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var selectedTargetId by remember { mutableStateOf(-1) }
    val freeCabins = allCabins.filter { !it.isOccupied }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cambio de PC") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Mover sesión de ${currentCabin.name} a:")
                if (freeCabins.isEmpty()) {
                    Text("No hay cabinas disponibles.", color = Color.Red)
                } else {
                    LazyVerticalGrid(columns = GridCells.Adaptive(64.dp), modifier = Modifier.heightIn(max = 200.dp)) {
                        items(freeCabins) { target ->
                            val targetGroup = getPriceGroupForCabin(target.id, settings)
                            val currentGroup = getPriceGroupForCabin(currentCabin.id, settings)
                            val isDifferentPrice = targetGroup.id != currentGroup.id
                            
                            Box(
                                modifier = Modifier
                                    .padding(4.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (selectedTargetId == target.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable { selectedTargetId = target.id }
                                    .padding(12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(target.name, fontWeight = FontWeight.Bold)
                                    if (isDifferentPrice) Icon(Icons.Default.Warning, null, modifier = Modifier.size(12.dp), tint = Color(0xFFFFA500))
                                }
                            }
                        }
                    }
                    if (selectedTargetId != -1) {
                        val targetGroup = getPriceGroupForCabin(selectedTargetId, settings)
                        val currentGroup = getPriceGroupForCabin(currentCabin.id, settings)
                        if (targetGroup.id != currentGroup.id) {
                            Text("⚠️ Aviso: Diferente rango de precios. Se transferirá el saldo de S/ ${String.format("%.2f", currentCost)} a la nueva PC.", style = MaterialTheme.typography.bodySmall, color = Color(0xFFFFA500))
                        } else {
                            Text("Se moverá el saldo de S/ ${String.format("%.2f", currentCost)}.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(selectedTargetId) }, enabled = selectedTargetId != -1) { Text("MOVER PC") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCELAR") } }
    )
}

@Composable
fun SummaryDialog(cabin: Cabin, cost: Double, allCabins: List<Cabin>, onDismiss: () -> Unit, onConfirm: (String) -> Unit, onAddToOther: (Int) -> Unit) {
    var selectedMethod by remember { mutableStateOf("Efectivo") }
    var showAddToOtherDialog by remember { mutableStateOf(false) }

    AlertDialog(onDismissRequest = onDismiss, title = { Text("Cerrar Cuenta") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Sesión: ${cabin.name}", fontWeight = FontWeight.Bold)
            Text("Inicio: ${formatClockTime(cabin.startTimeMillis)}")
            Text("Fin: ${formatClockTime(System.currentTimeMillis())}")
            HorizontalDivider()
            
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = { showAddToOtherDialog = true }) {
                    Icon(Icons.Default.AddCircleOutline, "Añadir a otra persona", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                }
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(text = String.format(Locale.getDefault(), "Total: S/ %.2f", cost), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    if (cabin.transferBalance > 0) Text("Saldo previo: S/ ${String.format("%.2f", cabin.transferBalance)}", style = MaterialTheme.typography.labelSmall)
                }
            }

            if (cabin.extras.isNotEmpty()) {
                Text("Consumos:", fontWeight = FontWeight.Bold)
                cabin.extras.forEach { Text("- ${it.name}: S/ ${String.format("%.2f", it.price)}") }
            }
            Text("Forma de pago:")
            Row(Modifier.selectableGroup()) {
                ModeOption("Efectivo", selectedMethod == "Efectivo") { selectedMethod = "Efectivo" }
                ModeOption("Yape", selectedMethod == "Yape") { selectedMethod = "Yape" }
            }
        }
    }, confirmButton = { Button(onClick = { onConfirm(selectedMethod) }) { Text("COBRAR") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("CANCELAR") } })

    if (showAddToOtherDialog) {
        val otherOccupied = allCabins.filter { it.isOccupied && it.id != cabin.id }
        AlertDialog(
            onDismissRequest = { showAddToOtherDialog = false },
            title = { Text("Sumar deuda a...") },
            text = {
                Column {
                    if (otherOccupied.isEmpty()) {
                        Text("No hay otras cabinas ocupadas actualmente.")
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                            items(otherOccupied) { target ->
                                ListItem(
                                    headlineContent = { Text(target.name, fontWeight = FontWeight.Bold) },
                                    supportingContent = { Text("Sesión activa") },
                                    leadingContent = { Icon(Icons.Default.AccountBalanceWallet, null) },
                                    modifier = Modifier.clickable { onAddToOther(target.id) }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showAddToOtherDialog = false }) { Text("CANCELAR") } }
        )
    }
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
                Card(modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }, shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(if (isToday) "HOY ($date)" else date, fontWeight = FontWeight.Bold)
                            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                        }
                        Text(text = String.format(Locale.getDefault(), "Total: S/ %.2f", totalEfectivo + totalYape), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        if (expanded) {
                            HorizontalDivider(Modifier.padding(vertical = 8.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(text = "Efectivo: S/ ${String.format("%.2f", totalEfectivo)}", style = MaterialTheme.typography.bodySmall)
                                Text(text = "Yape: S/ ${String.format("%.2f", totalYape)}", style = MaterialTheme.typography.bodySmall, color = Color(0xFF8E44AD))
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
                        ListItem(headlineContent = { Text(p.name) }, supportingContent = { Text("S/ ${String.format(Locale.getDefault(), "%.2f", p.price)}") }, trailingContent = { IconButton(onClick = { onAdd(p) }) { Icon(Icons.Default.Add, null) } })
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
            .clip(RoundedCornerShape(12.dp)),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(label, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), fontWeight = FontWeight.Bold)
    }
}

@Composable
fun SettingsScreen(settings: AppSettings, onSettingsChange: (AppSettings) -> Unit) {
    var showAddGroup by remember { mutableStateOf(false) }
    var showProductDialog by remember { mutableStateOf<ExtraItem?>(null) }
    var editingGroupPresets by remember { mutableStateOf<PriceGroup?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("Configuración", modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
        
        SettingsSection(title = "General", icon = Icons.Default.Tune) {
            SettingsTextField(value = settings.cabinCount.toString(), onValueChange = { it.toIntOrNull()?.let { n -> onSettingsChange(settings.copy(cabinCount = n)) } }, label = "Número de Cabinas", keyboardType = KeyboardType.Number)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text("Incluir Cabina 0", modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
                Switch(checked = settings.includeCabinZero, onCheckedChange = { onSettingsChange(settings.copy(includeCabinZero = it)) })
            }
            SettingsTextField(value = settings.closingTime, onValueChange = { onSettingsChange(settings.copy(closingTime = it)) }, label = "Hora Cierre (HH:mm)")
            
            Spacer(Modifier.height(8.dp))
            Text("Redondeo de cobro", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            val roundingOptions = listOf(0.01 to "Céntimos", 0.05 to "0.05", 0.10 to "0.10", 0.50 to "0.50")
            Row(Modifier.selectableGroup().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
                roundingOptions.forEach { (step, label) ->
                    val selected = settings.roundingStep == step
                    FilterChip(
                        selected = selected,
                        onClick = { onSettingsChange(settings.copy(roundingStep = step)) },
                        label = { Text(label) },
                        modifier = Modifier.padding(end = 8.dp),
                        shape = CircleShape
                    )
                }
            }
        }

        SettingsSection(title = "Zonas de Precios", icon = Icons.Default.Sell, action = { IconButton(onClick = { showAddGroup = true }) { Icon(Icons.Default.AddCircle, null, tint = MaterialTheme.colorScheme.primary) } }) {
            settings.priceGroups.forEach { group ->
                ListItem(
                    headlineContent = { Text(group.name, fontWeight = FontWeight.Bold) },
                    supportingContent = { Text("Rango: ${group.cabinRange} • S/ ${String.format("%.2f", group.pricePerHour)}/h") },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { editingGroupPresets = group }) { Icon(Icons.Default.Settings, null) }
                            if (group.cabinRange != "all") {
                                IconButton(onClick = { onSettingsChange(settings.copy(priceGroups = settings.priceGroups - group)) }) {
                                    Icon(Icons.Default.Delete, null, tint = Color.Red)
                                }
                            }
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }
        }

        SettingsSection(title = "Productos / Snacks", icon = Icons.Default.LocalCafe, action = { IconButton(onClick = { showProductDialog = ExtraItem(name = "", price = 0.0) }) { Icon(Icons.Default.AddCircle, null, tint = MaterialTheme.colorScheme.primary) } }) {
            settings.products.forEach { p ->
                ListItem(
                    modifier = Modifier.clickable { showProductDialog = p },
                    headlineContent = { Text(p.name, fontWeight = FontWeight.Bold) },
                    supportingContent = { Text("S/ ${String.format("%.2f", p.price)}") },
                    trailingContent = {
                        IconButton(onClick = { onSettingsChange(settings.copy(products = settings.products - p)) }) {
                            Icon(Icons.Default.Delete, null, tint = Color.Red)
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }
        }
        Spacer(Modifier.height(100.dp))
    }

    // DIALOGS
    if (showAddGroup) {
        var n by remember { mutableStateOf("") }; var r by remember { mutableStateOf("") }; var p by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { showAddGroup = false }, title = { Text("Nueva Zona") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = n, onValueChange = { n = it }, label = { Text("Nombre") }, shape = RoundedCornerShape(12.dp))
                OutlinedTextField(value = r, onValueChange = { r = it }, label = { Text("Rango (ej. 1-3)") }, shape = RoundedCornerShape(12.dp))
                OutlinedTextField(value = p, onValueChange = { p = it }, label = { Text("Precio/h") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), shape = RoundedCornerShape(12.dp))
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
                OutlinedTextField(value = tempPriceH, onValueChange = { tempPriceH = it }, label = { Text("Precio Hora Base") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), shape = RoundedCornerShape(12.dp))
                Text("Preajustes Prepago:", fontWeight = FontWeight.Bold)
                tempPresets.forEachIndexed { index, preset ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(preset.label, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = preset.price.toString(), onValueChange = { val p = it.toDoubleOrNull() ?: 0.0; tempPresets = tempPresets.toMutableList().apply { set(index, preset.copy(price = p)) } }, modifier = Modifier.width(90.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), shape = RoundedCornerShape(12.dp))
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
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nombre") }, shape = RoundedCornerShape(12.dp))
                OutlinedTextField(value = priceStr, onValueChange = { priceStr = it }, label = { Text("Precio") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), shape = RoundedCornerShape(12.dp))
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

@Composable
fun InfoScreen() {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            modifier = Modifier.size(120.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = CircleShape
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Coffee, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
        
        Spacer(Modifier.height(24.dp))
        Text("Cyber Control", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
        Text("Versión 1.0", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
        
        Spacer(Modifier.height(32.dp))
        
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("Desarrollo", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("Aplicación diseñada para la gestión eficiente de centros de computación y cybers.", textAlign = TextAlign.Justify)
                
                Spacer(Modifier.height(16.dp))
                Text("Características principales:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                Text("• Control de tiempo real con persistencia", style = MaterialTheme.typography.bodySmall)
                Text("• Gestión de múltiples zonas de precio", style = MaterialTheme.typography.bodySmall)
                Text("• Sistema de redondeo inteligente", style = MaterialTheme.typography.bodySmall)
                Text("• Registro de ventas y consumos", style = MaterialTheme.typography.bodySmall)
            }
        }
        
        Spacer(Modifier.height(32.dp))
        
        Button(
            onClick = { uriHandler.openUri("https://github.com/LeninAsto/Cyber-Control") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            contentPadding = PaddingValues(16.dp)
        ) {
            Icon(Icons.Default.Code, null)
            Spacer(Modifier.width(12.dp))
            Text("Ver código en GitHub", fontWeight = FontWeight.Bold)
        }
        
        Spacer(Modifier.height(16.dp))
        
        OutlinedButton(
            onClick = { uriHandler.openUri("https://buymeacoffee.com/lenin_anonimo_of") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            contentPadding = PaddingValues(16.dp)
        ) {
            Text("Buy me a Coffe!", fontWeight = FontWeight.Bold)
        }
        
        Spacer(Modifier.height(48.dp))
        Text("© 2026 Lenin Asto", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
    }
}

@Composable
fun SettingsSection(title: String, icon: ImageVector, action: @Composable (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp))
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                action?.invoke()
            }
            Spacer(modifier = Modifier.height(16.dp))
            content()
        }
    }
}

@Composable
fun SettingsTextField(value: String, onValueChange: (String) -> Unit, label: String, keyboardType: KeyboardType = KeyboardType.Text) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
        )
    )
}
