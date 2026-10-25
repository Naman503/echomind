package app.echomind.ui.screens

import android.Manifest
import android.graphics.drawable.GradientDrawable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.echomind.App
import app.echomind.brain.GeoFix
import app.echomind.brain.Visit
import app.echomind.brain.WeatherDay
import app.echomind.brain.Whereabouts
import app.echomind.core.Settings
import app.echomind.data.Conversation
import app.echomind.ui.ConversationRoute
import app.echomind.ui.DayReviewRoute
import app.echomind.ui.Fmt
import app.echomind.ui.LocalNav
import app.echomind.ui.components.EchoCard
import app.echomind.ui.components.EmptyState
import app.echomind.ui.components.ScreenTopBar
import app.echomind.ui.components.SectionHeader
import app.echomind.ui.components.SwitchRow
import app.echomind.ui.components.rememberResumeTick
import app.echomind.ui.theme.Sp
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class MapSpan(val label: String) { DAY("Day"), WEEK("Week"), MONTH("Month"), TRIPS("Trips") }

private data class MapData(val fixes: List<GeoFix>, val visits: List<Visit>, val convs: List<Conversation>, val weather: Map<String, WeatherDay>)

/** Where you went and what happened there, on an OpenStreetMap map. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(day: String? = null) {
    val g = App.graph
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by g.settings.flow.collectAsStateWithLifecycle()
    val tick = rememberResumeTick()
    var spanName by rememberSaveable { mutableStateOf(MapSpan.DAY.name) }
    var anchorStr by rememberSaveable { mutableStateOf(day ?: LocalDate.now().toString()) }
    var tripIndex by rememberSaveable { mutableStateOf(0) }
    var selected by remember { mutableStateOf<Visit?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }
    val span = MapSpan.valueOf(spanName)
    val anchor = LocalDate.parse(anchorStr)
    val zone = ZoneId.systemDefault()
    val trips = remember(tick) { g.travel.spans().reversed() }
    val (from, to) = when (span) {
        MapSpan.DAY -> anchor.atStartOfDay(zone).toInstant().toEpochMilli() to anchor.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        MapSpan.WEEK -> anchor.minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli() to anchor.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        MapSpan.MONTH -> anchor.minusDays(29).atStartOfDay(zone).toInstant().toEpochMilli() to anchor.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        MapSpan.TRIPS -> trips.getOrNull(tripIndex)?.let { it.from to (if (it.to == 0L) System.currentTimeMillis() else it.to) } ?: (0L to 0L)
    }
    val count by remember { g.db.locations().observeCount() }.collectAsStateWithLifecycle(0)
    val data by produceState<MapData?>(null, from, to, count, s.placesOnline, s.places) {
        val f = g.places.fixes(from, to)
        val v = g.places.visits(f)
        val convs = g.db.conversations().inRangeOnce(from, to).filter { it.state == "done" && !it.ignored }
        val days = f.map { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() }.distinct().take(31)
        value = MapData(f, v, convs, days.mapNotNull { d -> g.places.weather(d)?.let { d.toString() to it } }.toMap())
    }
    val coarse = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        ScreenTopBar("Places & map", onBack = { nav.back() }) {
            IconButton(onClick = { scope.launch { g.places.recordNow() } }) { Icon(Icons.Rounded.MyLocation, "Note where I am now") }
            IconButton(onClick = { settingsOpen = true }) { Icon(Icons.Rounded.Settings, "Location history settings") }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = Sp.gutter), horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
            items(MapSpan.entries.filter { it != MapSpan.TRIPS || trips.isNotEmpty() }) { sp -> FilterChip(span == sp, { spanName = sp.name; selected = null }, label = { Text(sp.label) }) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = Sp.s), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (span == MapSpan.TRIPS) tripIndex = (tripIndex + 1).coerceAtMost(trips.lastIndex) else anchorStr = anchor.minusDays(if (span == MapSpan.DAY) 1 else if (span == MapSpan.WEEK) 7 else 30).toString(); selected = null }) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Earlier")
            }
            Text(
                when (span) {
                    MapSpan.DAY -> Fmt.day(anchor).let { if (it == "Today" || it == "Yesterday") it else anchor.format(DateTimeFormatter.ofPattern("EEE d MMM")) }
                    MapSpan.WEEK -> "${anchor.minusDays(6).format(DateTimeFormatter.ofPattern("d MMM"))} – ${anchor.format(DateTimeFormatter.ofPattern("d MMM"))}"
                    MapSpan.MONTH -> "Last 30 days to ${anchor.format(DateTimeFormatter.ofPattern("d MMM"))}"
                    MapSpan.TRIPS -> trips.getOrNull(tripIndex)?.let { "Trip · ${Fmt.dayTime(it.from)}" } ?: "No trips yet"
                }, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            IconButton(enabled = if (span == MapSpan.TRIPS) tripIndex > 0 else anchor < LocalDate.now(), onClick = {
                if (span == MapSpan.TRIPS) tripIndex = (tripIndex - 1).coerceAtLeast(0) else anchorStr = minOf(LocalDate.now(), anchor.plusDays(if (span == MapSpan.DAY) 1 else if (span == MapSpan.WEEK) 7 else 30)).toString(); selected = null
            }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Later") }
        }
        val d = data
        val dark = isSystemInDarkTheme()
        val primary = MaterialTheme.colorScheme.primary.toArgb(); val tertiary = MaterialTheme.colorScheme.tertiary.toArgb()
        LazyColumn(contentPadding = PaddingValues(start = Sp.gutter, end = Sp.gutter, bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(Sp.s)) {
            if (!s.locationHistory && count == 0) item {
                EchoCard(color = MaterialTheme.colorScheme.primaryContainer) {
                    Text("Remember where your days happen", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("EchoMind notes your approximate location every 15 minutes (Wi-Fi and mobile networks, never GPS) and keeps it on this phone. " +
                        "Your diary, day review and morning brief then know where things happened — and, if you allow it, the weather.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(vertical = Sp.s))
                    Button(onClick = {
                        scope.launch { g.settings.update { it.copy(locationHistory = true) } }
                        if (!g.travel.hasPermission(background = false)) coarse.launch(Manifest.permission.ACCESS_COARSE_LOCATION) else scope.launch { g.places.recordNow() }
                        if (!g.travel.hasPermission()) nav.go(app.echomind.ui.TravelRoute)
                    }) { Text("Turn on location history") }
                    Text("Background checks need location “Allow all the time” (approximate is enough) — set it on the Travel mode page.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(top = Sp.s))
                }
            }
            // The map.
            item {
                Surface(shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth().height(340.dp)) {
                    OsmMap(d, s, dark, primary, tertiary) { v -> selected = v }
                }
                if (s.mapTiles) Text("Map © OpenStreetMap contributors · map pictures show the area you look at; your path stays on the phone",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 4.dp))
            }
            if (d == null) item { Text("Loading…", style = MaterialTheme.typography.bodySmall) }
            else {
                val km = Whereabouts.distance(d.fixes) / 1000
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
                        app.echomind.ui.components.Tile("places", "${d.visits.size}", Modifier.weight(1f))
                        app.echomind.ui.components.Tile("travelled", if (km < 1) "<1 km" else "${km.toInt()} km", Modifier.weight(1f))
                        app.echomind.ui.components.Tile("readings", "${d.fixes.size}", Modifier.weight(1f))
                    }
                }
                selected?.let { v -> item(key = "sel") { VisitCard(v, d.convs, highlight = true) { nav.go(ConversationRoute(it)) } } }
                if (d.visits.isEmpty()) item {
                    EmptyState("No places here yet", if (d.fixes.isEmpty()) "No location readings in this range." else "Places appear once you stay somewhere for 15 minutes or more.", icon = Icons.Rounded.Map)
                }
                d.visits.groupBy { Instant.ofEpochMilli(it.start).atZone(zone).toLocalDate() }.toSortedMap(compareByDescending { it }).forEach { (day, vs) ->
                    item(key = "d$day") {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Sp.m)) {
                            Column(Modifier.weight(1f)) {
                                Text(Fmt.day(day).let { if (it == "Today" || it == "Yesterday") it else day.format(DateTimeFormatter.ofPattern("EEEE d MMMM")) }, style = MaterialTheme.typography.titleSmall)
                                d.weather[day.toString()]?.let { w -> Whereabouts.weatherLine(w)?.let { Text("☁ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                            }
                            TextButton(onClick = { nav.go(DayReviewRoute(day.toString())) }) { Text("Day review") }
                        }
                    }
                    items(vs.sortedBy { it.start }, key = { "v${it.start}" }) { v -> VisitCard(v, d.convs, highlight = v == selected) { nav.go(ConversationRoute(it)) } }
                }
            }
        }
    }
    if (settingsOpen) ModalBottomSheet(onDismissRequest = { settingsOpen = false }) { LocationSettings(s) }
}

@Composable
private fun VisitCard(v: Visit, convs: List<Conversation>, highlight: Boolean, open: (Long) -> Unit) {
    val here = convs.filter { it.startAt in (v.start - 10 * 60_000L)..(v.end + 10 * 60_000L) }
    EchoCard(color = if (highlight) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Place, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(Sp.m))
            Column(Modifier.weight(1f)) {
                Text(v.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${Fmt.time(v.start)} – ${Fmt.time(v.end)} · ${if (v.minutes >= 60) "${v.minutes / 60} h ${v.minutes % 60} min" else "${v.minutes} min"}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (here.isNotEmpty()) Column(Modifier.padding(top = Sp.s)) {
            Text("What happened here", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            here.forEach { c ->
                Text("${Fmt.time(c.startAt)} · ${c.title.orEmpty()}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).then(Modifier.clickableNoRipple { open(c.id) }))
            }
        }
    }
}

private fun Modifier.clickableNoRipple(onClick: () -> Unit) = this.clickable(onClick = onClick)

/** Dark map: inverted, mostly grey and a little dimmer, so roads read without loud colours. */
private val DARK_TILES = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix(floatArrayOf(
    -0.85f, 0f, 0f, 0f, 230f,
    0f, -0.85f, 0f, 0f, 230f,
    0f, 0f, -0.85f, 0f, 235f,
    0f, 0f, 0f, 1f, 0f,
)).apply { postConcat(android.graphics.ColorMatrix().apply { setSaturation(0.25f) }) })

/** The map itself: an osmdroid view with the path, visits (sized by time spent) and your saved places. */
@Composable
private fun OsmMap(d: MapData?, s: Settings, dark: Boolean, pathColor: Int, placeColor: Int, onVisit: (Visit) -> Unit) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val map = remember {
        Configuration.getInstance().apply {
            userAgentValue = "EchoMind/1.0 (personal Android app)"
            osmdroidBasePath = ctx.cacheDir.resolve("osm")
            osmdroidTileCache = ctx.cacheDir.resolve("osm/tiles")
        }
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            minZoomLevel = 3.0
            controller.setZoom(12.0)
        }
    }
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) map.onResume() else if (e == Lifecycle.Event.ON_PAUSE) map.onPause() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); map.onDetach() }
    }
    AndroidView(factory = { map }, modifier = Modifier.fillMaxSize(), update = { m ->
        m.setUseDataConnection(s.mapTiles)
        m.overlayManager.tilesOverlay.setColorFilter(if (dark) DARK_TILES else null)
        m.overlays.clear()
        val pts = d?.fixes.orEmpty().map { GeoPoint(it.lat, it.lon) }
        // Consecutive readings at the same spot add nothing to a path (and a zero-length one breaks drawing).
        val path = pts.fold(ArrayList<GeoPoint>()) { acc, p -> if (acc.isEmpty() || acc.last().distanceToAsDouble(p) > 15) acc.add(p); acc }
        if (path.size >= 2) m.overlays.add(Polyline(m).apply { setPoints(path); outlinePaint.color = pathColor; outlinePaint.strokeWidth = 8f })
        fun dot(color: Int, sizeDp: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color); setStroke(4, android.graphics.Color.WHITE); setSize((sizeDp * ctx.resources.displayMetrics.density).toInt(), (sizeDp * ctx.resources.displayMetrics.density).toInt()) }
        s.places.forEach { p ->
            m.overlays.add(Marker(m).apply {
                position = GeoPoint(p.lat, p.lon); title = p.name; icon = dot(placeColor, 18); setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            })
        }
        d?.visits.orEmpty().forEach { v ->
            m.overlays.add(Marker(m).apply {
                position = GeoPoint(v.lat, v.lon); title = v.name; snippet = "${Fmt.time(v.start)}–${Fmt.time(v.end)}"
                icon = dot(pathColor, (14 + (v.minutes / 30)).coerceAtMost(30)); setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                setOnMarkerClickListener { mk, _ -> onVisit(v); mk.showInfoWindow(); true }
            })
        }
        val all = path + s.places.map { GeoPoint(it.lat, it.lon) }.takeIf { path.isEmpty() }.orEmpty()
        // Fit everything on screen; readings all within a few hundred metres just centre at street level.
        fun fit() {
            if (all.isEmpty()) return
            val box = BoundingBox.fromGeoPointsSafe(all)
            // osmdroid's centre of a zero-size box is NaN (and breaks the whole map), so take the average.
            val mid = GeoPoint(all.map { it.latitude }.average(), all.map { it.longitude }.average())
            // Applied on the next frame: osmdroid ignores a centre set in the middle of a layout pass.
            m.post {
                if (all.size == 1 || (box.latitudeSpan < 0.004 && box.longitudeSpanWithDateLine < 0.004)) { m.controller.setZoom(16.0); m.setExpectedCenter(mid) }
                else runCatching { m.zoomToBoundingBox(box.increaseByScale(1.3f), false, 48) }.onFailure { m.controller.setZoom(13.0); m.setExpectedCenter(mid) }
            }
        }
        if (m.width > 0) fit() else m.addOnFirstLayoutListener { _, _, _, _, _ -> fit() }
        m.invalidate()
    })
}

/** Location history, online extras and the map, in one sheet. */
@Composable
private fun LocationSettings(s: Settings) {
    val g = App.graph
    val scope = rememberCoroutineScope()
    fun set(change: (Settings) -> Settings) { scope.launch { g.settings.update(change) } }
    Column(Modifier.padding(bottom = Sp.xl)) {
        Text("Location history", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = Sp.xl, vertical = Sp.s))
        SwitchRow("Note where I am", "Approximate, from Wi-Fi and mobile networks — never GPS. Kept on this phone.", s.locationHistory) { v -> set { it.copy(locationHistory = v) } }
        Text("How often", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = Sp.l, vertical = 4.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = Sp.l)) {
            listOf(15 to "15 min", 30 to "30 min", 60 to "1 hour").forEachIndexed { i, (k, l) ->
                SegmentedButton(s.locationEveryMin == k, { set { it.copy(locationEveryMin = k) } }, SegmentedButtonDefaults.itemShape(i, 3)) { Text(l) }
            }
        }
        Text("Keep for", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = Sp.l, vertical = 4.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = Sp.l)) {
            listOf(30 to "30 days", 365 to "1 year", 3650 to "Always").forEachIndexed { i, (k, l) ->
                SegmentedButton(s.locationKeepDays == k, { set { it.copy(locationKeepDays = k) } }, SegmentedButtonDefaults.itemShape(i, 3)) { Text(l) }
            }
        }
        SwitchRow("Weather and place names", "Sends a rough location (about 1 km) to Open-Meteo and OpenStreetMap; shown in the data ledger", s.placesOnline) { v -> set { it.copy(placesOnline = v) } }
        SwitchRow("Map pictures", "Download map tiles from OpenStreetMap (they show the area you look at, not your path)", s.mapTiles) { v -> set { it.copy(mapTiles = v) } }
        TextButton(onClick = { scope.launch { g.places.forgetAll() } }, modifier = Modifier.padding(horizontal = Sp.s)) { Text("Delete all location history", color = MaterialTheme.colorScheme.error) }
    }
}
