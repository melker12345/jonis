package com.jonis.thirty

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.animateFloatAsState
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

// ---------- Config: version check + gate ----------

// Bump this together with versionCode in app/build.gradle.kts AND "version" in version.json
// each time you ship a new APK. If the remote version is higher, the app forces an update.
private const val APP_VERSION = 8

// Raw URL of version.json in your GitHub repo. REPLACE <YOUR_USER>/<YOUR_REPO>.
private const val VERSION_URL =
    "https://raw.githubusercontent.com/melker12345/jonis/master/version.json"

// SHA-256 of the real-life-challenge code. Plaintext is "JONIS-5K" (case-insensitive).
// Change it by running:  printf '%s' "YOURCODE" | sha256sum
private const val GATE_CODE_HASH =
    "8c9c3ba8e5142f60b6178986c1446be13d52b275ce5b4b4b3123d6770be9d029"

// ---------- Journey data ----------

enum class GameType { BEER, WHAC, MEMORY, SEQUENCE, TAP, NINJA, STACK, JUMP, GATE }

data class Quest(val title: String, val tag: String, val type: GameType, val goal: Int)

// one node per minigame — no repeats — with the gate as the grand finale
private val quests = listOf(
    Quest("Häll upp åt Pappa", "Styr Pappa in i ölstrålen tills glaset är fullt", GameType.BEER, 2),
    Quest("Familjeminne", "Vänd korten och para ihop släkten", GameType.MEMORY, 2),
    Quest("Whac-en-Farmor", "Klappa till släkten när de dyker upp — nå 15", GameType.WHAC, 15),
    Quest("Familjesekvens", "Härma ordningen släkten lyser upp i", GameType.SEQUENCE, 6),
    Quest("Klappa Farmor", "120 klapp på 22 sekunder. Kör!", GameType.TAP, 120),
    Quest("Familje-Ninja", "Svep sönder släkten som flyger upp — 15 träffar", GameType.NINJA, 15),
    Quest("Släkttornet", "Släpp släkten i en hög — pricka mitten. Stapla 8", GameType.STACK, 8),
    Quest("Farmor Hoppar", "Studsa Farmor uppåt, väj för släkten. Klättra 25", GameType.JUMP, 25),
    Quest("Spring 5 km", "Visa Strava-bevis och få hemliga koden av festfixaren", GameType.GATE, 0),
)

// pool of family faces reused across games
private val faces = listOf(
    R.drawable.pappa to "Pappa",
    R.drawable.melker to "Melker",
    R.drawable.jonis to "Jonis",
    R.drawable.farmor to "Farmor",
    R.drawable.elvis to "Elvis",
    R.drawable.olivia to "Olivia",
    R.drawable.mamma to "Mamma",
    R.drawable.jonis1 to "Jonis",
    R.drawable.farmor1 to "Farmor",
)

class MainActivity : ComponentActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContent { JonisApp() }
    }
}

@Composable
fun JonisApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("jonis", Context.MODE_PRIVATE) }
    var selected by remember { mutableStateOf<Int?>(null) }
    // clamp saved progress to the current quest count — an older build may have
    // stored a higher value than the (now shorter) list, which would leave every
    // node "completed" and none tappable
    var unlocked by remember { mutableIntStateOf(prefs.getInt("unlocked", 1).coerceIn(1, quests.size)) }
    var updateUrl by remember { mutableStateOf<String?>(null) }

    // check GitHub for a newer version on launch; silently ignore if offline/unreachable
    LaunchedEffect(Unit) {
        val latest = fetchLatestVersion()
        if (latest != null && latest.first > APP_VERSION) updateUrl = latest.second
    }

    // theme follows the system setting automatically — no manual toggle
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        val idx = selected
        if (idx == null) {
            Hub(unlocked) { selected = it }
        } else {
            GameHost(
                quest = quests[idx],
                index = idx,
                onWinContinue = {
                    selected = null
                    unlocked = minOf(quests.size, maxOf(unlocked, idx + 2))
                    prefs.edit().putInt("unlocked", unlocked).apply()
                },
                onBack = { selected = null },
            )
        }
        updateUrl?.let { UpdateDialog(it) }
    }
}

// ---------- Update check + helpers ----------

private suspend fun fetchLatestVersion(): Pair<Int, String>? = withContext(Dispatchers.IO) {
    try {
        // unique query param + no-cache defeat GitHub's 5-min raw CDN cache
        // (cache-control: max-age=300), so a freshly released version.json is
        // seen immediately instead of up to 5 minutes late
        val url = URL("$VERSION_URL?t=${System.currentTimeMillis()}")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 4000
            readTimeout = 4000
            useCaches = false
            setRequestProperty("Cache-Control", "no-cache")
        }
        conn.inputStream.bufferedReader().use {
            val json = JSONObject(it.readText())
            json.getInt("version") to json.getString("url")
        }
    } catch (e: Exception) {
        null
    }
}

@Composable
private fun UpdateDialog(url: String) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = { },
        title = { Text("Ny version!", fontWeight = FontWeight.Black) },
        text = { Text("En nyare version av festen finns. Uppdatera för att fortsätta.") },
        confirmButton = {
            Button(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }) { Text("Ladda ner uppdatering", fontWeight = FontWeight.Bold) }
        },
    )
}

private fun sha256(s: String): String =
    MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

// ---------- Hub / road map ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Hub(unlocked: Int, open: (Int) -> Unit) {
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("JONIS 30", fontWeight = FontWeight.Black, letterSpacing = 2.sp) },
            actions = {
                // version badge — lets you confirm at a glance which build is installed
                Text(
                    "v$APP_VERSION",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 18.dp),
                )
            },
        )
    }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 20.dp).fillMaxSize()) {
            Text("JONIS\n30-ÅRS KAOS", fontSize = 39.sp, lineHeight = 40.sp, fontWeight = FontWeight.Black)
            Text(
                "${quests.size} uppdrag. Noll värdighet. En legend.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
            )
            LinearProgressIndicator(
                { (unlocked - 1) / quests.size.toFloat() },
                Modifier.fillMaxWidth().height(9.dp),
                strokeCap = StrokeCap.Round,
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 18.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("KAOS FRAMSTEG", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text("${unlocked - 1} / ${quests.size} klara", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            RoadMap(unlocked, open)
        }
    }
}

@Composable
private fun RoadMap(unlocked: Int, open: (Int) -> Unit) {
    val density = LocalDensity.current
    val wall = Color(0xFF2E5BFF)                 // pac-man maze wall (reads on light & dark)
    val pellet = Color(0xFFFFC107)
    val green = Color(0xFFB7E34B)
    val bg = MaterialTheme.colorScheme.background // corridor inner matches the parent bg
    val primary = MaterialTheme.colorScheme.primary
    val cols = 3
    val nodeSize = 84.dp
    val rowStep = 128.dp
    val topPad = 12.dp
    val rows = (quests.size + cols - 1) / cols

    // grid position for node i: snake left->right on even rows, right->left on odd rows
    fun colOf(i: Int): Int {
        val p = i % cols
        return if ((i / cols) % 2 == 0) p else cols - 1 - p
    }
    fun rowOf(i: Int) = i / cols

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),   // no background: matches parent screen
    ) {
        val fullW = maxWidth
        val colW = fullW / cols
        val fullWpx = with(density) { fullW.toPx() }
        val colWpx = fullWpx / cols
        val nodePx = with(density) { nodeSize.toPx() }
        val rowStepPx = with(density) { rowStep.toPx() }
        val topPadPx = with(density) { topPad.toPx() }
        val totalH = topPad + rowStep * rows

        fun centerX(i: Int) = (colOf(i) + 0.5f) * colWpx
        fun centerY(i: Int) = topPadPx + rowOf(i) * rowStepPx + nodePx / 2

        Box(Modifier.fillMaxWidth().height(totalH)) {
            // pac-man wall connectors (outlined corridor) + pellet trail, behind the nodes
            Canvas(Modifier.fillMaxSize()) {
                for (i in 0 until quests.size - 1) {
                    val start = Offset(centerX(i), centerY(i))
                    val end = Offset(centerX(i + 1), centerY(i + 1))
                    val done = i < unlocked - 1
                    drawLine(if (done) wall else wall.copy(alpha = 0.3f), start, end, strokeWidth = 16f, cap = StrokeCap.Round)
                    drawLine(bg, start, end, strokeWidth = 7f, cap = StrokeCap.Round)
                    val dots = 4
                    for (s in 1 until dots) {
                        val t = s / dots.toFloat()
                        val px = start.x + (end.x - start.x) * t
                        val py = start.y + (end.y - start.y) * t
                        drawCircle(pellet.copy(alpha = if (done) 0.25f else 0.85f), radius = 4f, center = Offset(px, py))
                    }
                }
            }
            quests.indices.forEach { i ->
                val active = i == unlocked - 1
                val completed = i < unlocked - 1
                val colLeftPx = centerX(i) - colWpx / 2
                val rowTopPx = topPadPx + rowOf(i) * rowStepPx
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .width(colW)
                        .offset { IntOffset(colLeftPx.toInt(), rowTopPx.toInt()) },
                ) {
                    Box(
                        Modifier.size(nodeSize)
                            .clip(RoundedCornerShape(18.dp))
                            .background(
                                when {
                                    completed -> green
                                    active -> primary
                                    else -> MaterialTheme.colorScheme.surfaceVariant
                                },
                            )
                            .border(if (active) 3.dp else 0.dp, wall, RoundedCornerShape(18.dp))
                            // active node plays; completed nodes can be replayed
                            .clickable(enabled = active || completed) { open(i) },
                        contentAlignment = Alignment.Center,
                    ) {
                        when {
                            // completed: green node keeps its number in dark ink — no checkmark
                            completed -> Text(
                                "%02d".format(i + 1),
                                fontSize = 26.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Black,
                                color = Color(0xFF14203A),
                            )
                            active -> Text(
                                "%02d".format(i + 1),
                                fontSize = 26.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Black,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            else -> Icon(Icons.Outlined.Lock, "Låst", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text(
                        quests[i].title,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = if (active) FontWeight.Black else FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        color = when {
                            active -> primary
                            completed -> MaterialTheme.colorScheme.onSurface
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        // opaque chip in the parent bg color so the connector line
                        // never shows through the label text
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .background(bg, RoundedCornerShape(6.dp))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
            }
        }
    }
}

// ---------- Game host + win overlay ----------

@Composable
private fun GameHost(quest: Quest, index: Int, onWinContinue: () -> Unit, onBack: () -> Unit) {
    var won by remember(index) { mutableStateOf(false) }
    // Surface (not a bare Box) so LocalContentColor resolves to onBackground —
    // otherwise default text/icons render black and vanish in dark theme.
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onBack) { Icon(Icons.Outlined.ArrowBack, "Tillbaka") }
                    Column(Modifier.padding(start = 4.dp)) {
                        Text("UPPDRAG %02d".format(index + 1), color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(quest.title, fontSize = 22.sp, fontWeight = FontWeight.Black)
                    }
                }
                Text(quest.tag, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(vertical = 10.dp), textAlign = TextAlign.Center)
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    when (quest.type) {
                        GameType.BEER -> BeerGame(quest.goal) { won = true }
                        GameType.WHAC -> WhacGame(quest.goal) { won = true }
                        GameType.MEMORY -> MemoryGame(quest.goal) { won = true }
                        GameType.SEQUENCE -> SequenceGame(quest.goal) { won = true }
                        GameType.TAP -> TapGame(quest.goal) { won = true }
                        GameType.NINJA -> NinjaGame(quest.goal) { won = true }
                        GameType.STACK -> StackGame(quest.goal) { won = true }
                        GameType.JUMP -> JumperGame(quest.goal) { won = true }
                        GameType.GATE -> GateScreen { won = true }
                    }
                }
            }
            if (won) WinOverlay(onContinue = onWinContinue)
        }
    }
}

@Composable
private fun WinOverlay(onContinue: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color(0xCC101010)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Text("🎉", fontSize = 72.sp)
            Text("KLARAT!", fontSize = 40.sp, fontWeight = FontWeight.Black, color = Color(0xFFD7FF45))
            Text("Nästa uppdrag upplåst.", color = Color.White, modifier = Modifier.padding(top = 8.dp, bottom = 28.dp))
            Button(onClick = onContinue) { Text("Tillbaka till kartan", fontWeight = FontWeight.Bold) }
        }
    }
}

// ---------- Real-life challenge gate ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GateScreen(onUnlock: () -> Unit) {
    var code by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(12.dp)) {
        Text("🏃", fontSize = 64.sp)
        Text(
            "Spring 5 km och visa Strava-beviset. Då får du den hemliga koden som låser upp resten av festen.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 16.dp),
        )
        OutlinedTextField(
            value = code,
            onValueChange = { code = it; wrong = false },
            label = { Text("Ange kod") },
            singleLine = true,
            isError = wrong,
        )
        if (wrong) Text("Fel kod. Fortsätt springa! 🏃", color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                if (sha256(code.trim().uppercase()) == GATE_CODE_HASH) onUnlock() else wrong = true
            },
            enabled = code.isNotBlank(),
        ) { Text("Lås upp", fontWeight = FontWeight.Bold) }
    }
}

// ---------- Game 1: Pour beer into Pappa ----------

@Composable
private fun BeerGame(difficulty: Int, onWin: () -> Unit) {
    val density = LocalDensity.current
    var dadX by remember { mutableFloatStateOf(0.5f) }
    var streamX by remember { mutableFloatStateOf(0.5f) }
    var fill by remember { mutableFloatStateOf(0f) }
    var elapsed by remember { mutableFloatStateOf(0f) }

    val driftSpeed = 0.9f + difficulty * 0.5f
    val fillRate = 0.32f
    val drainRate = 0.15f
    val catchWidth = 0.13f

    BoxWithConstraints(
        Modifier.fillMaxSize()
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF243B66), Color(0xFF14203A)))),
    ) {
        val widthPx = with(density) { maxWidth.toPx() }
        val pappaW = 152.dp
        val pappaWpx = with(density) { pappaW.toPx() }
        val pappaHpx = pappaWpx * (880f / 760f)
        val bottomPadPx = with(density) { 8.dp.toPx() }

        LaunchedEffect(difficulty) {
            var last = 0L
            fill = 0f
            elapsed = 0f
            while (true) {
                val now = withFrameNanos { it }
                if (last != 0L) {
                    val dt = ((now - last) / 1_000_000_000f).coerceAtMost(0.05f)
                    elapsed += dt
                    streamX = 0.5f + 0.42f * sin(elapsed * driftSpeed)
                    val aligned = abs(dadX - streamX) < catchWidth
                    fill = (fill + (if (aligned) fillRate else -drainRate) * dt).coerceIn(0f, 1f)
                    if (fill >= 1f) { onWin(); break }
                }
                last = now
            }
        }

        val aligned = abs(dadX - streamX) < catchWidth

        // beer glass at the top + beer-looking stream down to Pappa's mouth
        Canvas(Modifier.fillMaxSize()) {
            val sx = streamX * size.width
            val mugCx = sx
            val mugTop = 104f
            val mugW = 150f
            val mugH = 118f
            val mouthY = size.height - bottomPadPx - pappaHpx * 0.36f
            val streamTop = mugTop + mugH * 0.72f

            // ---- the beer stream ----
            val streamW = 46f
            drawRoundRect(
                color = Color(0xFFE8A317),
                topLeft = Offset(sx - streamW / 2, streamTop),
                size = Size(streamW, (mouthY - streamTop).coerceAtLeast(0f)),
                cornerRadius = CornerRadius(streamW / 2, streamW / 2),
            )
            // lighter highlight on the stream
            drawRoundRect(
                color = Color(0xFFFFC845),
                topLeft = Offset(sx - streamW / 2 + 7f, streamTop),
                size = Size(12f, (mouthY - streamTop).coerceAtLeast(0f)),
                cornerRadius = CornerRadius(7f, 7f),
            )
            // bubbles travelling down the stream
            val span = (mouthY - streamTop).coerceAtLeast(1f)
            for (b in 0 until 7) {
                val phase = (elapsed * 260f + b * 90f) % span
                drawCircle(Color(0x66FFFFFF), radius = 5f, center = Offset(sx + (if (b % 2 == 0) 8f else -8f), streamTop + phase))
            }
            // foam splash where it lands (green + pulsing larger when it's going in the mouth)
            val pulse = (sin(elapsed * 9f) + 1f) / 2f
            drawCircle(
                if (aligned) Color(0xFFB7E34B) else Color(0xFFFFF3C4),
                radius = if (aligned) 30f + pulse * 14f else 16f,
                center = Offset(sx, mouthY),
            )

            // ---- the pouring glass (tilted mug) ----
            rotate(degrees = 24f, pivot = Offset(mugCx, mugTop + mugH / 2)) {
                // glass body
                drawRoundRect(
                    color = Color(0x33FFFFFF),
                    topLeft = Offset(mugCx - mugW / 2, mugTop),
                    size = Size(mugW, mugH),
                    cornerRadius = CornerRadius(14f, 14f),
                )
                // beer inside (fills most of the mug)
                drawRoundRect(
                    color = Color(0xFFE8A317),
                    topLeft = Offset(mugCx - mugW / 2 + 6f, mugTop + mugH * 0.30f),
                    size = Size(mugW - 12f, mugH * 0.70f - 6f),
                    cornerRadius = CornerRadius(10f, 10f),
                )
                // foam head
                drawRoundRect(
                    color = Color(0xFFFFF6DA),
                    topLeft = Offset(mugCx - mugW / 2 + 6f, mugTop + 6f),
                    size = Size(mugW - 12f, mugH * 0.26f),
                    cornerRadius = CornerRadius(10f, 10f),
                )
                // glass outline
                drawRoundRect(
                    color = Color.White,
                    topLeft = Offset(mugCx - mugW / 2, mugTop),
                    size = Size(mugW, mugH),
                    cornerRadius = CornerRadius(14f, 14f),
                    style = Stroke(width = 5f),
                )
                // handle
                drawArc(
                    color = Color.White,
                    startAngle = -70f,
                    sweepAngle = 220f,
                    useCenter = false,
                    topLeft = Offset(mugCx + mugW / 2 - 14f, mugTop + 20f),
                    size = Size(46f, 62f),
                    style = Stroke(width = 6f),
                )
            }
        }

        // beer meter
        Column(Modifier.align(Alignment.TopCenter).padding(top = 12.dp).fillMaxWidth(0.7f)) {
            Text(
                "GLAS: ${(fill * 100).toInt()}%",
                color = Color.White,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            LinearProgressIndicator(
                { fill },
                Modifier.fillMaxWidth().height(14.dp).padding(top = 6.dp),
                color = Color(0xFFE8A317),
                trackColor = Color(0x33FFFFFF),
                strokeCap = StrokeCap.Round,
            )
        }

        // pulsing green glow ring behind Pappa's head while drinking
        val glowPulse = (sin(elapsed * 6f) + 1f) / 2f
        if (aligned) {
            val glowW = pappaW * (1.18f + glowPulse * 0.12f)
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .offset {
                        val maxX = (widthPx - pappaWpx).coerceAtLeast(0f)
                        val gExtra = with(density) { (glowW - pappaW).toPx() } / 2f
                        IntOffset((dadX * maxX).toInt().coerceIn(0, maxX.toInt()) - gExtra.toInt(), 0)
                    }
                    .padding(bottom = 4.dp)
                    .size(glowW)
                    .clip(CircleShape)
                    .background(Color(0xFFB7E34B).copy(alpha = 0.30f + glowPulse * 0.30f)),
            )
        }

        // Pappa cutout, draggable left/right (scales up while drinking)
        val pappaScale = if (aligned) 1.08f + glowPulse * 0.05f else 1f
        Image(
            painter = painterResource(R.drawable.pappa1),
            contentDescription = "Pappa",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset {
                    val maxX = (widthPx - pappaWpx).coerceAtLeast(0f)
                    IntOffset((dadX * maxX).toInt().coerceIn(0, maxX.toInt()), 0)
                }
                .padding(bottom = 8.dp)
                .width(pappaW)
                .graphicsLayer { scaleX = pappaScale; scaleY = pappaScale }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { _, dragAmount ->
                        dadX = (dadX + dragAmount / widthPx).coerceIn(0f, 1f)
                    }
                },
        )

        // "Pappa is drinking" label, only while aligned
        if (aligned) {
            Text(
                "🍺 GULP GULP!",
                color = Color(0xFFB7E34B),
                fontWeight = FontWeight.Black,
                fontSize = 22.sp,
                modifier = Modifier.align(Alignment.Center).padding(bottom = 40.dp),
            )
        }

        Text(
            "Dra Pappa i sidled →",
            color = Color(0xCCFFFFFF),
            fontSize = 12.sp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
        )
    }
}

// ---------- Game 2: Whac-a-Family ----------

@Composable
private fun WhacGame(target: Int, onWin: () -> Unit) {
    var score by remember { mutableIntStateOf(0) }
    var active by remember { mutableIntStateOf(-1) }
    var whoFace by remember { mutableIntStateOf(0) }
    var bonkCell by remember { mutableIntStateOf(-1) }

    LaunchedEffect(target) {
        score = 0
        while (score < target) {
            active = Random.nextInt(9)
            whoFace = Random.nextInt(faces.size)
            val upMs = (720L - score * 22L).coerceAtLeast(360L)
            delay(upMs)
            active = -1
            delay(180L)
        }
        onWin()
    }

    // clear the transient bonk graphic shortly after a hit
    LaunchedEffect(bonkCell) { if (bonkCell >= 0) { delay(320L); bonkCell = -1 } }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("TRÄFFAR: $score / $target", fontWeight = FontWeight.Black, fontSize = 20.sp)
        Spacer(Modifier.height(16.dp))
        for (row in 0 until 3) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                for (col in 0 until 3) {
                    val cell = row * 3 + col
                    val isUp = active == cell
                    Box(
                        Modifier.size(96.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable(enabled = isUp) {
                                if (active == cell) {
                                    score++
                                    active = -1
                                    bonkCell = cell
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isUp) {
                            Image(
                                painterResource(faces[whoFace].first),
                                faces[whoFace].second,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)),
                            )
                        } else {
                            Text("🕳", fontSize = 30.sp)
                        }
                        // transient bonk burst, overlaid so it never shifts layout
                        if (bonkCell == cell) {
                            var grown by remember { mutableStateOf(false) }
                            LaunchedEffect(Unit) { grown = true }
                            val pop by animateFloatAsState(if (grown) 1.3f else 0.4f, label = "bonk")
                            Text(
                                "💥",
                                fontSize = 46.sp,
                                modifier = Modifier.graphicsLayer {
                                    scaleX = pop
                                    scaleY = pop
                                    alpha = (1.6f - pop).coerceIn(0f, 1f)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------- Game 3: Family memory (concentration) ----------

@Composable
private fun MemoryGame(levelsToWin: Int, onWin: () -> Unit) {
    var level by remember { mutableIntStateOf(1) }
    val cols = 4
    // each pair is a distinct (face, number) so we can go beyond 4 pairs
    val pairs = 4 + level * 2                 // level 1 -> 6 pairs (12 cards), level 2 -> 8 pairs (16 cards)
    val deck = remember(level) { (0 until pairs).flatMap { listOf(it, it) }.shuffled() }
    val rows = deck.size / cols
    val matched = remember(level) { mutableStateListOf<Int>() }
    val revealed = remember(level) { mutableStateListOf<Int>() }
    var busy by remember(level) { mutableStateOf(false) }
    var preview by remember(level) { mutableStateOf(true) }

    // distinct border color per pairId so same-face pairs are still tellable apart
    val pairColors = listOf(
        Color(0xFFEF5350), Color(0xFF42A5F5), Color(0xFF66BB6A), Color(0xFFFFCA28),
        Color(0xFFAB47BC), Color(0xFFFF7043), Color(0xFF26C6DA), Color(0xFFEC407A),
        Color(0xFF9CCC65), Color(0xFF7E57C2),
    )

    LaunchedEffect(level) {
        preview = true
        delay((1600L - level * 400L).coerceAtLeast(600L))   // shorter peek each level
        preview = false
    }

    LaunchedEffect(revealed.size) {
        if (revealed.size == 2) {
            busy = true
            val (a, b) = revealed
            if (deck[a] == deck[b]) {
                matched.add(a); matched.add(b)
                revealed.clear()
            } else {
                delay(650)
                revealed.clear()
            }
            busy = false
        }
    }

    LaunchedEffect(matched.size) {
        if (matched.isNotEmpty() && matched.size == deck.size) {
            delay(450)
            if (level >= levelsToWin) onWin() else level++
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("NIVÅ $level / $levelsToWin", fontWeight = FontWeight.Black, fontSize = 20.sp)
        Text("Par: ${matched.size / 2} / ${deck.size / 2}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(14.dp))
        for (row in 0 until rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                for (col in 0 until cols) {
                    val pos = row * cols + col
                    if (pos >= deck.size) continue
                    val pairId = deck[pos]
                    val faceUp = preview || pos in revealed || pos in matched
                    val pairColor = pairColors[pairId % pairColors.size]
                    Box(
                        Modifier.size(72.dp, 88.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (faceUp) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.primary)
                            .border(
                                if (faceUp) 5.dp else 2.dp,
                                if (faceUp) pairColor else MaterialTheme.colorScheme.outline,
                                RoundedCornerShape(12.dp),
                            )
                            .clickable(enabled = !preview && !busy && pos !in revealed && pos !in matched) {
                                revealed.add(pos)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (faceUp) {
                            Image(
                                painterResource(faces[pairId % faces.size].first),
                                faces[pairId % faces.size].second,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
                            )
                        } else {
                            Text("?", fontSize = 28.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }
            }
        }
        if (preview) Text("Memorera!", modifier = Modifier.padding(top = 10.dp), fontWeight = FontWeight.Bold)
    }
}

// ---------- Game 4: Sequence memory (Human Benchmark style) ----------

@Composable
private fun SequenceGame(target: Int, onWin: () -> Unit) {
    val seq = remember { mutableStateListOf(Random.nextInt(9)) }
    var showing by remember { mutableStateOf(true) }
    var flashCell by remember { mutableIntStateOf(-1) }
    var inputPos by remember { mutableIntStateOf(0) }
    var round by remember { mutableIntStateOf(0) }   // bump to replay the sequence
    var wrong by remember { mutableStateOf(false) }
    var pressCell by remember { mutableIntStateOf(-1) }      // last tapped cell
    var pressOk by remember { mutableStateOf(true) }         // was that press correct?

    LaunchedEffect(round) {
        showing = true
        inputPos = 0
        delay(500)
        for (c in seq) {
            flashCell = c
            delay(420)
            flashCell = -1
            delay(200)
        }
        showing = false
    }

    fun tap(cell: Int) {
        if (showing) return
        pressCell = cell
        if (cell == seq[inputPos]) {
            pressOk = true
            inputPos++
            if (inputPos == seq.size) {
                if (seq.size >= target) { onWin() }
                else { seq.add(Random.nextInt(9)); round++ }
            }
        } else {
            pressOk = false
            wrong = true
            seq.clear(); seq.add(Random.nextInt(9)); round++
        }
    }

    LaunchedEffect(wrong) { if (wrong) { delay(700); wrong = false } }
    // clear the transient press highlight; the key includes both cell and round
    // so consecutive taps on the same tile still re-trigger the pop
    LaunchedEffect(pressCell, inputPos, round) { if (pressCell >= 0) { delay(240L); pressCell = -1 } }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("LÄNGD: ${seq.size} / $target", fontWeight = FontWeight.Black, fontSize = 20.sp)
        Text(
            if (wrong) "Fel! Börjar om…" else if (showing) "Titta noga…" else "Din tur — härma ordningen",
            fontSize = 12.sp,
            color = if (wrong) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        for (row in 0 until 3) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                for (col in 0 until 3) {
                    val cell = row * 3 + col
                    val lit = flashCell == cell
                    val rot by animateFloatAsState(if (lit) 180f else 0f, label = "flip")
                    val pressed = pressCell == cell
                    val pressScale by animateFloatAsState(if (pressed) 1.12f else 1f, label = "press")
                    val ringColor = if (pressOk) Color(0xFF32E0FF) else Color(0xFFFF4D4D)
                    Box(
                        Modifier.size(94.dp)
                            .graphicsLayer {
                                rotationY = rot; cameraDistance = 12f * density
                                scaleX = pressScale; scaleY = pressScale
                            }
                            .clip(RoundedCornerShape(16.dp))
                            .then(
                                if (pressed) Modifier.border(5.dp, ringColor, RoundedCornerShape(16.dp))
                                else Modifier,
                            )
                            .clickable(enabled = !showing) { tap(cell) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (rot < 90f) {
                            Box(
                                Modifier.fillMaxSize()
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("?", fontSize = 32.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onPrimary)
                            }
                        } else {
                            Box(
                                Modifier.fillMaxSize()
                                    .graphicsLayer { rotationY = 180f }
                                    .border(4.dp, Color(0xFFD7FF45), RoundedCornerShape(16.dp)),
                            ) {
                                Image(
                                    painterResource(faces[cell % faces.size].first),
                                    faces[cell % faces.size].second,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------- Game 5: Tap grandma 70x in 30s ----------

@Composable
private fun TapGame(target: Int, onWin: () -> Unit) {
    var taps by remember { mutableIntStateOf(0) }
    var timeLeft by remember { mutableFloatStateOf(22f) }
    var failed by remember { mutableStateOf(false) }
    var bump by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        var last = 0L
        while (timeLeft > 0f && taps < target) {
            val now = withFrameNanos { it }
            if (last != 0L) timeLeft -= (now - last) / 1_000_000_000f
            last = now
        }
        if (taps >= target) onWin() else failed = true
    }

    LaunchedEffect(bump) { if (bump) { delay(70); bump = false } }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("KLAPP: $taps / $target", fontWeight = FontWeight.Black, fontSize = 24.sp)
        Text("Tid: ${timeLeft.coerceAtLeast(0f).toInt()}s", fontWeight = FontWeight.Bold, color = if (timeLeft < 6f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        Image(
            painterResource(R.drawable.farmor),
            "Farmor",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(if (bump) 210.dp else 220.dp)
                .clip(CircleShape)
                .border(6.dp, Color(0xFFD7FF45), CircleShape)
                .clickable(enabled = !failed && timeLeft > 0f) {
                    taps++; bump = true
                    if (taps >= target) onWin()
                },
        )
        Spacer(Modifier.height(18.dp))
        if (failed) {
            Text("Tiden ute! ${taps}/$target", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            Button(onClick = { taps = 0; timeLeft = 22f; failed = false }, modifier = Modifier.padding(top = 10.dp)) {
                Text("Försök igen")
            }
        } else {
            Text("KLAPPA FARMOR!", fontWeight = FontWeight.Black, fontSize = 16.sp)
        }
    }
}

// ---------- Game 6: Family Ninja (fruit-ninja style) ----------

private class Flyer(
    var x: Float, var y: Float, var vx: Float, var vy: Float,
    val face: Int, var rot: Float = 0f, var vrot: Float = 0f,
    var sliced: Boolean = false, var alive: Boolean = true,
)

@Composable
private fun NinjaGame(target: Int, onWin: () -> Unit) {
    val density = LocalDensity.current
    var score by remember { mutableIntStateOf(0) }
    var tick by remember { mutableIntStateOf(0) }
    val flyers = remember { mutableStateListOf<Flyer>() }
    val sizePx = with(density) { 78.dp.toPx() }

    BoxWithConstraints(
        Modifier.fillMaxSize()
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF2A1D3A), Color(0xFF120B1C)))),
    ) {
        val w = with(density) { maxWidth.toPx() }
        val h = with(density) { maxHeight.toPx() }

        LaunchedEffect(Unit) {
            var last = 0L
            var sinceSpawn = 0f
            val gravity = h * 0.9f
            while (score < target) {
                val now = withFrameNanos { it }
                if (last != 0L) {
                    val dt = ((now - last) / 1_000_000_000f).coerceAtMost(0.05f)
                    sinceSpawn += dt
                    if (sinceSpawn > 0.85f) {
                        sinceSpawn = 0f
                        val startX = w * (0.15f + 0.7f * Random.nextFloat())
                        // aim the upward launch so the arc peaks well inside the screen,
                        // then gravity pulls it back down across the visible area
                        flyers.add(
                            Flyer(
                                x = startX, y = h + sizePx,
                                vx = (Random.nextFloat() - 0.5f) * w * 0.5f,
                                vy = -h * (1.05f + Random.nextFloat() * 0.25f),
                                face = Random.nextInt(faces.size),
                                rot = Random.nextFloat() * 360f,
                                vrot = (Random.nextFloat() - 0.5f) * 480f,
                            ),
                        )
                    }
                    // physics loop owns ALL structural changes to the list
                    for (f in flyers) {
                        if (!f.alive) continue
                        f.x += f.vx * dt
                        f.y += f.vy * dt
                        f.vy += gravity * dt
                        f.rot += f.vrot * dt
                        if (f.y > h + sizePx * 1.5f) f.alive = false
                    }
                    flyers.removeAll { !it.alive || it.sliced }
                    tick++
                }
                last = now
            }
            onWin()
        }

        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    val p = change.position
                    // iterate a snapshot and ONLY set flags; the physics loop removes
                    // sliced flyers, avoiding concurrent structural modification
                    for (f in flyers.toList()) {
                        if (f.alive && !f.sliced &&
                            hypot(p.x - f.x, p.y - f.y) < sizePx * 0.75f
                        ) {
                            f.sliced = true
                            score++
                        }
                    }
                }
            },
        ) {
            // read the frame tick in the SAME scope that emits the Images so the
            // offset/rotation lambdas below are re-evaluated every physics frame
            tick
            flyers.forEach { f ->
                if (f.alive && !f.sliced) {
                    Image(
                        painterResource(faces[f.face].first),
                        faces[f.face].second,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .offset { IntOffset((f.x - sizePx / 2).toInt(), (f.y - sizePx / 2).toInt()) }
                            .size(78.dp)
                            .graphicsLayer { rotationZ = f.rot }
                            .clip(CircleShape)
                            .border(3.dp, Color.White, CircleShape),
                    )
                }
            }
        }

        Text(
            "SVEP: $score / $target",
            color = Color.White,
            fontWeight = FontWeight.Black,
            fontSize = 20.sp,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
        )
        Text(
            "Dra fingret genom släkten",
            color = Color(0xCCFFFFFF),
            fontSize = 12.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
        )
    }
}

// ---------- Game 7: Stack Tower (family blocks) ----------

private class Slab(val left: Float, val width: Float, val face: Int)

@Composable
private fun StackGame(target: Int, onWin: () -> Unit) {
    val density = LocalDensity.current
    BoxWithConstraints(
        Modifier.fillMaxSize()
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF10233F), Color(0xFF071324)))),
    ) {
        val wpx = with(density) { maxWidth.toPx() }
        val hpx = with(density) { maxHeight.toPx() }
        val blockH = with(density) { 52.dp.toPx() }
        val bottomPad = with(density) { 12.dp.toPx() }

        val placed = remember { mutableStateListOf<Slab>() }
        var movingLeft by remember { mutableFloatStateOf(0f) }
        var movingWidth by remember { mutableFloatStateOf(0f) }
        var movingFace by remember { mutableIntStateOf(0) }
        var dir by remember { mutableIntStateOf(1) }
        var failed by remember { mutableStateOf(false) }
        var restart by remember { mutableIntStateOf(0) }
        var perfectFlash by remember { mutableStateOf(false) }

        // (re)start — declared first so it fully initialises before the slide loop runs
        LaunchedEffect(restart) {
            placed.clear()
            val baseW = wpx * 0.42f
            placed.add(Slab((wpx - baseW) / 2f, baseW, Random.nextInt(faces.size)))
            movingWidth = baseW
            movingFace = Random.nextInt(faces.size)
            movingLeft = 0f
            dir = 1
            failed = false
        }

        // slide the active block back and forth; speed grows as the tower rises
        LaunchedEffect(restart) {
            var last = 0L
            while (!failed) {
                val now = withFrameNanos { it }
                if (last != 0L && !failed) {
                    val dt = ((now - last) / 1_000_000_000f).coerceAtMost(0.05f)
                    val sp = wpx * (0.55f + (placed.size - 1) * 0.04f).coerceAtMost(1.7f)
                    movingLeft += dir * sp * dt
                    if (movingLeft <= 0f) { movingLeft = 0f; dir = 1 }
                    if (movingLeft + movingWidth >= wpx) { movingLeft = wpx - movingWidth; dir = -1 }
                }
                last = now
            }
        }

        LaunchedEffect(perfectFlash) { if (perfectFlash) { delay(220); perfectFlash = false } }

        fun drop() {
            if (failed) return
            val top = placed.last()
            val left = maxOf(movingLeft, top.left)
            val right = minOf(movingLeft + movingWidth, top.left + top.width)
            val overlap = right - left
            if (overlap <= wpx * 0.02f) { failed = true; return }   // missed the stack
            if (abs(movingLeft - top.left) < wpx * 0.03f) perfectFlash = true
            placed.add(Slab(left, overlap, movingFace))
            if (placed.size - 1 >= target) { onWin(); return }
            movingWidth = overlap
            movingFace = Random.nextInt(faces.size)
            movingLeft = if (dir > 0) 0f else wpx - overlap
        }

        // camera starts scrolling once the tower gets tall so the active block stays visible
        val rawShift = (placed.size + 1) * blockH + bottomPad - hpx * 0.72f
        val camShift by animateFloatAsState(rawShift.coerceAtLeast(0f), label = "cam")
        fun topYOf(i: Int) = hpx - bottomPad - (i + 1) * blockH + camShift

        Box(Modifier.fillMaxSize().clickable(enabled = !failed) { drop() }) {
            placed.forEachIndexed { i, s ->
                val topY = topYOf(i)
                Box(
                    Modifier
                        .offset { IntOffset(s.left.toInt(), topY.toInt()) }
                        .size(with(density) { s.width.toDp() }, with(density) { blockH.toDp() })
                        .clip(RoundedCornerShape(6.dp))
                        .border(2.dp, Color.White, RoundedCornerShape(6.dp)),
                ) {
                    Image(
                        painterResource(faces[s.face].first), faces[s.face].second,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)),
                    )
                }
            }
            if (!failed) {
                val topY = topYOf(placed.size)
                Box(
                    Modifier
                        .offset { IntOffset(movingLeft.toInt(), topY.toInt()) }
                        .size(with(density) { movingWidth.toDp() }, with(density) { blockH.toDp() })
                        .clip(RoundedCornerShape(6.dp))
                        .border(3.dp, Color(0xFFD7FF45), RoundedCornerShape(6.dp)),
                ) {
                    Image(
                        painterResource(faces[movingFace].first), faces[movingFace].second,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)),
                    )
                }
            }
        }

        Text(
            "TORN: ${(placed.size - 1).coerceAtLeast(0)} / $target",
            color = Color.White, fontWeight = FontWeight.Black, fontSize = 20.sp,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
        )
        if (perfectFlash) {
            Text(
                "PERFEKT! ✨", color = Color(0xFFD7FF45), fontWeight = FontWeight.Black, fontSize = 22.sp,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        if (!failed) {
            Text(
                "Tryck för att släppa", color = Color(0xCCFFFFFF), fontSize = 12.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
            )
        } else {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Rasade! ${(placed.size - 1).coerceAtLeast(0)}/$target", color = Color.White, fontWeight = FontWeight.Bold)
                Button(onClick = { restart++ }, modifier = Modifier.padding(top = 10.dp)) { Text("Försök igen") }
            }
        }
    }
}

// ---------- Game 8: Vertical Jumper (Farmor, doodle-jump style) ----------

private class Plat(val x: Float, val y: Float, val w: Float, val kind: Int, var boot: Boolean, var alive: Boolean = true)
private class Obst(var x: Float, val y: Float, var vx: Float, val face: Int)

// obstacles alternate between Melker, Elvis and Jonis (indices into `faces`)
private val obstacleFaces = listOf(1, 4, 2)

private fun spawnPlat(y: Float, wpx: Float, platW: Float, kind: Int): Plat =
    Plat(Random.nextFloat() * (wpx - platW), y, platW, kind, false)

@Composable
private fun JumperGame(target: Int, onWin: () -> Unit) {
    val density = LocalDensity.current
    BoxWithConstraints(
        Modifier.fillMaxSize()
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF12324A), Color(0xFF0A1626)))),
    ) {
        val wpx = with(density) { maxWidth.toPx() }
        val hpx = with(density) { maxHeight.toPx() }
        val playerSz = with(density) { 66.dp.toPx() }
        val platW = with(density) { 86.dp.toPx() }
        val platH = with(density) { 18.dp.toPx() }
        val spacing = with(density) { 112.dp.toPx() }
        val obstSz = with(density) { 60.dp.toPx() }

        var px by remember { mutableFloatStateOf(wpx / 2f) }
        var py by remember { mutableFloatStateOf(0f) }
        var vy by remember { mutableFloatStateOf(0f) }
        var camY by remember { mutableFloatStateOf(0f) }   // world-y mapped to the top of the screen
        var score by remember { mutableIntStateOf(0) }
        var failed by remember { mutableStateOf(false) }
        var restart by remember { mutableIntStateOf(0) }
        var tick by remember { mutableIntStateOf(0) }

        val plats = remember { mutableStateListOf<Plat>() }
        val obstacles = remember { mutableStateListOf<Obst>() }

        val jumpV = hpx * 1.15f
        val springV = hpx * 1.95f
        val gravity = hpx * 1.9f

        // world y increases downward: jumping is negative vy, falling is positive
        LaunchedEffect(restart) {
            plats.clear(); obstacles.clear()
            px = wpx / 2f; py = 0f; vy = -jumpV
            camY = py - hpx * 0.55f
            score = 0; failed = false
            plats.add(Plat(wpx / 2f - platW / 2f, playerSz, platW, 0, false))
            var topGen = playerSz
            repeat(16) { topGen -= spacing; plats.add(spawnPlat(topGen, wpx, platW, 0)) }

            var last = 0L
            var minPy = py
            while (!failed) {
                val now = withFrameNanos { it }
                if (last != 0L) {
                    val dt = ((now - last) / 1_000_000_000f).coerceAtMost(0.033f)
                    vy += gravity * dt
                    py += vy * dt
                    if (px < 0f) px += wpx
                    if (px > wpx) px -= wpx
                    if (py - camY < hpx * 0.45f) camY = py - hpx * 0.45f
                    if (py < minPy) {
                        minPy = py
                        score = (-minPy / spacing).toInt()
                        if (score >= target) { onWin(); break }
                    }
                    // land on a platform only while falling; interval test avoids tunnelling
                    if (vy > 0f) {
                        val feet = py + playerSz * 0.35f
                        val prevFeet = feet - vy * dt
                        for (p in plats) {
                            if (!p.alive) continue
                            if (px + playerSz * 0.25f > p.x && px - playerSz * 0.25f < p.x + p.w &&
                                prevFeet <= p.y && feet >= p.y
                            ) {
                                if (p.kind == 1) p.alive = false            // breaking: fall through
                                else {
                                    vy = -(if (p.boot || p.kind == 2) springV else jumpV)
                                    py = p.y - playerSz * 0.35f
                                }
                                break
                            }
                        }
                    }
                    for (o in obstacles) {
                        o.x += o.vx * dt
                        if (o.x < obstSz / 2) o.vx = abs(o.vx)
                        if (o.x > wpx - obstSz / 2) o.vx = -abs(o.vx)
                        if (hypot(px - o.x, py - o.y) < (playerSz + obstSz) * 0.32f) failed = true
                    }
                    // generate platforms above; sparser + obstacles as the climb gets higher
                    while (topGen > camY - spacing) {
                        topGen -= spacing * (0.85f + Random.nextFloat() * 0.5f + (score * 0.01f).coerceAtMost(0.6f))
                        val kind = when {
                            score > 6 && Random.nextFloat() < 0.18f -> 1
                            score > 3 && Random.nextFloat() < 0.14f -> 2
                            else -> 0
                        }
                        val p = spawnPlat(topGen, wpx, platW, kind)
                        if (kind == 0 && Random.nextFloat() < 0.12f) p.boot = true
                        plats.add(p)
                        if (score > 8 && Random.nextFloat() < 0.22f) {
                            obstacles.add(
                                Obst(
                                    x = wpx * (0.2f + 0.6f * Random.nextFloat()),
                                    y = topGen - spacing * 0.5f,
                                    vx = (if (Random.nextBoolean()) 1f else -1f) * wpx * (0.25f + Random.nextFloat() * 0.25f),
                                    face = obstacleFaces[Random.nextInt(obstacleFaces.size)],
                                ),
                            )
                        }
                    }
                    plats.removeAll { (it.y - camY > hpx + spacing) || (!it.alive && it.y - camY > 0f) }
                    obstacles.removeAll { it.y - camY > hpx + spacing }
                    if (py - camY > hpx + playerSz) failed = true
                    tick++
                }
                last = now
            }
        }

        Box(
            Modifier.fillMaxSize().pointerInput(restart) {
                detectHorizontalDragGestures { _, dx -> px += dx }
            },
        ) {
            tick
            plats.forEach { p ->
                if (p.alive) {
                    val sy = p.y - camY
                    Box(
                        Modifier
                            .offset { IntOffset(p.x.toInt(), sy.toInt()) }
                            .size(with(density) { p.w.toDp() }, with(density) { platH.toDp() })
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                when (p.kind) {
                                    1 -> Color(0xFF8D6E63)      // breaking (brown)
                                    2 -> Color(0xFF00E5FF)      // spring (cyan)
                                    else -> Color(0xFFB7E34B)   // normal (green)
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (p.boot) Text("🥾", fontSize = 15.sp)
                        else if (p.kind == 2) Text("⬆", fontWeight = FontWeight.Black, fontSize = 13.sp, color = Color(0xFF063038))
                    }
                }
            }
            obstacles.forEach { o ->
                val sy = o.y - camY
                Image(
                    painterResource(faces[o.face].first), faces[o.face].second,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .offset { IntOffset((o.x - obstSz / 2).toInt(), (sy - obstSz / 2).toInt()) }
                        .size(with(density) { obstSz.toDp() })
                        .clip(CircleShape)
                        .border(3.dp, Color(0xFFFF5252), CircleShape),
                )
            }
            if (!failed) {
                val sy = py - camY
                Image(
                    painterResource(R.drawable.farmor), "Farmor",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .offset { IntOffset((px - playerSz / 2).toInt(), (sy - playerSz / 2).toInt()) }
                        .size(with(density) { playerSz.toDp() })
                        .clip(CircleShape)
                        .border(4.dp, Color(0xFFD7FF45), CircleShape),
                )
            }
        }

        Text(
            "HÖJD: $score / $target",
            color = Color.White, fontWeight = FontWeight.Black, fontSize = 20.sp,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
        )
        if (!failed) {
            Text(
                "Dra i sidled för att styra", color = Color(0xCCFFFFFF), fontSize = 12.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
            )
        } else {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Farmor ramlade! $score/$target", color = Color.White, fontWeight = FontWeight.Bold)
                Button(onClick = { restart++ }, modifier = Modifier.padding(top = 10.dp)) { Text("Försök igen") }
            }
        }
    }
}
