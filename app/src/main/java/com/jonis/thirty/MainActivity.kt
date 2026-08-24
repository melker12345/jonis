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
private const val APP_VERSION = 22

// Raw URL of version.json in your GitHub repo. REPLACE <YOUR_USER>/<YOUR_REPO>.
private const val VERSION_URL =
    "https://raw.githubusercontent.com/melker12345/jonis/master/version.json"

// SHA-256 of the present-gate code. Plaintext is "GODIS-250" (case-insensitive).
// Change it by running:  printf '%s' "YOURCODE" | sha256sum
private const val GATE_CODE_HASH =
    "33a86e7311d126f1fde5fa0b7ba0d9929043efd9b64e825fd5df6c073c5485b2"

// ---------- Journey data ----------

enum class GameType { BEER, WHAC, MEMORY, SEQUENCE, TAP, NINJA, STACK, JUMP, MAZE, GATE, LOCKED }

data class Quest(val title: String, val tag: String, val type: GameType, val goal: Int)

// Nodes 1-9 are minigames, node 10 is the present/candy-budget gate. Nodes 11-30 are
// grayed placeholders ("more to come") until the post-gate path is designed.
private val quests = listOf(
    Quest("Pappa är törstig!", "Styr Pappa in i ölstrålen tills han är full", GameType.BEER, 1),
    Quest("Familjememory", "Vänd korten och para ihop släkten", GameType.MEMORY, 2),
    Quest("Whac-en-Farmor", "Klappa släkten när de dyker upp — slå 32", GameType.WHAC, 32),
    Quest("Familjesekvens", "Härma ordningen — nå längd 10", GameType.SEQUENCE, 10),
    Quest("Klappa Farmor", "Klappa Farmor — slå 170 på tiden!", GameType.TAP, 170),
    Quest("Familje-Ninja", "Svep släkten, undvik bomben — slå 100", GameType.NINJA, 100),
    Quest("Släkttornet", "Släpp släkten i en hög — stapla 10", GameType.STACK, 10),
    Quest("Farmor Hoppar", "Studsa Farmor uppåt, väj för släkten — nå 80", GameType.JUMP, 80),
    Quest("Farmor i mörkret", "Farmor har gått vilse i en mörk labyrint! Hjälp henne hitta ut.", GameType.MAZE, 0),
    Quest("Presenten 🎁", "En budget för godis väntar — skicka bildbevis", GameType.GATE, 0),
) + List(20) { Quest("???", "Kommer snart", GameType.LOCKED, 0) }

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
    // chosen play-style ("MINI" or "IRL"); null until picked on first launch. Drives the
    // post-node-10 path (nodes 11+) once that content is designed.
    var mode by remember { mutableStateOf(prefs.getString("mode", null)) }

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
        // ask how they want to play, once, before anything else
        if (mode == null) {
            ModeDialog { picked ->
                mode = picked
                prefs.edit().putString("mode", picked).apply()
            }
        }
        updateUrl?.let { UpdateDialog(it) }
    }
}

@Composable
private fun ModeDialog(onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = { },
        title = { Text("Nu jävlar ska det firas! Frågan är bara hur? 🎉", fontWeight = FontWeight.Black) },
        text = {
            Text(
                "Välj din väg genom kalaset. Om du vill ta den enkla vägen för att låsa upp " +
                    "alla presenter eller om du vill ha lite utmanigar som typ \"bevisa att du " +
                    "har varit på 3 uteserveringar\".",
            )
        },
        confirmButton = {
            Button(onClick = { onPick("MINI") }) { Text("Enkla minigames", fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            OutlinedButton(onClick = { onPick("IRL") }) { Text("IRL-utmaningar") }
        },
    )
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
            Text("Jonis på äventyr", fontSize = 39.sp, lineHeight = 42.sp, fontWeight = FontWeight.Black)
            Text(
                "hahaha trodde du att du bara skulle få presenter? Haha tänk igen här kommer " +
                    "lite utmanigar du måste låsa upp innan du får presenterna.",
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
                Text("progress", fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
                val placeholder = quests[i].type == GameType.LOCKED
                val isGift = quests[i].type == GameType.GATE
                // placeholders are never active/completed — they stay grayed "more to come"
                val active = !placeholder && i == unlocked - 1
                val completed = !placeholder && i < unlocked - 1
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
                            .alpha(if (placeholder) 0.38f else 1f)
                            .background(
                                when {
                                    placeholder -> MaterialTheme.colorScheme.surfaceVariant
                                    completed -> green
                                    active -> primary
                                    else -> MaterialTheme.colorScheme.surfaceVariant
                                },
                            )
                            .border(if (active) 3.dp else 0.dp, wall, RoundedCornerShape(18.dp))
                            // active node plays; completed nodes can be replayed; placeholders don't open
                            .clickable(enabled = active || completed) { open(i) },
                        contentAlignment = Alignment.Center,
                    ) {
                        when {
                            placeholder -> Text("?", fontSize = 30.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            // the present/gift node reads as a 🎁 rather than a number
                            isGift -> Text("🎁", fontSize = 34.sp)
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
                            .alpha(if (placeholder) 0.5f else 1f)
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
                        GameType.MAZE -> MazeGame { won = true }
                        GameType.GATE -> GateScreen { won = true }
                        GameType.LOCKED -> Text("Kommer snart…", color = MaterialTheme.colorScheme.onSurfaceVariant)
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

// End-of-round panel for the "play to your max" games: shows the achieved score
// against the target. The round only counts as cleared when the target is met —
// below it there is no way onward, just another try, so no node can be skipped.
@Composable
private fun ScorePanel(score: Int, target: Int, onRetry: () -> Unit, onContinue: () -> Unit) {
    val cleared = score >= target
    Column(
        Modifier
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f), RoundedCornerShape(18.dp))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("DIN POÄNG", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "$score",
            fontSize = 60.sp,
            fontWeight = FontWeight.Black,
            color = if (cleared) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        Text(
            "MÅL: $target",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!cleared) {
            Text(
                "Du behöver $target för att klara utmaningen — ${target - score} kvar!",
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (cleared) {
                OutlinedButton(onClick = onRetry) { Text("Försök igen") }
                Button(onClick = onContinue) { Text("Fortsätt →", fontWeight = FontWeight.Bold) }
            } else {
                Button(onClick = onRetry) { Text("Försök igen", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

// ---------- Real-life challenge gate ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GateScreen(onUnlock: () -> Unit) {
    var code by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(12.dp).verticalScroll(scroll),
    ) {
        Text("🎁", fontSize = 64.sp)
        Text(
            "Oops! Jag underskattade hur gammal du fyller… så för att köpa mig själv lite mer " +
                "utvecklingstid: här kommer en budget på minst 250 kr som INTE ska gå till något " +
                "annat än godis/snacks. 🍬\n\nSkicka bildbevis via SMS till världens bästa lilebror, " +
                "så får du koden som låser upp resten av festen.",
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
        if (wrong) Text("Fel kod. Skicka godisbeviset först, din lilla fuskare, försök inte kolla source koden alla koder är enkrypterade! 🍬", color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
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

    // gentle & forgiving: slow steady swing, wide catch zone, fills fast, drains slow
    val driftSpeed = 0.9f + difficulty * 0.35f
    val fillRate = 0.36f
    val drainRate = 0.13f
    val catchWidth = 0.16f

    val ink = MaterialTheme.colorScheme.onSurface   // theme-aware outline/text now there's no dark backdrop
    BoxWithConstraints(
        Modifier.fillMaxSize()
            // direct control: Pappa snaps to wherever you touch/drag horizontally
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    dadX = (change.position.x / size.width).coerceIn(0f, 1f)
                }
            },
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
                    streamX = (0.5f + 0.40f * sin(elapsed * driftSpeed)).coerceIn(0.08f, 0.92f)
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
                    color = ink,
                    topLeft = Offset(mugCx - mugW / 2, mugTop),
                    size = Size(mugW, mugH),
                    cornerRadius = CornerRadius(14f, 14f),
                    style = Stroke(width = 5f),
                )
                // handle
                drawArc(
                    color = ink,
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
                color = ink,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            LinearProgressIndicator(
                { fill },
                Modifier.fillMaxWidth().height(14.dp).padding(top = 6.dp),
                color = Color(0xFFE8A317),
                trackColor = ink.copy(alpha = 0.2f),
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

        // Pappa cutout — position follows your touch (handled on the parent box)
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
                .graphicsLayer { scaleX = pappaScale; scaleY = pappaScale },
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
            "Tryck/dra för att flytta Pappa",
            color = ink.copy(alpha = 0.8f),
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
    var timeLeft by remember { mutableFloatStateOf(30f) }
    var ended by remember { mutableStateOf(false) }
    var restart by remember { mutableIntStateOf(0) }

    // fixed-length round — play to your max; the score is however many you hit
    LaunchedEffect(restart) {
        score = 0; timeLeft = 30f; ended = false
        var last = 0L
        while (timeLeft > 0f) {
            val now = withFrameNanos { it }
            if (last != 0L) timeLeft -= (now - last) / 1_000_000_000f
            last = now
        }
        active = -1
        ended = true
    }

    // pop the family up one cell at a time until the timer ends; gets quicker as you score
    LaunchedEffect(restart) {
        while (!ended) {
            active = Random.nextInt(9)
            whoFace = Random.nextInt(faces.size)
            val upMs = (720L - score * 12L).coerceAtLeast(320L)
            delay(upMs)
            active = -1
            delay(150L)
        }
    }

    // clear the transient bonk graphic shortly after a hit
    LaunchedEffect(bonkCell) { if (bonkCell >= 0) { delay(320L); bonkCell = -1 } }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("TRÄFFAR: $score", fontWeight = FontWeight.Black, fontSize = 22.sp)
            Text("SLÅ $target", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
            Text(
                "Tid: ${timeLeft.coerceAtLeast(0f).toInt()}s",
                fontWeight = FontWeight.Bold,
                color = if (timeLeft < 6f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            for (row in 0 until 3) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                    for (col in 0 until 3) {
                        val cell = row * 3 + col
                        val isUp = active == cell
                        Box(
                            Modifier.size(96.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable(enabled = isUp && !ended) {
                                    if (active == cell) {
                                        score++
                                        active = -1
                                        bonkCell = cell
                                        if (score >= target) onWin()
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
        if (ended) ScorePanel(score, target, onRetry = { restart++ }, onContinue = onWin)
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
    var ended by remember { mutableStateOf(false) }
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
        if (showing || ended) return
        pressCell = cell
        if (cell == seq[inputPos]) {
            pressOk = true
            inputPos++
            // reach the target length to win; otherwise the sequence keeps growing
            if (inputPos == seq.size) {
                if (seq.size >= target) onWin()
                else { seq.add(Random.nextInt(9)); round++ }
            }
        } else {
            pressOk = false
            ended = true
        }
    }

    fun retry() {
        seq.clear(); seq.add(Random.nextInt(9))
        inputPos = 0; ended = false; pressOk = true; pressCell = -1; round++
    }

    // clear the transient press highlight; the key includes both cell and round
    // so consecutive taps on the same tile still re-trigger the pop
    LaunchedEffect(pressCell, inputPos, round) { if (pressCell >= 0) { delay(240L); pressCell = -1 } }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("LÄNGD: ${seq.size} · SLÅ $target", fontWeight = FontWeight.Black, fontSize = 20.sp)
            Text(
                if (showing) "Titta noga…" else "Din tur — härma ordningen",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                            .clickable(enabled = !showing && !ended) { tap(cell) },
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
        if (ended) ScorePanel(seq.size - 1, target, onRetry = { retry() }, onContinue = onWin)
    }
}

// ---------- Game 5: Tap grandma 70x in 30s ----------

@Composable
private fun TapGame(target: Int, onWin: () -> Unit) {
    var taps by remember { mutableIntStateOf(0) }
    var timeLeft by remember { mutableFloatStateOf(22f) }
    var ended by remember { mutableStateOf(false) }
    var restart by remember { mutableIntStateOf(0) }
    var bump by remember { mutableStateOf(false) }

    // tap as much as you can until the timer runs out — score is your tap count
    LaunchedEffect(restart) {
        taps = 0; timeLeft = 22f; ended = false
        var last = 0L
        while (timeLeft > 0f) {
            val now = withFrameNanos { it }
            if (last != 0L) timeLeft -= (now - last) / 1_000_000_000f
            last = now
        }
        ended = true
    }

    LaunchedEffect(bump) { if (bump) { delay(70); bump = false } }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("KLAPP: $taps", fontWeight = FontWeight.Black, fontSize = 26.sp)
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
                    .clickable(enabled = !ended && timeLeft > 0f) {
                        taps++; bump = true
                        if (taps >= target) onWin()
                    },
            )
            Spacer(Modifier.height(18.dp))
            Text("KLAPPA FARMOR! · KLAPPA $target", fontWeight = FontWeight.Black, fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
        }
        if (ended) ScorePanel(taps, target, onRetry = { restart++ }, onContinue = onWin)
    }
}

// ---------- Game 6: Family Ninja (fruit-ninja style) ----------

private class Flyer(
    var x: Float, var y: Float, var vx: Float, var vy: Float,
    val face: Int, val bomb: Boolean,
    var rot: Float = 0f, var vrot: Float = 0f,
    var sliced: Boolean = false, var alive: Boolean = true,
)

@Composable
private fun NinjaGame(target: Int, onWin: () -> Unit) {
    val density = LocalDensity.current
    var score by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf(false) }
    var restart by remember { mutableIntStateOf(0) }
    var frame by remember { mutableIntStateOf(0) }   // bumped every physics tick to drive re-layout
    val flyers = remember { mutableStateListOf<Flyer>() }
    val sizePx = with(density) { 88.dp.toPx() }

    // no background of its own — the round arcs float over the screen's own background
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = with(density) { maxWidth.toPx() }
        val h = with(density) { maxHeight.toPx() }

        LaunchedEffect(restart) {
            flyers.clear()
            score = 0
            failed = false
            var last = 0L
            var sinceSpawn = 1f
            val gravity = h * 2.0f
            while (!failed) {   // endless — play until you slice a bomb
                val now = withFrameNanos { it }
                if (last != 0L) {
                    val dt = ((now - last) / 1_000_000_000f).coerceAtMost(0.033f)
                    sinceSpawn += dt
                    val interval = (1.0f - score * 0.02f).coerceAtLeast(0.55f)
                    if (sinceSpawn >= interval) {
                        sinceSpawn = 0f
                        val startX = w * (0.2f + 0.6f * Random.nextFloat())
                        // strong upward launch so the arc peaks near the top of the screen;
                        // gravity (2*h) then pulls it back down. vx sweeps it across the area.
                        flyers.add(
                            Flyer(
                                x = startX, y = h + sizePx * 0.5f,
                                vx = (w * 0.5f - startX) * 0.9f + (Random.nextFloat() - 0.5f) * w * 0.2f,
                                vy = -h * (1.9f + Random.nextFloat() * 0.2f),
                                face = Random.nextInt(faces.size),
                                bomb = score >= 4 && Random.nextFloat() < 0.2f,
                                rot = Random.nextFloat() * 360f,
                                vrot = (Random.nextFloat() - 0.5f) * 300f,
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
                        if (f.y > h + sizePx * 2f) f.alive = false
                    }
                    flyers.removeAll { !it.alive || it.sliced }
                    frame++
                }
                last = now
            }
        }

        Box(
            Modifier.fillMaxSize().pointerInput(restart) {
                detectDragGestures { change, _ ->
                    val p = change.position
                    // snapshot + flag-only; the physics loop does the removal
                    for (f in flyers.toList()) {
                        if (f.alive && !f.sliced &&
                            hypot(p.x - f.x, p.y - f.y) < sizePx * 0.72f
                        ) {
                            f.sliced = true
                            if (f.bomb) failed = true else { score++; if (score >= target) onWin() }
                        }
                    }
                }
            },
        ) {
            flyers.forEach { f ->
                if (f.alive && !f.sliced) {
                    // reading `frame` inside the placement lambda re-runs it every physics
                    // tick, so the round follows f.x/f.y (plain vars) smoothly up the screen
                    val place: androidx.compose.ui.unit.Density.() -> IntOffset = {
                        frame
                        IntOffset((f.x - sizePx / 2).toInt(), (f.y - sizePx / 2).toInt())
                    }
                    if (f.bomb) {
                        Box(
                            Modifier
                                .offset(place)
                                .size(with(density) { sizePx.toDp() })
                                .graphicsLayer { rotationZ = f.rot }
                                .clip(CircleShape)
                                .background(Color(0xFF181818))
                                .border(4.dp, Color(0xFFFF5252), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("💣", fontSize = 36.sp)
                        }
                    } else {
                        Image(
                            painterResource(faces[f.face].first),
                            faces[f.face].second,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .offset(place)
                                .size(with(density) { sizePx.toDp() })
                                .graphicsLayer { rotationZ = f.rot }
                                .clip(CircleShape)
                                .border(3.dp, MaterialTheme.colorScheme.primary, CircleShape),
                        )
                    }
                }
            }
        }

        Column(Modifier.align(Alignment.TopCenter).padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("SVEP: $score", fontWeight = FontWeight.Black, fontSize = 20.sp)
            Text("SLÅ $target", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
        }
        if (!failed) {
            Text(
                "Svep släkten — undvik bomben 💣",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
            )
        } else {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("BOM! 💥", fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 10.dp))
                ScorePanel(score, target, onRetry = { restart++ }, onContinue = onWin)
            }
        }
    }
}

// ---------- Game 7: Stack Tower (family blocks) ----------

private class Slab(val left: Float, val width: Float, val face: Int)

@Composable
private fun StackGame(target: Int, onWin: () -> Unit) {
    val density = LocalDensity.current
    BoxWithConstraints(
        Modifier.fillMaxSize(),   // no background — blocks stack over the screen itself
    ) {
        val wpx = with(density) { maxWidth.toPx() }
        val hpx = with(density) { maxHeight.toPx() }
        // square blocks: the base is a square, so block height == the starting block width
        val baseSide = wpx * 0.36f
        val blockH = baseSide
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
            placed.add(Slab((wpx - baseSide) / 2f, baseSide, Random.nextInt(faces.size)))
            movingWidth = baseSide
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
                    val sp = wpx * (0.95f + (placed.size - 1) * 0.09f).coerceAtMost(3.0f)
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
            if (placed.size - 1 >= target) { onWin(); return }   // stacked enough — you win
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
                        .border(2.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(6.dp)),
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
            "TORN: ${(placed.size - 1).coerceAtLeast(0)} · SLÅ $target",
            color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Black, fontSize = 20.sp,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
        )
        if (perfectFlash) {
            Text(
                "PERFEKT! ✨", color = Color(0xFFD7FF45), fontWeight = FontWeight.Black, fontSize = 22.sp,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        if (!failed) {
            Text(
                "Tryck för att släppa", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
            )
        } else {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Rasade!", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 10.dp))
                ScorePanel((placed.size - 1).coerceAtLeast(0), target, onRetry = { restart++ }, onContinue = onWin)
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
    BoxWithConstraints(Modifier.fillMaxSize()) {   // no background — plays over the screen itself
        val wpx = with(density) { maxWidth.toPx() }
        val hpx = with(density) { maxHeight.toPx() }
        val playerSz = with(density) { 66.dp.toPx() }
        val platW = with(density) { 86.dp.toPx() }
        val platH = with(density) { 18.dp.toPx() }
        val spacing = with(density) { 108.dp.toPx() }
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
        // a normal jump only rises this far (apex = v²/2g); every gap between consecutive
        // platforms is capped below it so the next platform is ALWAYS reachable — no dead ends
        val maxReach = jumpV * jumpV / (2f * gravity)
        val maxGap = maxReach * 0.82f

        // world y increases downward: jumping is negative vy, falling is positive
        LaunchedEffect(restart) {
            plats.clear(); obstacles.clear()
            px = wpx / 2f; py = 0f; vy = -jumpV
            camY = py - hpx * 0.55f
            score = 0; failed = false
            plats.add(Plat(wpx / 2f - platW / 2f, playerSz, platW, 0, false))
            var topGen = playerSz
            val startGap = minOf(spacing, maxGap)
            repeat(16) { topGen -= startGap; plats.add(spawnPlat(topGen, wpx, platW, 0)) }

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
                        if (score >= target) { onWin(); break }   // beat the target height
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
                                // every platform gives a bounce; breaking ones then vanish
                                // so they can only be used once (never a dead end)
                                vy = -(if (p.boot || p.kind == 2) springV else jumpV)
                                py = p.y - playerSz * 0.35f
                                if (p.kind == 1) p.alive = false
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
                    // generate platforms above; sparser + more obstacles as the climb gets higher
                    while (topGen > camY - spacing) {
                        // grow the gap with score for difficulty, but never beyond a single jump
                        topGen -= (spacing * (0.95f + Random.nextFloat() * 0.65f + (score * 0.02f).coerceAtMost(1.0f))).coerceAtMost(maxGap)
                        val kind = when {
                            score > 4 && Random.nextFloat() < 0.24f -> 1     // breaking (earlier + more)
                            score > 2 && Random.nextFloat() < 0.16f -> 2
                            else -> 0
                        }
                        val p = spawnPlat(topGen, wpx, platW, kind)
                        if (kind == 0 && Random.nextFloat() < 0.10f) p.boot = true
                        plats.add(p)
                        if (score > 4 && Random.nextFloat() < 0.30f) {
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
            plats.forEach { p ->
                if (p.alive) {
                    Box(
                        Modifier
                            // read `tick` inside the placement lambda so it re-runs every
                            // physics frame — smooth motion without recomposing the scene
                            .offset { tick; IntOffset(p.x.toInt(), (p.y - camY).toInt()) }
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
                Image(
                    painterResource(faces[o.face].first), faces[o.face].second,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .offset { tick; IntOffset((o.x - obstSz / 2).toInt(), (o.y - camY - obstSz / 2).toInt()) }
                        .size(with(density) { obstSz.toDp() })
                        .clip(CircleShape)
                        .border(3.dp, Color(0xFFFF5252), CircleShape),
                )
            }
            if (!failed) {
                Image(
                    painterResource(R.drawable.farmor1), "Farmor",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .offset { tick; IntOffset((px - playerSz / 2).toInt(), (py - camY - playerSz / 2).toInt()) }
                        .size(with(density) { playerSz.toDp() })
                        .clip(CircleShape)
                        .border(4.dp, Color(0xFFD7FF45), CircleShape),
                )
            }
        }

        Column(Modifier.align(Alignment.TopCenter).padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("HÖJD: $score", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Black, fontSize = 20.sp)
            Text("SLÅ $target", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
        }
        if (!failed) {
            Text(
                "Dra i sidled för att styra", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
            )
        } else {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Farmor ramlade!", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 10.dp))
                ScorePanel(score, target, onRetry = { restart++ }, onContinue = onWin)
            }
        }
    }
}

// ---------- Game 9: Dark Maze (guide Farmor out) ----------

private class MazeCell(
    var top: Boolean = true, var right: Boolean = true,
    var bottom: Boolean = true, var left: Boolean = true, var visited: Boolean = false,
)

@Composable
private fun MazeGame(onWin: () -> Unit) {
    val cols = 11
    val rows = 15
    val density = LocalDensity.current
    var restart by remember { mutableIntStateOf(0) }

    // carve a perfect maze with iterative depth-first search (unique path, always solvable)
    val maze = remember(restart) {
        val g = Array(cols) { Array(rows) { MazeCell() } }
        val stack = ArrayDeque<Pair<Int, Int>>()
        g[0][0].visited = true
        stack.addLast(0 to 0)
        while (stack.isNotEmpty()) {
            val (x, y) = stack.last()
            val nbrs = buildList {
                if (y > 0 && !g[x][y - 1].visited) add(Triple(x, y - 1, 0))
                if (x < cols - 1 && !g[x + 1][y].visited) add(Triple(x + 1, y, 1))
                if (y < rows - 1 && !g[x][y + 1].visited) add(Triple(x, y + 1, 2))
                if (x > 0 && !g[x - 1][y].visited) add(Triple(x - 1, y, 3))
            }
            if (nbrs.isEmpty()) { stack.removeLast(); continue }
            val (nx, ny, dir) = nbrs[Random.nextInt(nbrs.size)]
            when (dir) {
                0 -> { g[x][y].top = false; g[nx][ny].bottom = false }
                1 -> { g[x][y].right = false; g[nx][ny].left = false }
                2 -> { g[x][y].bottom = false; g[nx][ny].top = false }
                else -> { g[x][y].left = false; g[nx][ny].right = false }
            }
            g[nx][ny].visited = true
            stack.addLast(nx to ny)
        }
        g
    }

    var fx by remember(restart) { mutableIntStateOf(0) }
    var fy by remember(restart) { mutableIntStateOf(0) }
    var won by remember(restart) { mutableStateOf(false) }
    val exitX = cols - 1
    val exitY = rows - 1

    val wallColor = Color(0xFF000000)
    val floorColor = MaterialTheme.colorScheme.surfaceVariant

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wpx = with(density) { maxWidth.toPx() }
        val hpx = with(density) { maxHeight.toPx() }
        // zoomed in: only ~5 cells fit across, the rest of the maze lives off-screen
        val cell = minOf(wpx, hpx) / 5f
        val visionR = 1.6f   // tighter flashlight now that we're zoomed in

        // camera glides so Farmor stays centred; the maze scrolls around her (Pac-Man style)
        val camX by animateFloatAsState((fx + 0.5f) * cell - wpx / 2f, label = "camx")
        val camY by animateFloatAsState((fy + 0.5f) * cell - hpx / 2f, label = "camy")

        fun tryMove(dx: Int, dy: Int) {
            if (won) return
            val c = maze[fx][fy]
            val ok = when {
                dx == 1 -> !c.right
                dx == -1 -> !c.left
                dy == 1 -> !c.bottom
                dy == -1 -> !c.top
                else -> false
            }
            if (ok) {
                fx += dx; fy += dy
                if (fx == exitX && fy == exitY) { won = true; onWin() }
            }
        }

        var accX by remember(restart) { mutableFloatStateOf(0f) }
        var accY by remember(restart) { mutableFloatStateOf(0f) }
        Canvas(
            Modifier.fillMaxSize().pointerInput(restart) {
                detectDragGestures(onDragEnd = { accX = 0f; accY = 0f }) { _, drag ->
                    accX += drag.x; accY += drag.y
                    val thresh = cell * 0.42f
                    if (abs(accX) > abs(accY)) {
                        if (accX > thresh) { tryMove(1, 0); accX = 0f; accY = 0f }
                        else if (accX < -thresh) { tryMove(-1, 0); accX = 0f; accY = 0f }
                    } else {
                        if (accY > thresh) { tryMove(0, 1); accX = 0f; accY = 0f }
                        else if (accY < -thresh) { tryMove(0, -1); accX = 0f; accY = 0f }
                    }
                }
            },
        ) {
            drawRect(Color(0xFF05070D))   // fog of war — you only see near Farmor
            // 1) continuous floor for lit cells — no per-cell borders, so open corridors merge
            for (x in 0 until cols) for (y in 0 until rows) {
                val dist = hypot((x - fx).toFloat(), (y - fy).toFloat())
                if (dist > visionR + 1f) continue
                val a = if (dist <= visionR) 1f else (visionR + 1f - dist).coerceIn(0f, 1f)
                val sx = x * cell - camX
                val sy = y * cell - camY
                val isExit = x == exitX && y == exitY
                drawRect(
                    color = (if (isExit) Color(0xFF64FF57) else floorColor).copy(alpha = a * 0.92f),
                    topLeft = Offset(sx, sy),
                    size = Size(cell, cell),
                )
            }
            // 2) thick bright walls on top — the ONLY lines drawn, so they stand out clearly
            val sw = cell * 0.14f
            for (x in 0 until cols) for (y in 0 until rows) {
                val dist = hypot((x - fx).toFloat(), (y - fy).toFloat())
                if (dist > visionR + 1f) continue
                val a = if (dist <= visionR) 1f else (visionR + 1f - dist).coerceIn(0f, 1f)
                val sx = x * cell - camX
                val sy = y * cell - camY
                val c = maze[x][y]
                val w = wallColor.copy(alpha = a)
                if (c.top) drawLine(w, Offset(sx, sy), Offset(sx + cell, sy), sw, cap = StrokeCap.Round)
                if (c.bottom) drawLine(w, Offset(sx, sy + cell), Offset(sx + cell, sy + cell), sw, cap = StrokeCap.Round)
                if (c.left) drawLine(w, Offset(sx, sy), Offset(sx, sy + cell), sw, cap = StrokeCap.Round)
                if (c.right) drawLine(w, Offset(sx + cell, sy), Offset(sx + cell, sy + cell), sw, cap = StrokeCap.Round)
            }
        }

        // Farmor — big, glides with the camera toward the centre of the screen
        val fSize = cell * 0.7f
        Image(
            painterResource(R.drawable.farmor1), "Farmor",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .offset {
                    IntOffset(
                        ((fx + 0.5f) * cell - camX - fSize / 2f).toInt(),
                        ((fy + 0.5f) * cell - camY - fSize / 2f).toInt(),
                    )
                }
                .size(with(density) { fSize.toDp() })
                .clip(CircleShape)
                .border(4.dp, Color(0xFFFFC107), CircleShape),
        )

        Text(
            "Hitta ut ur mörkret! 🔦",
            color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Black, fontSize = 16.sp,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 6.dp)
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
        Row(
            Modifier.align(Alignment.BottomCenter).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Dra för att gå",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
                modifier = Modifier.background(MaterialTheme.colorScheme.background.copy(alpha = 0.7f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
            )
            Spacer(Modifier.width(10.dp))
            OutlinedButton(onClick = { restart++ }) { Text("Ny labyrint") }
        }
    }
}
