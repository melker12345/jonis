package com.jonis.thirty

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
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
private const val APP_VERSION = 27

// LOCAL DEV ONLY — SET BACK TO false BEFORE CUTTING A RELEASE.
// Unlocks every node so the whole road map is clickable without playing through it, and
// puts a branch switcher on the hub so both tails can be inspected without replaying
// node 20. Progress is not written to prefs while this is on, so dev state stays
// repeatable and can't leak into a real playthrough when the flag goes off again.
const val DEV_UNLOCK_ALL = false

// Raw URL of version.json in your GitHub repo. REPLACE <YOUR_USER>/<YOUR_REPO>.
private const val VERSION_URL =
    "https://raw.githubusercontent.com/melker12345/jonis/master/version.json"

// SHA-256 of the present-gate code. Plaintext is "GODIS-250" (case-insensitive).
// Change it by running:  printf '%s' "YOURCODE" | sha256sum
private const val GATE_CODE_HASH =
    "33a86e7311d126f1fde5fa0b7ba0d9929043efd9b64e825fd5df6c073c5485b2"

// ---------- Journey data ----------

enum class GameType {
    BEER, WHAC, MEMORY, SEQUENCE, TAP, NINJA, STACK, JUMP, MAZE, GATE, CHALLENGE, GIFT,
    FLAPPY, GUESS, MINESWEEPER, FIND, PLUNGER, FINALE, LOCKED,
}

// codeHash: SHA-256 of the (uppercased) code Melker texts over once he's seen the photo
//           proof — only set for CHALLENGE nodes. flavor: optional italic intro line.
data class Quest(
    val title: String,
    val tag: String,
    val type: GameType,
    val goal: Int,
    val codeHash: String? = null,
    val flavor: String? = null,
    // optional line spelling out what counts as proof (e.g. "Bildbevis på Strava")
    val proofHint: String? = null,
    // optional literal sample output, rendered monospace and LEFT-aligned. The task text
    // itself is centred, which silently re-aligns any ASCII art embedded in it.
    val example: String? = null,
    // optional sub-tasks ("3 caféer, 3 bullar") shown as a tickable checklist. Ticking is
    // local encouragement only — the code from Melker is still what unlocks the node.
    val steps: List<String>? = null,
    // optional pool the app rolls from, so the task picks its own target (tunnelbana stop).
    // The roll is persisted, so it can't be re-rolled until something nicer comes up.
    val roll: List<String>? = null,
    // optional line ABOVE the task — the wind-up before the punchline. `flavor` reads as
    // an aside after the fact; a lead-in has to land before the task does. Quiet italic on
    // a challenge screen, the big shout on the finale.
    val lead: String? = null,
    // optional picture that IS the task ("recreate this photo") — no node uses it today
    val imageRes: Int? = null,
)

// Nodes 1-9 are minigames, node 10 is the present/candy-budget gate. Nodes 11-21 are
// IRL challenges: each shows the task, you text a photo to Melker, he texts back the
// unlock code (node 20 is a gift-reveal with just a Continue button). Nodes 22-30 stay
// grayed placeholders until designed.
private val baseQuests = listOf(
    Quest("Pappa är törstig!", "Styr Pappa in i ölstrålen tills han är full", GameType.BEER, 1),
    Quest("Familjememory", "Vänd korten och para ihop släkten", GameType.MEMORY, 2),
    Quest("Whac-en-Farmor", "Klappa släkten när de dyker upp — slå 32", GameType.WHAC, 32),
    Quest("Familjesekvens", "Härma ordningen — nå längd 10", GameType.SEQUENCE, 10),
    Quest("Klappa Farmor", "Klappa Farmor — slå 170 på tiden!", GameType.TAP, 170),
    Quest("Familje-Ninja", "Svep släkten, undvik bomben — slå 100", GameType.NINJA, 100),
    Quest("Släkttornet", "Släpp släkten i en hög — stapla 10", GameType.STACK, 10),
    Quest("Farmor Hoppar", "Studsa Farmor uppåt, väj för släkten — nå 55", GameType.JUMP, 55),
    Quest("Farmor i mörkret", "Farmor har gått vilse i en mörk labyrint! Hjälp henne hitta ut.", GameType.MAZE, 0),
    Quest("Presenten 🎁", "En budget för godis väntar — skicka bildbevis", GameType.GATE, 0),
    // --- IRL challenges (nodes 11-21) ---
    Quest(
        "Drick", "Sänk valfri enhet — med kapsyl! Skicka bildbevis.",
        GameType.CHALLENGE, 0, codeHash = "74bcea300e57da996fea1b1bf55242f92938e9f09343bb80c075d0adbb2cd105",
        flavor = "Tjohuuu!! Nu jävlar får du inviga din nya, fina, superbra och exklusiva ölöppnare 🍺",
    ),
    Quest(
        "Grimasparaden", "Skicka 5 av dina finaste grimaser i släktchatten.",
        GameType.CHALLENGE, 0, codeHash = "a997615456c511a2bb8fb9025e60f0e8c7a1a706209cccd31039ab871f528f7e",
    ),
    Quest(
        "Spelmaraton", "Spela i minst 3 timmar. Bildbevis om möjligt, annars intyg från Olivis.",
        GameType.CHALLENGE, 0, codeHash = "fd12cc292a7ee06f499c3a824a019a56df935f6398ac01967055f4f16bae1111",
        flavor = "Haha, den här kanske inte är alltför uppskattad av Olivia 😅 — men fear not, Olivis! 😁",
    ),
    Quest(
        "Sysslan", "Utför valfri syssla. Bildbevis eller intyg från Olivis. 😆",
        GameType.CHALLENGE, 0, codeHash = "6b47c9a9572152db9c9b519235782ff25c37f2b51656cee1a14114d4cbfff132",
    ),
    Quest(
        "Spring", "Spring minst 2,5 km eller cykla 5 km.",
        GameType.CHALLENGE, 0, codeHash = "28cf7436197cceaafe67e12cc079980cee9199b2c09a791fca62c78791785a8b",
        proofHint = "Bildbevis på Strava eller liknande",
    ),
    Quest(
        "Korthuset", "Bygg ett korthus med minst 3 kort i botten. Skicka bildbevis.",
        GameType.CHALLENGE, 0, codeHash = "59a367c8f9d7c70c0e0087172d8c672e224d204c95aac0f13a848dcfbaed2ad9",
    ),
    Quest(
        "Stå på händer", "Stå på händer. Skicka bildbevis!",
        GameType.CHALLENGE, 0, codeHash = "e99de9857817487a62aa0795e25a65f5ed2ab89aad39ed1bd16942e2b816f0e1",
        flavor = "Dags att stretcha, värma upp och förbereda dig mentalt — nu ska lederna stresstestas 🤸",
    ),
    Quest(
        "Stjärntrappan", "Printa en vänsterställd trappa av stjärnor, 1 till 30 rader, " +
            "i valfritt programmeringsspråk.",
        GameType.CHALLENGE, 0, codeHash = "a896edd8365cf0f2890d07e19fc9c8f6a94ed51605b6979c16bd479562ee29a1",
        flavor = "Phew — om du läser detta betyder det att du överlevde handståendet 😮‍💨",
        proofHint = "Bildbevis på resultatet + koden",
        example = "*\n**\n***\n****\n...\n****************************** (30 st)",
    ),
    Quest(
        "Mästerkocken", "Laga god mat. Skicka en bild!",
        GameType.CHALLENGE, 0, codeHash = "382c1e2e4b1ba67de34a387df93d14ce2f435ce287922b4e14e8caa5ea0e030f",
    ),
    Quest(
        "Öppna presenten 🎁", "Dags att öppna en present! Välj sedan hur du vill fortsätta.",
        GameType.GIFT, 0,
    ),
    Quest(
        "Picasso", "Rita något roligt, ingen streckgubbe.",
        GameType.CHALLENGE, 0, codeHash = "828620b56e3f97563c6dbf4b2dddeb953efc14cd3b9d6da89315d879ae3aefcc",
        lead = "Woooahh! Titta vilken fin penna Jonis har fått! Dags att inviga den.",
        proofHint = "Bildbevis",
    ),
)

// ---------- The fork at node 20 ----------
//
// Node 20 (the gift reveal) is where the road splits. "Lugnt" keeps the calm tail;
// "Äventyr" swaps nodes 22-29 for the out-of-the-house set. Node 21 (Picasso) is shared
// — the split only lands at 22. The choice is stored in prefs as "branch" and is FINAL:
// re-opening node 20 shows the road he took, with no way back to the other one. A
// switchable fork made both tails feel like a menu rather than a decision.
const val BRANCH_ADVENTURE = "ADV"
const val BRANCH_CHILL = "CHILL"

// The gift reveal, and the last node anyone can reach without having chosen a road.
const val FORK_NODE = 20

// The one task that sits on BOTH roads, word for word — the joke is the punchline of
// turning 30 and it lands the same either way. Only the code differs, so a replay of the
// other tail can't be waved through with the code he already has.
private fun massageQuest(codeHash: String) = Quest(
    "Massage", "Massera närmsta 33-åring — utan att klaga.",
    GameType.CHALLENGE, 0, codeHash = codeHash,
    lead = "Tycker du att det är jobbigt att vara 30? Föreställ dig att vara 33.",
    proofHint = "Bildbevis på massagen",
)

// Nodes 22-29 if he takes the adventure road. Bias: every task should leave a photo
// behind that's worth looking at in ten years — the "do it three times in three places"
// shape gives a set rather than a single snap.
private val adventureTail = listOf(
    Quest(
        "Tjockis", "Ät 30 bakelser på ett eller flera caféer samma dag. Skoja, ville bara " +
            "få dig att inse hur jävla gammal du e — ät 3 bakelser på tre olika caféer " +
            "(samma dag).",
        GameType.CHALLENGE, 0, codeHash = "a5d815d8cdf3d1046a7ed0fe3b990ff6dfd1dd2b518047e0656203004dcd63d1",
        lead = "Okej, vi vet att du egentligen vill äta fler bakelser än socialt acceptabelt. " +
            "Här kommer din ursäkt:",
        proofHint = "Selfie med bakelserna",
        steps = listOf("Café 1", "Café 2", "Café 3"),
    ),
    Quest(
        "Gubbe", "Hitta en rolig gubbhatt.",
        GameType.CHALLENGE, 0, codeHash = "7b79d407705d0ad0bde0688ca1d1a86f39659375a65f8d394e5693f6deed0c75",
        flavor = "(Bonuspoäng om den följer med på de nästa två uppdragen)\n" +
            "[Max budget 350 kr eller nåt, vet inte vad en hatt kostar :)) ]",
    ),
    Quest(
        "Pubrunda", "Selfie på minst tre olika pubar med valfri enhet.",
        GameType.CHALLENGE, 0, codeHash = "7d18b4f2c528649240dbd791a7bba8ca0a63d977978760b0b8e7c56af6b21575",
        lead = "Ifall du behövde en ursäkt att dricka på en måndag, här kommer den.",
        steps = listOf("Pub 1", "Pub 2", "Pub 3"),
    ),
    Quest(
        "Staty", "Hitta 2 statyer, härma deras pose och skicka bild.",
        GameType.CHALLENGE, 0, codeHash = "17b117dccd0c743c76058252304087627c885762767b3c2dc5252b6cc22427ad",
        steps = listOf("Staty 1", "Staty 2"),
    ),
    Quest(
        "Godmorgon!", "Skoja, ta en bild där du överdramatiserar hur bakis du är.",
        GameType.CHALLENGE, 0, codeHash = "2781ae72c8471eb00777e33d8f9dccc9ac2c3811ab2d564f302484f1765a8e3e",
        lead = "Gooodmorgon!! Dags för springturen, muhahah!",
    ),
    massageQuest("64467e9508999d33b20339d68c429b9c897c14f41e7d0c99fdea39611efdc27e"),
    Quest(
        "Hammarbybacken", "Ta dig upp för hela Hammarbybacken.",
        GameType.CHALLENGE, 0, codeHash = "7e5a36510da206c56cff337ad05fa3a2896e3aacf48b74e33990b08778a17c4f",
        proofHint = "Selfie på toppen",
    ),
    // Node 29: no code to text, just the photo quiz — the road earns its last node itself.
    Quest("Gissa åldern", "Hur gammal var personen på kortet?", GameType.GUESS, 2),
)

// Nodes 22-29 if he takes the calm road: mostly games, and the few real-world bits are
// short ones that leave a picture behind.
//
// Fågelskådning sits FIRST and Flappy Jonis last on purpose: node 22 asks him to record
// himself imitating five birds, and those voice notes are the sounds Flappy Jonis flaps
// to at node 27. The recording has to exist before the game that plays it back.
private val chillTail = listOf(
    Quest(
        "Fågelskådning", "Ta en bild på 5 fåglar, googla hur de låter och skicka en voice " +
            "note där du försöker imitera dem.",
        GameType.CHALLENGE, 0, codeHash = "49d55bcdf9b7e015cd20d51941d819a30c7b012da5f9a6c5d3aa6e3ce1c7aab5",
        lead = "Jag vet inte om ni gamlingar fågelskådar, men men.",
        proofHint = "5 fågelbilder + en voice note",
        steps = listOf("Fågel 1", "Fågel 2", "Fågel 3", "Fågel 4", "Fågel 5"),
    ),
    Quest(
        "Outfit", "Eftersom du valde den tråkiga vägen får du som straff att klä ut dig " +
            "till något roligt.",
        GameType.CHALLENGE, 0, codeHash = "c7b2234f795bae1b8e0e3edbea165fe631c26e2a98a3c5bb31044e5526929acf",
        proofHint = "Bildbevis",
    ),
    Quest("Gissa åldern", "Hur gammal var personen på kortet?", GameType.GUESS, 2),
    Quest("Minröjning", "Röj rutnätet — släkten ligger som minor under rutorna", GameType.MINESWEEPER, 0),
    Quest("Var är Farmor?", "Hitta Farmor bland släkten — 6 rundor på tid, max 2 missar", GameType.FIND, 6),
    Quest("Flappy Jonis", "Flaxa dig förbi rören — ta dig igenom 30", GameType.FLAPPY, 30),
    massageQuest("d693c42304a72a61a975e3b0b525ad68bf3b43d44a7ece7a9dedd927aad1b05b"),
    Quest(
        "Stopp i toan", "Ånej, det verkar som att det är stopp i toan!",
        GameType.PLUNGER, 0,
    ),
)

// Node 30 asks for nothing. Twenty-nine nodes of doing things, and then the road just
// ends at the real present — the one the whole map was built to hand over. Each road
// gets its own sign-off; `lead` is the big shout above the message.
private val adventureFinale = Quest(
    "Wippi!!!! 🎁", "Öppna din sista present! Den du har gjort alla dessa utmaningar för.",
    GameType.FINALE, 0, lead = "WIPPI!!!!",
)

private val chillFinale = Quest(
    "Grattis 🎁", "Nu är det äntligen färdigfirat! Här kommer sista presenten!\n" +
        "Grattis igen, din gamling!",
    GameType.FINALE, 0, lead = "JIPPIII!!!",
)

// Until the fork is taken, nodes 22-30 stay grayed: the road ahead genuinely isn't
// decided yet, which is the point.
private val lockedTail = List(9) { Quest("???", "Kommer snart", GameType.LOCKED, 0) }

fun questsFor(branch: String?): List<Quest> = baseQuests + when (branch) {
    BRANCH_ADVENTURE -> adventureTail + adventureFinale
    BRANCH_CHILL -> chillTail + chillFinale
    else -> lockedTail
}

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

// One birthday cake per year: node N shows the cake from Jonis' Nth birthday once the
// node is cleared. The 24th, 25th and 30th have no photo, so those nodes keep the plain
// numbered look.
private val cakes = mapOf(
    1 to R.drawable.cake01,
    2 to R.drawable.cake02,
    3 to R.drawable.cake03,
    4 to R.drawable.cake04,
    5 to R.drawable.cake05,
    6 to R.drawable.cake06,
    7 to R.drawable.cake07,
    8 to R.drawable.cake08,
    9 to R.drawable.cake09,
    10 to R.drawable.cake10,
    11 to R.drawable.cake11,
    12 to R.drawable.cake12,
    13 to R.drawable.cake13,
    14 to R.drawable.cake14,
    15 to R.drawable.cake15,
    16 to R.drawable.cake16,
    17 to R.drawable.cake17,
    18 to R.drawable.cake18,
    19 to R.drawable.cake19,
    20 to R.drawable.cake20,
    21 to R.drawable.cake21,
    22 to R.drawable.cake22,
    23 to R.drawable.cake23,
    26 to R.drawable.cake26,
    27 to R.drawable.cake27,
    28 to R.drawable.cake28,
    29 to R.drawable.cake29,
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
    var updateUrl by remember { mutableStateOf<String?>(null) }
    // Which tail he's on: null until he reaches node 20 and picks. Nodes 22-30 are drawn
    // from it, so the road map genuinely changes shape when he chooses.
    var branch by remember {
        mutableStateOf(prefs.getString("branch", if (DEV_UNLOCK_ALL) BRANCH_ADVENTURE else null))
    }
    val quests = remember(branch) { questsFor(branch) }
    // Fresh installs start with nodes 1–21 already cleared (through "Picasso") so a
    // reinstall doesn't make him replay a fortnight of challenges. `unlocked` is the highest
    // OPEN node, so 22 means 21 done and the first node of his chosen tail next. Existing
    // saves keep theirs, and the clamp guards against a stored value larger than the current
    // quest list.
    var unlocked by remember {
        mutableIntStateOf(
            if (DEV_UNLOCK_ALL) quests.size else prefs.getInt("unlocked", 22).coerceIn(1, quests.size),
        )
    }

    // check GitHub for a newer version on launch; silently ignore if offline/unreachable
    LaunchedEffect(Unit) {
        val latest = fetchLatestVersion()
        if (latest != null && latest.first > APP_VERSION) updateUrl = latest.second
    }

    // theme follows the system setting automatically — no manual toggle
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        // An unbuilt "???" slot is scenery, not a gate. If the next node up is one, step
        // over it so the road still reaches the finish while the slot stays empty.
        //
        // The fork IS a gate, though: with no road picked the tail is nine "???" slots, so
        // the skip above would walk straight to the finish and call it 29/30 done. Hold the
        // road at node 20 until the choice exists — a fresh install lands on the gift, and
        // the moment he picks a tail this clamp lifts and `unlocked` takes over again.
        val reach = remember(quests, unlocked, branch) {
            var u = unlocked.coerceIn(1, quests.size)
            if (branch == null) u = minOf(u, FORK_NODE)
            while (u < quests.size && quests[u - 1].type == GameType.LOCKED) u++
            u
        }
        val idx = selected
        if (idx == null) {
            Hub(
                quests = quests,
                unlocked = reach,
                branch = branch,
                onBranch = { picked ->
                    branch = picked
                    if (!DEV_UNLOCK_ALL) prefs.edit().putString("branch", picked).apply()
                },
            ) { selected = it }
        } else {
            // system back returns to the road map instead of closing the app
            BackHandler { selected = null }
            GameHost(
                quest = quests[idx],
                index = idx,
                branch = branch,
                // node 20 hands back the road he picked; every other node passes null
                onBranch = { picked ->
                    branch = picked
                    if (!DEV_UNLOCK_ALL) prefs.edit().putString("branch", picked).apply()
                },
                onWinContinue = {
                    selected = null
                    unlocked = minOf(quests.size, maxOf(unlocked, idx + 2))
                    if (!DEV_UNLOCK_ALL) prefs.edit().putInt("unlocked", unlocked).apply()
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
private fun Hub(
    quests: List<Quest>,
    unlocked: Int,
    branch: String?,
    onBranch: (String) -> Unit,
    open: (Int) -> Unit,
) {
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
                "hahaha trodde du att du bara skulle få presenter? Haha tänk igen, här kommer " +
                    "lite utmaningar du måste låsa upp innan du får presenterna.",
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
            if (DEV_UNLOCK_ALL) DevBranchSwitch(branch) { onBranch(it) }
            RoadMap(quests, unlocked, open)
        }
    }
}

// Dev-only: flip the tail without replaying node 20. Never shown in a release build.
@Composable
private fun DevBranchSwitch(branch: String?, onBranch: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(BRANCH_ADVENTURE to "DEV: äventyr", BRANCH_CHILL to "DEV: lugn").forEach { (key, label) ->
            val on = branch == key
            Button(
                onClick = { onBranch(key) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (on) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    label,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RoadMap(quests: List<Quest>, unlocked: Int, open: (Int) -> Unit) {
    val density = LocalDensity.current
    val wall = Color(0xFF2E5BFF)                 // pac-man maze wall (reads on light & dark)
    val pellet = Color(0xFFFFC107)
    val green = Color(0xFFB7E34B)
    val bg = MaterialTheme.colorScheme.background // corridor inner matches the parent bg
    val primary = MaterialTheme.colorScheme.primary
    val cols = 3
    val nodeSize = 84.dp
    // rowStep leaves enough gap below each node for a two-line label without the next
    // row's node overlapping it (node 84 + up to ~2 lines of 11sp text + padding)
    val rowStep = 148.dp
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
                val isGift = quests[i].type == GameType.GATE || quests[i].type == GameType.GIFT
                // placeholders are never active/completed — they stay grayed "more to come"
                val active = !placeholder && i == unlocked - 1
                val completed = !placeholder && i < unlocked - 1
                // cleared nodes wear that year's birthday cake instead of a flat green tile
                val cake = if (completed) cakes[i + 1] else null
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
                            .border(
                                if (active || cake != null) 3.dp else 0.dp,
                                if (cake != null) green else wall,
                                RoundedCornerShape(18.dp),
                            )
                            // active node plays; completed nodes can be replayed; placeholders don't open
                            .clickable(enabled = active || completed) { open(i) },
                        contentAlignment = Alignment.Center,
                    ) {
                        when {
                            placeholder -> Text("?", fontSize = 30.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            cake != null -> {
                                Image(
                                    painterResource(cake),
                                    "Tårta ${i + 1} år",
                                    Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop,
                                )
                                // small green chip keeps the node number readable over the photo
                                Text(
                                    "%02d".format(i + 1),
                                    fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Black,
                                    color = Color(0xFF14203A),
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(4.dp)
                                        .background(green, RoundedCornerShape(6.dp))
                                        .padding(horizontal = 4.dp, vertical = 1.dp),
                                )
                            }
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
private fun GameHost(
    quest: Quest,
    index: Int,
    branch: String?,
    onBranch: (String) -> Unit,
    onWinContinue: () -> Unit,
    onBack: () -> Unit,
) {
    var won by remember(index) { mutableStateOf(false) }
    // Surface (not a bare Box) so LocalContentColor resolves to onBackground —
    // otherwise default text/icons render black and vanish in dark theme.
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onBack) { Icon(Icons.Outlined.ArrowBack, "Tillbaka") }
                    Column(Modifier.padding(start = 4.dp)) {
                        Text("UPPDRAG %02d".format(index + 1), color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(quest.title, fontSize = 22.sp, fontWeight = FontWeight.Black)
                    }
                }
                // IRL challenge / gift screens render the task themselves (as the hero),
                // so skip the small tag line here to avoid a de-emphasised duplicate.
                if (quest.type != GameType.CHALLENGE && quest.type != GameType.GIFT &&
                    quest.type != GameType.FINALE
                ) {
                    Text(quest.tag, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(vertical = 10.dp), textAlign = TextAlign.Center)
                }
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
                        GameType.CHALLENGE -> ChallengeScreen(quest) { won = true }
                        GameType.GIFT -> GiftScreen(
                            branch,
                            onChoose = { picked -> onBranch(picked); won = true },
                            onContinue = { won = true },
                        )
                        GameType.FLAPPY -> FlappyGame(quest.goal) { won = true }
                        GameType.GUESS -> GuessAgeGame(quest.goal) { won = true }
                        GameType.MINESWEEPER -> MinesweeperGame { won = true }
                        GameType.FIND -> FindGame(quest.goal) { won = true }
                        GameType.PLUNGER -> PlungerGame { won = true }
                        GameType.FINALE -> FinaleScreen(quest) { won = true }
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
            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
        )
        Text(
            "(Köp en valfri dryck med kapsyl om du inte har en hemma 😉)",
            fontSize = 13.sp,
            fontStyle = FontStyle.Italic,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 16.dp, start = 6.dp, end = 6.dp),
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

// ---------- IRL challenge: task + photo-proof code unlock ----------

// Shows the challenge (title + tag are drawn by GameHost), an optional flavor line, and a
// code field. Player texts Melker a photo; he texts back the code that unlocks the node.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChallengeScreen(quest: Quest, onUnlock: () -> Unit) {
    var code by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("jonis", Context.MODE_PRIVATE) }
    // checklist ticks and the rolled target are per-quest and survive leaving the screen
    val stepKey = "steps_${quest.title}"
    val rollKey = "roll_${quest.title}"
    // keyed on the quest: without it a slot reused by the next node would keep the old
    // list, and a shorter checklist would index out of bounds
    val ticked = remember(quest.title) {
        mutableStateListOf<Boolean>().apply {
            val saved = prefs.getString(stepKey, "") ?: ""
            repeat(quest.steps?.size ?: 0) { add(saved.getOrNull(it) == '1') }
        }
    }
    var rolled by remember(quest.title) { mutableStateOf(prefs.getString(rollKey, null)) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().verticalScroll(scroll),
    ) {
        // --- the task is the hero: a big bold card front and centre ---
        // --- lead-in: the wind-up, so the joke lands before the task does ---
        quest.lead?.let {
            Text(
                it,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                fontStyle = FontStyle.Italic,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 14.dp, start = 6.dp, end = 6.dp),
            )
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(20.dp))
                .padding(vertical = 22.dp, horizontal = 18.dp),
        ) {
            Text(
                "DIN UTMANING",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                quest.tag,
                fontSize = 23.sp,
                lineHeight = 30.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
        // when the task is "recreate this", the picture has to be big enough to study
        quest.imageRes?.let {
            Image(
                painterResource(it),
                "Bilden du ska återskapa",
                Modifier
                    .padding(top = 14.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp)),
                contentScale = ContentScale.FillWidth,
            )
        }
        // sample output gets its own left-aligned monospace block — inside the centred
        // task text the columns drift and it reads as a centred pyramid
        quest.example?.let {
            Text(
                it,
                fontSize = 13.sp,
                lineHeight = 17.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Start,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .padding(top = 14.dp)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        // three-part tasks get a checklist so there's progress to feel inside one node
        quest.steps?.let { steps ->
            Column(Modifier.padding(top = 18.dp).fillMaxWidth()) {
                steps.forEachIndexed { i, label ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable {
                                ticked[i] = !ticked[i]
                                prefs.edit()
                                    .putString(stepKey, ticked.joinToString("") { if (it) "1" else "0" })
                                    .apply()
                            }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (ticked[i]) "✅" else "⬜", fontSize = 18.sp)
                        Text(
                            label,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = 10.dp),
                        )
                    }
                }
            }
        }
        // the app picks the target once, then keeps it — no re-rolling until it's nicer
        quest.roll?.let { pool ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 18.dp).fillMaxWidth(),
            ) {
                if (rolled == null) {
                    Button(onClick = {
                        val pick = pool[Random.nextInt(pool.size)]
                        rolled = pick
                        prefs.edit().putString(rollKey, pick).apply()
                    }) { Text("SLUMPA 🎲", fontWeight = FontWeight.Black) }
                } else {
                    Text("DITT MÅL", fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, color = MaterialTheme.colorScheme.primary)
                    Text(
                        rolled!!,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
        // --- flavour line: clearly secondary, quiet italic under the task ---
        quest.flavor?.let {
            Text(
                it,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                fontStyle = FontStyle.Italic,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp, start = 6.dp, end = 6.dp),
            )
        }
        Spacer(Modifier.height(28.dp))
        // --- proof + unlock ---
        Text("📸", fontSize = 40.sp)
        Text(
            "Skicka bildbeviset via SMS till världens bästa lillebror, så får du koden som " +
                "låser upp nästa uppdrag.",
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, start = 6.dp, end = 6.dp),
        )
        quest.proofHint?.let {
            Text(
                "[ $it ]",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp, start = 6.dp, end = 6.dp),
            )
        }
        Spacer(Modifier.height(18.dp))
        OutlinedTextField(
            value = code,
            onValueChange = { code = it; wrong = false },
            label = { Text("Ange kod") },
            singleLine = true,
            isError = wrong,
        )
        if (wrong) Text("Fel kod. Skicka beviset först, din lilla fuskare — koderna är enkrypterade! 😉", color = MaterialTheme.colorScheme.error, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                if (sha256(code.trim().uppercase()) == quest.codeHash) onUnlock() else wrong = true
            },
            enabled = code.isNotBlank(),
        ) { Text("Lås upp", fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(12.dp))
    }
}

// Node 20: the gift reveal, and the fork. Both roads are a plain button press — the
// choice is the point, and nothing gates it. It is also final: once "branch" is in prefs
// this screen stops offering the other road, because a fork you can walk back is a menu,
// not a decision. Re-opening the node just shows him the road he took.
@Composable
private fun GiftScreen(branch: String?, onChoose: (String) -> Unit, onContinue: () -> Unit) {
    val scroll = rememberScrollState()
    if (branch != null) {
        ChosenRoad(branch, onContinue)
        return
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(12.dp).verticalScroll(scroll),
    ) {
        Text("🎁", fontSize = 72.sp)
        Text(
            "Grattisss!!!!\nÖppna din present! Och välj hur du vill fortsätta detta äventyr!",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 18.dp),
        )
        Text(
            "VÄGEN HÄRIFRÅN",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
            color = MaterialTheme.colorScheme.primary,
        )
        // the calm road: plainly described, no shame attached to taking it
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .padding(top = 14.dp)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(18.dp))
                .padding(18.dp),
        ) {
            Text("🛋️  Lugnt", fontSize = 19.sp, fontWeight = FontWeight.Black)
            Text(
                "Minispel och enklare saker, typ 1 dag (som på restaurangen).",
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
            )
            Button(onClick = { onChoose(BRANCH_CHILL) }) {
                Text("Lugnt 🛋️", fontWeight = FontWeight.Bold)
            }
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .padding(top = 14.dp)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(18.dp))
                .padding(18.dp),
        ) {
            Text("🔥  Äventyr!", fontSize = 19.sp, fontWeight = FontWeight.Black)
            Text(
                "Utmaningar som kräver lite mer tid och energi än nivåerna 10–20.",
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
            )
            Button(onClick = { onChoose(BRANCH_ADVENTURE) }) {
                Text("Äventyr 🔥", fontWeight = FontWeight.Bold)
            }
        }
        Text(
            "Välj noga — vägen går inte att byta sen.",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

// What node 20 shows on every visit after the first: the road he is on, and no door back
// to the other one.
@Composable
private fun ChosenRoad(branch: String?, onContinue: () -> Unit) {
    val adventure = branch == BRANCH_ADVENTURE
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(12.dp),
    ) {
        Text(if (adventure) "🔥" else "🛋️", fontSize = 72.sp)
        Text(
            "DIN VÄG",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 18.dp),
        )
        Text(
            if (adventure) "Äventyr!" else "Lugnt",
            fontSize = 30.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            if (adventure) {
                "Du valde äventyret. Ut ur lägenheten, hela vägen till trettio."
            } else {
                "Du valde den lugna vägen. Resten går att göra hemifrån."
            },
            fontSize = 14.sp,
            lineHeight = 20.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp, start = 10.dp, end = 10.dp),
        )
        Button(onClick = onContinue, modifier = Modifier.padding(top = 24.dp)) {
            Text("Fortsätt →", fontWeight = FontWeight.Bold)
        }
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

// ---------- Game 11 (chill tail): Flappy Jonis ----------

private class Pipe(var x: Float, val gapY: Float, var scored: Boolean = false)

@Composable
private fun FlappyGame(target: Int, onWin: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val wpx = with(density) { maxWidth.toPx() }
        val hpx = with(density) { maxHeight.toPx() }
        val birdSz = with(density) { 52.dp.toPx() }
        val pipeW = with(density) { 58.dp.toPx() }
        // gap and spacing are fractions of the actual play area, not fixed dp: a fixed
        // 178.dp gap is over a third of a short screen and negative on a very short one.
        // 0.42 of the screen was a barn door — 0.26 leaves about two and a half birds of
        // clearance, which is roughly where the original game sits.
        val gap = (hpx * 0.26f).coerceAtLeast(birdSz * 2.5f)
        val spacing = wpx * 0.66f

        var by_ by remember { mutableFloatStateOf(hpx / 2f) }
        var vy by remember { mutableFloatStateOf(0f) }
        var score by remember { mutableIntStateOf(0) }
        var started by remember { mutableStateOf(false) }
        var ended by remember { mutableStateOf(false) }
        var restart by remember { mutableIntStateOf(0) }
        var tick by remember { mutableIntStateOf(0) }
        val pipes = remember { mutableStateListOf<Pipe>() }

        // tuned so one flap clears roughly a third of the screen and the bird takes about
        // a second to fall its full height — the old values gave a 128 px hop against a
        // 490 px gap, which is why it played like a brick
        val gravity = hpx * 1.15f
        val flapV = hpx * 0.46f
        val speed = wpx * 0.38f
        val bx = wpx * 0.26f

        // wider vertical spread of gaps, so consecutive pipes demand real climbing
        fun gapCentre() = hpx * (0.22f + Random.nextFloat() * 0.56f)

        fun reset() {
            pipes.clear()
            by_ = hpx / 2f; vy = 0f; score = 0; ended = false; started = false
            repeat(3) { pipes.add(Pipe(wpx + spacing * 0.6f + it * spacing, gapCentre())) }
        }
        LaunchedEffect(restart) { reset() }

        // nothing moves until the first tap: the old version dropped him the instant the
        // node opened, so the first run was over before he knew the game had begun
        LaunchedEffect(restart, started) {
            if (!started) return@LaunchedEffect
            var last = 0L
            while (!ended) {
                val now = withFrameNanos { it }
                if (last != 0L) {
                    val dt = ((now - last) / 1_000_000_000f).coerceAtMost(0.033f)
                    vy += gravity * dt
                    by_ += vy * dt
                    // the roof only blocks, it doesn't kill — dying to the ceiling in a
                    // game where up is the only input you have is pure frustration
                    if (by_ < birdSz / 2f) { by_ = birdSz / 2f; vy = 0f }
                    if (by_ > hpx - birdSz / 2f) ended = true
                    for (p in pipes) {
                        p.x -= speed * dt
                        val overlapX = bx + birdSz * 0.32f > p.x && bx - birdSz * 0.32f < p.x + pipeW
                        val inGap = by_ - birdSz * 0.32f > p.gapY - gap / 2f &&
                            by_ + birdSz * 0.32f < p.gapY + gap / 2f
                        if (overlapX && !inGap) ended = true
                        if (!p.scored && p.x + pipeW < bx) {
                            p.scored = true
                            score++
                            if (score >= target) { onWin(); ended = true }
                        }
                    }
                    // recycle off-screen pipes to the back of the queue, spacing preserved
                    pipes.filter { it.x + pipeW < 0f }.forEach { gone ->
                        pipes.remove(gone)
                        val furthest = pipes.maxOfOrNull { q -> q.x } ?: wpx
                        pipes.add(Pipe(furthest + spacing, gapCentre()))
                    }
                    // Pipe is a plain class, so moving one changes nothing Compose watches.
                    // This counter is the frame signal the canvas below reads.
                    tick++
                }
                last = now
            }
        }

        Box(
            Modifier.fillMaxSize().pointerInput(restart) {
                // onPress, not onTap: waiting for the finger to lift adds lag to every flap
                detectTapGestures(
                    onPress = {
                        if (!ended) {
                            started = true
                            vy = -flapV
                        }
                    },
                )
            },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                tick.let {
                    for (p in pipes) {
                        val topH = p.gapY - gap / 2f
                        if (topH > 0f) drawRect(Color(0xFF2E5BFF), Offset(p.x, 0f), Size(pipeW, topH))
                        val botY = p.gapY + gap / 2f
                        if (botY < hpx) drawRect(Color(0xFF2E5BFF), Offset(p.x, botY), Size(pipeW, hpx - botY))
                    }
                }
            }
            Image(
                painterResource(R.drawable.jonis),
                "Jonis",
                Modifier
                    .offset { IntOffset((bx - birdSz / 2f).toInt(), (by_ - birdSz / 2f).toInt()) }
                    .size(with(density) { birdSz.toDp() })
                    .clip(CircleShape)
                    .graphicsLayer { rotationZ = (vy / hpx * 48f).coerceIn(-25f, 60f) },
                contentScale = ContentScale.Crop,
            )
            Text(
                "$score / $target",
                fontSize = 26.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
            )
            if (!started && !ended) {
                Text(
                    "TRYCK FÖR ATT FLAXA",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            if (ended && score < target) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    ScorePanel(score, target, onRetry = { restart++ }, onContinue = onWin)
                }
            }
        }
    }
}

// ---------- Game 12 (both tails): guess how old the person in the photo was ----------

// One round. The source file in guess_age/ is named after its own answer, so `answer` is
// just that number: the age of the person in the picture. The one photo with TWO people in
// it can't ask for an age — whose? — so it asks for the year instead, and `askYear` swaps
// both the question and the shape of the decoys.
private data class GuessPhoto(val res: Int, val answer: Int, val askYear: Boolean = false)

// Drop another file in guess_age/ named after the age, run the same magick crop into
// res/drawable, add a line here, and the game grows a round on its own.
private val guessPhotos = listOf(
    GuessPhoto(R.drawable.ga_age08, 8),
    GuessPhoto(R.drawable.ga_age09a, 9),
    GuessPhoto(R.drawable.ga_age09b, 9),
    GuessPhoto(R.drawable.ga_age10, 10),
    GuessPhoto(R.drawable.ga_age13, 13),
    GuessPhoto(R.drawable.ga_age15a, 15),
    GuessPhoto(R.drawable.ga_age15b, 15),
    GuessPhoto(R.drawable.ga_age16a, 16),
    GuessPhoto(R.drawable.ga_age16b, 16),
    GuessPhoto(R.drawable.ga_age26, 26),
    GuessPhoto(R.drawable.ga_age28, 28),
    GuessPhoto(R.drawable.ga_year2012, 2012, askYear = true),
)

@Composable
private fun GuessAgeGame(target: Int, onWin: () -> Unit) {
    var restart by remember { mutableIntStateOf(0) }
    val photos = remember(restart) { guessPhotos.shuffled() }
    val rounds = photos.size
    var round by remember(restart) { mutableIntStateOf(0) }
    var score by remember(restart) { mutableIntStateOf(0) }
    var picked by remember(restart) { mutableStateOf<Int?>(null) }

    // the pass mark grows with the pool — 8-of-12 today — so dropping more photos into
    // `guessPhotos` never turns the node into a formality
    val pass = maxOf(target, (rounds * 3 + 4) / 5)
    if (round >= rounds) {
        ScorePanel(score, pass, onRetry = { restart++ }, onContinue = onWin)
        return
    }

    val photo = photos[round]
    val answer = photo.answer
    // decoys sit close to the real answer, so it's a look-at-the-picture job, not a coin
    // flip. Ages are clamped to a plausible span; years need no clamp, the offsets are it.
    val options = remember(restart, round) {
        val spread = listOf(-6, -4, -3, -2, 2, 3, 4, 6)
            .map { answer + it }
            .filter { photo.askYear || it in 1..34 }
        (spread.shuffled().take(3) + answer).shuffled()
    }
    val scroll = rememberScrollState()

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().verticalScroll(scroll),
    ) {
        Text(
            "RUNDA ${round + 1} / $rounds   ·   RÄTT: $score",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            color = MaterialTheme.colorScheme.primary,
        )
        Image(
            painterResource(photo.res),
            if (photo.askYear) "Vilket år?" else "Hur gammal?",
            Modifier.padding(top = 12.dp).size(240.dp).clip(RoundedCornerShape(20.dp)),
            contentScale = ContentScale.Crop,
        )
        Text(
            // two people in the frame, so this one asks for the year the picture was taken
            if (photo.askYear) "Vilket år är kortet taget?" else "Hur gammal är personen på kortet?",
            fontSize = 17.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier.padding(top = 16.dp, bottom = 12.dp),
        )
        options.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 5.dp)) {
                row.forEach { option ->
                    val state = picked
                    Button(
                        onClick = {
                            if (state == null) {
                                picked = option
                                if (option == answer) score++
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = when {
                                state == null -> MaterialTheme.colorScheme.primary
                                option == answer -> Color(0xFF6FBF3F)
                                option == state -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            },
                        ),
                        modifier = Modifier.width(120.dp),
                    ) { Text(if (photo.askYear) "$option" else "$option år", fontWeight = FontWeight.Black) }
                }
            }
        }
        if (picked != null) {
            Text(
                when {
                    picked == answer && photo.askYear -> "Rätt! $answer."
                    picked == answer -> "Rätt! $answer år."
                    photo.askYear -> "Nej — kortet är från $answer."
                    else -> "Nej — personen var $answer år."
                },
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
            Button(
                onClick = { picked = null; round++ },
                modifier = Modifier.padding(top = 10.dp, bottom = 10.dp),
            ) { Text(if (round == rounds - 1) "Se resultatet" else "Nästa →", fontWeight = FontWeight.Bold) }
        }
    }
}

// ---------- Game 13 (chill tail): minesweeper with family faces as mines ----------

// 8x10 with 12 mines: 15 % density is a touch below the classic beginner board (12 %) but
// well under intermediate (16 %), and 8 columns is what fits a phone in portrait with a
// finger-sized cell.
private const val MINE_COLS = 8
private const val MINE_ROWS = 10
private const val MINE_COUNT = 12
private const val MINE_CELLS = MINE_COLS * MINE_ROWS

// Mid-tone digits: the classic palette's navy 1 and black 8 vanish in dark theme, so every
// colour here is picked to sit between the light and the dark cell fill.
private val mineDigitColors = listOf(
    Color(0xFF2E5BFF), // 1
    Color(0xFF2E9E4F), // 2
    Color(0xFFE04B4B), // 3
    Color(0xFF9B5DE5), // 4
    Color(0xFFE08A2E), // 5
    Color(0xFF17A2B8), // 6
    Color(0xFFE05A9C), // 7
    Color(0xFF9AA3B2), // 8
)

private fun mineNeighbours(i: Int): List<Int> {
    val r = i / MINE_COLS
    val c = i % MINE_COLS
    val out = ArrayList<Int>(8)
    for (dr in -1..1) {
        for (dc in -1..1) {
            if (dr == 0 && dc == 0) continue
            val rr = r + dr
            val cc = c + dc
            if (rr in 0 until MINE_ROWS && cc in 0 until MINE_COLS) out.add(rr * MINE_COLS + cc)
        }
    }
    return out
}

// Built on the FIRST tap, not when the node opens — that's the only way the opening move
// can be guaranteed safe.
private class MineField(firstTap: Int) {
    val mine = BooleanArray(MINE_CELLS)
    val near = IntArray(MINE_CELLS)
    val faceRes = IntArray(MINE_CELLS)
    val faceName = Array(MINE_CELLS) { "" }

    init {
        // the whole 3x3 around the first tap is kept clear, not just the tapped cell: a
        // first tap that opens a lone "3" is technically safe and still a dead end, and the
        // flood needs a zero to spread out from. 12 mines into the remaining 71 cells.
        val free = mineNeighbours(firstTap) + firstTap
        for (i in (0 until MINE_CELLS).filter { it !in free }.shuffled().take(MINE_COUNT)) {
            mine[i] = true
            val (res, who) = faces[Random.nextInt(faces.size)]
            faceRes[i] = res
            faceName[i] = who
        }
        for (i in 0 until MINE_CELLS) near[i] = mineNeighbours(i).count { mine[it] }
    }
}

@Composable
private fun MinesweeperGame(onWin: () -> Unit) {
    var restart by remember { mutableIntStateOf(0) }
    var board by remember(restart) { mutableStateOf<MineField?>(null) }
    val revealed = remember(restart) {
        mutableStateListOf<Boolean>().apply { addAll(List(MINE_CELLS) { false }) }
    }
    val flags = remember(restart) {
        mutableStateListOf<Boolean>().apply { addAll(List(MINE_CELLS) { false }) }
    }
    var lostOn by remember(restart) { mutableStateOf<Int?>(null) }
    var won by remember(restart) { mutableStateOf(false) }
    val lost = lostOn != null
    val flagged = flags.count { it }

    // iterative: a corner-to-corner flood on an empty-ish board is ~80 frames deep, and
    // recursion here would be one long chain of stack frames for no gain
    fun flood(f: MineField, start: Int) {
        val queue = ArrayDeque<Int>()
        queue.addLast(start)
        while (queue.isNotEmpty()) {
            val i = queue.removeLast()
            if (revealed[i] || flags[i]) continue
            revealed[i] = true
            if (f.near[i] == 0) mineNeighbours(i).forEach { if (!revealed[it]) queue.addLast(it) }
        }
    }

    fun dig(i: Int) {
        if (lostOn != null || won || flags[i] || revealed[i]) return
        val f = board ?: MineField(i).also { board = it }
        if (f.mine[i]) {
            lostOn = i
            for (k in 0 until MINE_CELLS) if (f.mine[k]) revealed[k] = true
            return
        }
        flood(f, i)
        // every safe cell open = win; the mines themselves never need flagging, which is
        // the standard rule and spares him a last round of bookkeeping
        if (revealed.count { it } == MINE_CELLS - MINE_COUNT) won = true
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Sized in dp off the actual box instead of a fixed cell size: the board is taller
        // than it is wide, so on a short screen it's the height that binds, and a
        // width-only fit runs off the bottom of the host's weight(1f) box.
        val chrome = 78.dp   // header line + hint line + the padding around them
        val cell = minOf(maxWidth / MINE_COLS, (maxHeight - chrome) / MINE_ROWS)
            .coerceAtLeast(20.dp)

        Column(
            Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "MINOR: $flagged / $MINE_COUNT",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.primary,
            )
            Box(Modifier.padding(top = 10.dp).size(width = cell * MINE_COLS, height = cell * MINE_ROWS)) {
                for (i in 0 until MINE_CELLS) {
                    val r = i / MINE_COLS
                    val c = i % MINE_COLS
                    val open = revealed[i]
                    val isMine = board?.mine?.get(i) == true
                    Box(
                        Modifier
                            .offset(x = cell * c, y = cell * r)
                            .size(cell)
                            .background(
                                when {
                                    i == lostOn -> MaterialTheme.colorScheme.error.copy(alpha = 0.45f)
                                    open -> MaterialTheme.colorScheme.background
                                    // unopened cells alternate like a chessboard, so a big
                                    // untouched field still reads as a grid of cells
                                    (r + c) % 2 == 0 -> MaterialTheme.colorScheme.surfaceVariant
                                    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
                                },
                            )
                            .border(0.5.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                            .pointerInput(restart) {
                                detectTapGestures(
                                    onTap = { dig(i) },
                                    onLongPress = {
                                        if (lostOn == null && !won && !revealed[i]) flags[i] = !flags[i]
                                    },
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        when {
                            open && isMine -> Image(
                                painterResource(board!!.faceRes[i]),
                                board!!.faceName[i],
                                Modifier.size(cell - 5.dp).clip(CircleShape),
                                contentScale = ContentScale.Crop,
                            )
                            !open && flags[i] -> Text("🚩", fontSize = (cell.value * 0.42f).sp)
                            open && (board?.near?.get(i) ?: 0) > 0 -> Text(
                                "${board!!.near[i]}",
                                fontSize = (cell.value * 0.46f).sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Black,
                                color = mineDigitColors[board!!.near[i] - 1],
                            )
                        }
                    }
                }
            }
            Text(
                "Tryck för att gräva · håll inne för att flagga 🚩",
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )
        }

        // Bespoke end panel rather than ScorePanel: a minesweeper has no score to show, and
        // the whole joke is which relative he stepped on.
        if (lost || won) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    Modifier
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f), RoundedCornerShape(18.dp))
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(if (won) "🚩" else "💥", fontSize = 52.sp)
                    Text(
                        if (won) "MINFRITT!" else "BOM!",
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp,
                        color = if (won) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Text(
                        if (won) {
                            "Hela släkten ligger kvar under jorden. Snyggt jobbat."
                        } else {
                            "Där låg ${lostOn?.let { board?.faceName?.get(it) } ?: "släkten"}. " +
                                "Sådant händer i den här familjen."
                        },
                        fontSize = 14.sp,
                        lineHeight = 19.sp,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (won) {
                            OutlinedButton(onClick = { restart++ }) { Text("En gång till") }
                            Button(onClick = onWin) { Text("Fortsätt →", fontWeight = FontWeight.Bold) }
                        } else {
                            Button(onClick = { restart++ }) {
                                Text("Nytt fält", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------- Game 14 (chill tail): find Farmor in the crowd ----------

private class Face(val x: Float, val y: Float, val res: Int, val isTarget: Boolean)

// round 0 gets 10 s, and every round after that a second less — round 5 lands on 5 s
// against a crowd of 60 faces, which is about the edge of doable.
private fun limitTenths(round: Int) = ((10 - round) * 10).coerceAtLeast(40)

@Composable
private fun FindGame(rounds: Int, onWin: () -> Unit) {
    var restart by remember { mutableIntStateOf(0) }
    var round by remember(restart) { mutableIntStateOf(0) }
    var misses by remember(restart) { mutableIntStateOf(0) }
    var done by remember(restart) { mutableStateOf(false) }
    // tenths, not seconds: a whole-second clock ticking down from 10 barely reads as
    // pressure until the last two numbers
    var left by remember(restart, round) { mutableIntStateOf(limitTenths(round)) }

    // the clock tightens as the crowd thickens, so late rounds are hard on both axes
    LaunchedEffect(restart, round, done) {
        if (done) return@LaunchedEffect
        while (left > 0) {
            delay(100)
            left--
        }
        // running out costs the round, same as a wrong tap — nobody gets stuck on one
        misses++
        if (round + 1 >= rounds) done = true else round++
    }

    if (done) {
        // one point per round, minus wrong taps and timeouts; two slips still passes
        ScorePanel(
            (rounds - misses).coerceAtLeast(0), rounds - 2,
            onRetry = { restart++ }, onContinue = onWin,
        )
        return
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val faceDp = 40.dp
        val w = maxWidth
        val h = maxHeight
        // The crowd thickens each round and is laid out on a jittered grid: pure random
        // scatter piled faces on top of each other, so the target was either free or
        // impossible depending on the roll. Both photos of Farmor are kept out of the decoy
        // pool: at 40dp a second picture of the same person is indistinguishable from the
        // target, so the round came down to guessing which of the two the game meant. The
        // clock is what makes it hard now, not the ambiguity.
        val crowd = remember(restart, round) {
            val count = 20 + round * 8
            val cols = 5
            val rows = (count + cols) / cols
            val decoys = faces.filter { it.first != R.drawable.farmor && it.first != R.drawable.farmor1 }
            val slots = (0 until rows * cols).shuffled().take(count + 1)
            slots.mapIndexed { i, slot ->
                val gx = (slot % cols + 0.12f + Random.nextFloat() * 0.76f) / cols
                val gy = (slot / cols + 0.12f + Random.nextFloat() * 0.76f) / rows
                if (i == 0) Face(gx, gy, R.drawable.farmor, true)
                else Face(gx, gy, decoys[Random.nextInt(decoys.size)].first, false)
            }
        }

        crowd.forEach { f ->
            Image(
                painterResource(f.res),
                if (f.isTarget) "Farmor" else null,
                Modifier
                    .offset(x = (w - faceDp) * f.x, y = (h - faceDp) * f.y)
                    .size(faceDp)
                    .clip(CircleShape)
                    .clickable {
                        if (f.isTarget) {
                            if (round + 1 >= rounds) done = true else round++
                        } else {
                            misses++
                        }
                    },
                contentScale = ContentScale.Crop,
            )
        }
        Column(
            Modifier.align(Alignment.TopCenter).padding(top = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "RUNDA ${round + 1} / $rounds   ·   MISSAR: $misses   ·   %.1f s".format(left / 10f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.background, RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

// ---------- Game 15 (chill tail): stopp i toan ----------

// The toilet is drawn in profile, so the hole is no longer in the middle of the frame.
// Both the canvas and the Pappa overlay measure off these two, otherwise he pops out of
// the cistern.
private const val BOWL_CX = 0.37f
private const val BOWL_RIM_Y = 0.52f

// The cut-open inside of the bowl, in absolute px. Everything wet is drawn as plain
// shapes clipped to this path — far less arithmetic than solving the curved wall for x at
// whatever height the water happens to have reached.
private fun bowlInterior(w: Float, h: Float): Path = Path().apply {
    moveTo(w * 0.175f, h * 0.535f)
    cubicTo(w * 0.168f, h * 0.612f, w * 0.205f, h * 0.652f, w * 0.278f, h * 0.678f)
    cubicTo(w * 0.312f, h * 0.692f, w * 0.372f, h * 0.692f, w * 0.402f, h * 0.668f)
    cubicTo(w * 0.492f, h * 0.640f, w * 0.560f, h * 0.610f, w * 0.560f, h * 0.535f)
    close()
}

// A plumbing-diagram section rather than an intact toilet: the whole joke is watching the
// level climb and the colour turn, and a solid bowl hides both. `water` is passed in
// because the trap seal has to brown along with everything else — a clean blue U under a
// bowl full of murk reads as a bug.
private fun DrawScope.toilet(porcelain: Color, ink: Color, water: Color, inner: Path) {
    val w = size.width
    val h = size.height
    val edge = Stroke(width = w * 0.009f)

    drawLine(
        ink.copy(alpha = 0.35f),
        Offset(w * 0.04f, h * 0.945f), Offset(w * 0.96f, h * 0.945f),
        strokeWidth = w * 0.013f, cap = StrokeCap.Round,
    )

    // foot before pipework: in a section the trap runs *through* the pedestal, so drawing
    // the porcelain over it would put the toilet's near wall back on
    val foot = Path().apply {
        moveTo(w * 0.245f, h * 0.730f)
        lineTo(w * 0.228f, h * 0.945f)
        lineTo(w * 0.505f, h * 0.945f)
        lineTo(w * 0.462f, h * 0.730f)
        close()
    }
    drawPath(foot, porcelain)
    drawPath(foot, ink, style = edge)

    val trap = Path().apply {
        moveTo(w * 0.335f, h * 0.688f)
        cubicTo(w * 0.272f, h * 0.845f, w * 0.432f, h * 0.858f, w * 0.430f, h * 0.762f)
        cubicTo(w * 0.436f, h * 0.730f, w * 0.452f, h * 0.775f, w * 0.445f, h * 0.928f)
    }
    // only the U holds water; the downpipe past the weir is dry, which is the one detail
    // that makes the S-trap read as a trap and not as a bent tube
    val seal = Path().apply {
        moveTo(w * 0.335f, h * 0.688f)
        cubicTo(w * 0.272f, h * 0.845f, w * 0.432f, h * 0.858f, w * 0.430f, h * 0.762f)
    }
    drawPath(trap, ink, style = Stroke(width = w * 0.062f, cap = StrokeCap.Round))
    drawPath(trap, porcelain, style = Stroke(width = w * 0.048f, cap = StrokeCap.Round))
    drawPath(seal, water, style = Stroke(width = w * 0.032f, cap = StrokeCap.Round))

    val body = Path().apply {
        moveTo(w * 0.120f, h * 0.512f)
        cubicTo(w * 0.108f, h * 0.600f, w * 0.170f, h * 0.680f, w * 0.240f, h * 0.740f)
        lineTo(w * 0.470f, h * 0.740f)
        cubicTo(w * 0.545f, h * 0.680f, w * 0.605f, h * 0.620f, w * 0.605f, h * 0.512f)
        close()
    }
    drawPath(body, porcelain)
    // translucent white, never a fixed light colour: it has to sit on top of whatever
    // surfaceVariant the theme handed us, in both directions
    drawPath(
        body,
        Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.10f), Color.Transparent),
            startY = h * 0.51f, endY = h * 0.76f,
        ),
    )
    drawPath(body, ink, style = edge)
    // the far wall of the bowl, shaded — without it a low water level looks like a hole
    // straight through the bathroom floor
    drawPath(inner, ink.copy(alpha = 0.22f))

    val tankTL = Offset(w * 0.600f, h * 0.240f)
    val tankSz = Size(w * 0.295f, h * 0.285f)
    val tankR = CornerRadius(w * 0.020f, w * 0.020f)
    drawRoundRect(porcelain, tankTL, tankSz, tankR)
    drawRoundRect(
        Brush.horizontalGradient(
            listOf(Color.White.copy(alpha = 0.12f), Color.Transparent),
            startX = tankTL.x, endX = tankTL.x + tankSz.width,
        ),
        tankTL, tankSz, tankR,
    )
    drawRoundRect(ink, tankTL, tankSz, tankR, style = edge)

    val tankLidTL = Offset(w * 0.583f, h * 0.205f)
    val tankLidSz = Size(w * 0.329f, h * 0.040f)
    val tankLidR = CornerRadius(w * 0.012f, w * 0.012f)
    drawRoundRect(porcelain, tankLidTL, tankLidSz, tankLidR)
    drawRoundRect(ink, tankLidTL, tankLidSz, tankLidR, style = edge)
    drawRoundRect(
        Color(0xFF9E9E9E),
        Offset(w * 0.715f, h * 0.212f), Size(w * 0.070f, h * 0.026f),
        CornerRadius(w * 0.012f, w * 0.012f),
    )

    val seatTL = Offset(w * 0.128f, h * 0.500f)
    val seatSz = Size(w * 0.470f, h * 0.026f)
    val seatR = CornerRadius(h * 0.013f, h * 0.013f)
    drawRoundRect(porcelain, seatTL, seatSz, seatR)
    drawRoundRect(ink.copy(alpha = 0.10f), seatTL, seatSz, seatR)
    drawRoundRect(ink, seatTL, seatSz, seatR, style = edge)

    val hinge = Offset(w * 0.580f, h * 0.513f)
    drawCircle(ink.copy(alpha = 0.55f), radius = w * 0.016f, center = hinge)
    // 102°, not 90: a lid stood bolt upright reads as a mistake, the extra bit leans it
    // back onto the cistern the way a real one settles
    rotate(102f, hinge) {
        val lidTL = Offset(hinge.x - w * 0.395f, hinge.y - h * 0.030f)
        val lidSz = Size(w * 0.395f, h * 0.026f)
        drawRoundRect(porcelain, lidTL, lidSz, seatR)
        drawRoundRect(ink.copy(alpha = 0.10f), lidTL, lidSz, seatR)
        drawRoundRect(ink, lidTL, lidSz, seatR, style = edge)
    }
}

// Anchored on the cup, not the handle: the rubber is the only part that has to land
// somewhere exact — first on the water, later on Pappa's head — so both callers pass the
// point they actually care about and let the stick fall where it may. Every length here
// is a multiple of cupW, because the same helper draws it at 64 px and at 200.
private fun DrawScope.plunger(cx: Float, cupBottom: Float, cupW: Float, ink: Color) {
    val cupH = cupW * 0.78f
    val cupTop = cupBottom - cupH
    val neck = cupW * 0.17f
    val shaftW = cupW * 0.20f
    val shaftTop = cupTop - cupW * 1.5f
    val shaftTL = Offset(cx - shaftW / 2f, shaftTop)
    val shaftSz = Size(shaftW, cupTop + cupH * 0.30f - shaftTop)
    val shaftR = CornerRadius(shaftW / 2f, shaftW / 2f)

    // shaft first so the bell covers the joint, instead of a stick that visibly stops on
    // top of the rubber
    drawRoundRect(Color(0xFFB07B46), shaftTL, shaftSz, shaftR)
    drawLine(
        Color(0x33000000),
        Offset(cx - shaftW * 0.18f, shaftTop + shaftW), Offset(cx - shaftW * 0.18f, cupTop),
        strokeWidth = shaftW * 0.09f, cap = StrokeCap.Round,
    )
    drawLine(
        Color(0x22000000),
        Offset(cx + shaftW * 0.24f, shaftTop + shaftW * 1.7f), Offset(cx + shaftW * 0.24f, cupTop - shaftW),
        strokeWidth = shaftW * 0.06f, cap = StrokeCap.Round,
    )
    // right-hand third in shadow: two grain lines alone still read as a flat strip
    drawRoundRect(
        Color(0x2E000000),
        Offset(cx + shaftW * 0.10f, shaftTop), Size(shaftW * 0.40f, shaftSz.height),
        CornerRadius(shaftW * 0.20f, shaftW * 0.20f),
    )
    drawRoundRect(
        Color(0xFF6D4522),
        Offset(cx - shaftW * 0.95f, shaftTop + (cupTop - shaftTop) * 0.20f),
        Size(shaftW * 1.9f, shaftW * 0.85f),
        CornerRadius(shaftW * 0.42f, shaftW * 0.42f),
    )
    drawRoundRect(ink.copy(alpha = 0.55f), shaftTL, shaftSz, shaftR, style = Stroke(width = cupW * 0.03f))
    drawRoundRect(
        Color(0xFF8D8D8D),
        Offset(cx - neck * 1.2f, cupTop - cupW * 0.05f), Size(neck * 2.4f, cupW * 0.13f),
        CornerRadius(cupW * 0.05f, cupW * 0.05f),
    )

    val bell = Path().apply {
        moveTo(cx - cupW / 2f, cupBottom)
        cubicTo(cx - cupW * 0.53f, cupBottom - cupH * 0.45f, cx - cupW * 0.36f, cupTop + cupH * 0.05f, cx - neck, cupTop)
        lineTo(cx + neck, cupTop)
        cubicTo(cx + cupW * 0.36f, cupTop + cupH * 0.05f, cx + cupW * 0.53f, cupBottom - cupH * 0.45f, cx + cupW / 2f, cupBottom)
        // hollow underside, so only the two lip tips touch: that concave line is the whole
        // difference between a suction cup and a lump of rubber on a stick
        cubicTo(cx + cupW * 0.32f, cupBottom - cupH * 0.17f, cx - cupW * 0.32f, cupBottom - cupH * 0.17f, cx - cupW / 2f, cupBottom)
        close()
    }
    drawPath(
        bell,
        Brush.horizontalGradient(
            listOf(Color(0xFFE2503C), Color(0xFFC62828), Color(0xFF8B1A1A)),
            startX = cx - cupW / 2f, endX = cx + cupW / 2f,
        ),
    )
    val lip = Path().apply {
        moveTo(cx - cupW / 2f, cupBottom)
        cubicTo(cx - cupW * 0.32f, cupBottom - cupH * 0.17f, cx + cupW * 0.32f, cupBottom - cupH * 0.17f, cx + cupW / 2f, cupBottom)
    }
    drawPath(lip, Color(0xFF7A1414), style = Stroke(width = cupW * 0.09f, cap = StrokeCap.Round))
    drawOval(
        Color.White.copy(alpha = 0.20f),
        Offset(cx - cupW * 0.34f, cupTop + cupH * 0.16f), Size(cupW * 0.15f, cupH * 0.42f),
    )
    drawPath(bell, ink, style = Stroke(width = cupW * 0.045f))
}

@Composable
private fun PlungerGame(onWin: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val wpx = with(density) { maxWidth.toPx() }
        val hpx = with(density) { maxHeight.toPx() }

        var progress by remember { mutableFloatStateOf(0f) }
        var plungerY by remember { mutableFloatStateOf(0f) }
        var anchor by remember { mutableFloatStateOf(0f) }
        var pushed by remember { mutableStateOf(false) }
        var strokes by remember { mutableIntStateOf(0) }
        var splash by remember { mutableFloatStateOf(0f) }
        var elapsed by remember { mutableFloatStateOf(0f) }
        var popAge by remember { mutableFloatStateOf(0f) }
        var popped by remember { mutableStateOf(false) }
        var revealed by remember { mutableStateOf(false) }
        var fired by remember { mutableStateOf(false) }

        // How far the plunger travels, and how much of that a push (or a pull) has to cover
        // before it counts. 0.6 of the travel means one sweep can never be read as two
        // strokes — and anything much smaller is just a millimetre of wiggle, on repeat.
        val travel = hpx * 0.24f
        val strokeNeed = travel * 0.6f
        // Twelve strokes of gain against a drain that empties in three quarters of a minute:
        // plunge steadily and it's over in about fourteen, put the phone down and it isn't.
        val gain = 1f / 12f
        val decay = 0.022f

        val porcelain = MaterialTheme.colorScheme.surfaceVariant   // a white toilet on the
        val ink = MaterialTheme.colorScheme.onSurface              // dark theme is a lightbulb

        LaunchedEffect(Unit) {
            while (true) {
                delay(16)
                // Fixed 16 ms step instead of withFrameNanos: nothing here is physics, and a
                // dropped frame only makes the decay a hair slower — which never hurts him.
                elapsed += 0.016f
                if (popped) popAge += 0.016f else progress = (progress - decay * 0.016f).coerceAtLeast(0f)
                splash = (splash - 0.05f).coerceAtLeast(0f)
            }
        }

        // the punchline waits for the spring to settle — arriving on the same frame as the
        // pop reads as a rendering bug rather than as a joke
        LaunchedEffect(popped) { if (popped) { delay(1100); revealed = true } }

        // deliberately under-damped: the overshoot IS the gag
        val pop by animateFloatAsState(
            targetValue = if (popped) 1f else 0f,
            animationSpec = spring(dampingRatio = 0.42f, stiffness = Spring.StiffnessLow),
            label = "pop",
        )

        // null once he's out: the picture is the punchline, and a caption under it only
        // explains the joke to someone already looking at it
        val status = if (popped) null else when {
            progress < 0.12f -> "Blip…"
            progress < 0.30f -> "Något rör sig därnere…"
            progress < 0.50f -> "Det där lät inte som vatten."
            progress < 0.70f -> "Nej. Nej nej nej."
            progress < 0.88f -> "Något tittar upp."
            else -> "OJ."
        }

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(popped) {
                    if (popped) return@pointerInput
                    detectVerticalDragGestures { _, dy ->
                        plungerY = (plungerY + dy).coerceIn(0f, travel)
                        if (!pushed) {
                            // the anchor chases the highest point seen so far, so easing back
                            // mid-push re-arms the stroke instead of banking half of it
                            if (plungerY < anchor) anchor = plungerY
                            if (plungerY - anchor >= strokeNeed) {
                                pushed = true
                                anchor = plungerY
                                splash = 1f
                            }
                        } else {
                            if (plungerY > anchor) anchor = plungerY
                            if (anchor - plungerY >= strokeNeed) {
                                pushed = false
                                anchor = plungerY
                                strokes++
                                progress = (progress + gain).coerceAtMost(1f)
                                if (progress >= 1f) popped = true
                            }
                        }
                    }
                },
        ) {
            val w = size.width
            val h = size.height
            val cx = w * BOWL_CX
            val inner = bowlInterior(w, h)
            // tap-water blue drifting towards something we won't name — the same ramp feeds
            // the bowl and the trap seal so the whole system browns together
            val water = Color(
                red = 0.33f + 0.16f * progress,
                green = 0.72f - 0.40f * progress,
                blue = 0.92f - 0.74f * progress,
            )
            toilet(porcelain, ink, water, inner)

            val waterY = h * (0.632f - 0.084f * progress)
            clipPath(inner) {
                val left = w * 0.14f
                val right = w * 0.60f
                val surface = Path().apply {
                    moveTo(left, waterY)
                    var x = left
                    while (x < right) {
                        // the ripple scales with the last stroke: a dead-flat surface in a
                        // bowl someone is hammering looks like a sheet of plastic
                        lineTo(x, waterY + sin(x / w * 26f + elapsed * 3.2f) * h * 0.005f * (0.3f + splash))
                        x += w * 0.02f
                    }
                    lineTo(right, waterY)
                    lineTo(right, h)
                    lineTo(left, h)
                    close()
                }
                drawPath(surface, water)

                val bubbles = (progress * 7f).toInt()
                for (b in 0 until bubbles) {
                    val ph = (elapsed * 0.6f + b * 0.37f) % 1f
                    val bx = cx + w * 0.11f * sin(b * 2.3f + elapsed * 1.7f)
                    drawCircle(
                        Color.White.copy(alpha = 0.40f * (1f - ph)),
                        radius = w * 0.017f * (0.4f + ph),
                        center = Offset(bx, waterY + h * 0.06f * (1f - ph)),
                    )
                }

                if (splash > 0f) {
                    drawCircle(
                        Color.White.copy(alpha = 0.55f * splash),
                        radius = w * 0.10f * (1f + (1f - splash) * 0.9f),
                        center = Offset(cx, waterY),
                        style = Stroke(width = w * 0.012f),
                    )
                    for (d in 0 until 4) {
                        drawCircle(
                            water.copy(alpha = splash * 0.8f),
                            radius = w * 0.012f,
                            center = Offset(
                                cx + w * 0.13f * sin(d * 1.9f + 0.7f),
                                waterY - h * 0.07f * splash * (0.5f + d * 0.2f),
                            ),
                        )
                    }
                }
            }

            if (!popped) {
                // a hair of shake right after a stroke lands, so the push has some weight
                val shake = sin(elapsed * 40f) * splash * w * 0.006f
                plunger(cx + shake, h * 0.40f + plungerY, w * 0.20f, ink)
            }
        }

        val headSz = 112.dp
        val stackW = 160.dp
        val stackH = 220.dp
        val headPx = with(density) { headSz.toPx() }
        val stackWpx = with(density) { stackW.toPx() }
        val stackHpx = with(density) { stackH.toPx() }
        if (popped) {
            // wobble damps to nothing over ~1.8s; without the taper he shakes forever and
            // the punchline has to be read off a vibrating head
            val wobble = sin(popAge * 9f) * 11f * (1f - popAge / 1.8f).coerceAtLeast(0f)
            Box(
                Modifier
                    .offset {
                        // he starts with his head at rim level and is thrown up out of it —
                        // pop overshoots past 1f, which is exactly the point
                        val top = hpx * BOWL_RIM_Y - pop * hpx * 0.26f - (stackHpx - headPx / 2f)
                        IntOffset((wpx * BOWL_CX - stackWpx / 2f).toInt(), top.toInt())
                    }
                    .width(stackW)
                    .height(stackH)
                    .graphicsLayer { rotationZ = wobble },
            ) {
                Image(
                    painterResource(R.drawable.pappa),
                    "Pappa",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .size(headSz)
                        .clip(CircleShape)
                        .border(4.dp, Color(0xFFD7FF45), CircleShape),
                )
                // the canvas bottom deliberately overlaps the top of the circle: the cup has
                // to sit ON the bald head, not hover politely above it
                Canvas(
                    Modifier
                        .align(Alignment.TopCenter)
                        .width(100.dp)
                        .height(150.dp),
                ) {
                    plunger(size.width / 2f, size.height * 0.84f, size.width * 0.52f, ink)
                }
            }
        }

        Column(
            Modifier.align(Alignment.TopCenter).fillMaxWidth(0.8f).padding(top = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "TRYCK: ${(progress * 100).toInt()}%   ·   $strokes DRAG",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.primary,
            )
            LinearProgressIndicator(
                { progress },
                Modifier.fillMaxWidth().padding(top = 6.dp).height(10.dp),
                color = Color(0xFF8D6E63),
                trackColor = ink.copy(alpha = 0.18f),
                strokeCap = StrokeCap.Round,
            )
            status?.let {
                Text(
                    it,
                    fontSize = 13.sp,
                    fontStyle = FontStyle.Italic,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        if (!revealed) {
            Text(
                "Dra sugproppen upp och ner — hela vägen, annars räknas det inte",
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
            )
        } else {
            // onWin sits behind the button AND behind a latch: the win overlay would
            // otherwise cover the pop on the very frame it happens, and it can only fire once
            Button(
                onClick = { if (!fired) { fired = true; onWin() } },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp),
            ) {
                Text("Hjälp upp honom", fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ---------- Node 30: the finish line ----------

@Composable
private fun FinaleScreen(quest: Quest, onDone: () -> Unit) {
    val scroll = rememberScrollState()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().verticalScroll(scroll),
    ) {
        Text("🎁", fontSize = 78.sp, modifier = Modifier.padding(top = 20.dp))
        quest.lead?.let {
            Text(
                it,
                fontSize = 34.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 14.dp),
            )
        }
        Text(
            quest.tag,
            fontSize = 17.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 18.dp, start = 14.dp, end = 14.dp),
        )
        Button(onClick = onDone, modifier = Modifier.padding(top = 34.dp, bottom = 20.dp)) {
            Text("🎂", fontSize = 18.sp)
        }
    }
}
