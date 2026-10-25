package app.echomind.ui.screens

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.GpsNotFixed
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.WbCloudy
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Dehaze
import androidx.compose.material.icons.rounded.Umbrella
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Thunderstorm
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.FitScreen
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.echomind.App
import app.echomind.brain.Geo
import app.echomind.brain.GeoFix
import app.echomind.brain.Place
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
import app.echomind.ui.components.SwitchRow
import app.echomind.ui.components.rememberResumeTick
import app.echomind.ui.theme.Sp
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

private enum class MapSpan(val label: String) { DAY("Day"), WEEK("Week"), MONTH("Month"), TRIPS("Trips") }

/** A move between two stops: how far, how long, and roughly how. */
private data class Leg(val meters: Double, val minutes: Int) {
    val kmh get() = if (minutes <= 0) 0.0 else meters / 1000 / (minutes / 60.0)
}

private data class MapData(
    val fixes: List<GeoFix>,
    val visits: List<Visit>,
    val legs: List<Leg>,
    val convs: List<Conversation>,
    val weather: Map<String, WeatherDay>,
)

/** Controls the map from Compose: zoom, fit, fly to a spot. */
private class MapCtl {
    var view: MapView? = null
    var fitPoints: List<GeoPoint> = emptyList()
    fun zoomIn() { view?.controller?.zoomIn() }
    fun zoomOut() { view?.controller?.zoomOut() }
    fun flyTo(lat: Double, lon: Double, zoom: Double = 16.5) { view?.controller?.animateTo(GeoPoint(lat, lon), zoom, 600L) }
    fun fit() {
        val m = view ?: return
        val pts = fitPoints
        if (pts.isEmpty()) return
        // Built by hand: osmdroid's fromGeoPointsSafe can wrap the box round the far side of the globe.
        val n = pts.maxOf { it.latitude }; val so = pts.minOf { it.latitude }; val e = pts.maxOf { it.longitude }; val w = pts.minOf { it.longitude }
        val mid = GeoPoint((n + so) / 2, (e + w) / 2)
        // At least ~600 m across, so close readings still show at street level.
        val padLat = maxOf((n - so) * 0.15, 0.003); val padLon = maxOf((e - w) * 0.15, 0.003)
        val box = BoundingBox(n + padLat, e + padLon, so - padLat, w - padLon)
        m.post {
            // osmdroid's centre of a zero-size box is NaN, so close points just centre at street level.
            m.controller.stopAnimation(false)
            runCatching { m.zoomToBoundingBox(box, false, 64) }.onFailure { m.controller.setZoom(15.0); m.controller.setCenter(mid) }
            if (m.zoomLevelDouble > 17.0) m.controller.setZoom(17.0)
        }
    }
}

/** Where you went and what happened there: a full-screen map with your day's timeline in a sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(day: String? = null) {
    val g = App.graph
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val s by g.settings.flow.collectAsStateWithLifecycle()
    val tick = rememberResumeTick()
    var spanName by rememberSaveable { mutableStateOf(MapSpan.DAY.name) }
    var anchorStr by rememberSaveable { mutableStateOf(day ?: LocalDate.now().toString()) }
    var tripIndex by rememberSaveable { mutableIntStateOf(0) }
    var selected by remember { mutableIntStateOf(-1) }
    var settingsOpen by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf<GeoPoint?>(null) }
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
        // The moves between stops, measured over the readings in between.
        val legs = v.zipWithNext().map { (a, b) ->
            val between = f.filter { it.at in a.end..b.start }
            val pts = listOf(GeoFix(a.lat, a.lon, a.end)) + between + GeoFix(b.lat, b.lon, b.start)
            Leg(Whereabouts.distance(pts), ((b.start - a.end) / 60_000).toInt())
        }
        val convs = g.db.conversations().inRangeOnce(from, to).filter { it.state == "done" && !it.ignored }
        val days = f.map { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() }.distinct().take(31)
        value = MapData(f, v, legs, convs, days.mapNotNull { d -> g.places.weather(d)?.let { d.toString() to it } }.toMap())
        selected = -1
    }
    val coarse = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    // Android shows "Precise / Approximate" in this dialog; precise is what makes the timeline accurate.
    val precise = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.ACCESS_FINE_LOCATION] == true) scope.launch { g.places.recordNow() }
    }
    var styleMenu by remember { mutableStateOf(false) }
    val ctl = remember { MapCtl() }
    val sheet = rememberBottomSheetScaffoldState()
    val listState = rememberLazyListState()

    BottomSheetScaffold(
        scaffoldState = sheet,
        sheetPeekHeight = 240.dp,
        sheetContent = {
            Timeline(span, data, s, listState, selected,
                onSelect = { i -> selected = i; data?.visits?.getOrNull(i)?.let { ctl.flyTo(it.lat, it.lon) } },
                onConversation = { nav.go(ConversationRoute(it)) },
                onDay = { nav.go(DayReviewRoute(it)) },
                precise = !g.travel.hasPrecise(),
                onPrecise = { precise.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) },
                onTurnOn = {
                    scope.launch { g.settings.update { it.copy(locationHistory = true) } }
                    if (!g.travel.hasPermission(background = false)) coarse.launch(Manifest.permission.ACCESS_COARSE_LOCATION) else scope.launch { g.places.recordNow() }
                    if (!g.travel.hasPermission()) nav.go(app.echomind.ui.TravelRoute)
                })
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(bottom = pad.calculateBottomPadding())) {
            OsmMap(data, s, selected, ctl, onVisit = { i -> selected = i; scope.launch { listState.animateScrollToItem((i * 2 + 1).coerceAtLeast(0)); sheet.bottomSheetState.expand() } }, onLongPress = { saving = it })

            // Top: back, range and date — on a surface so it reads over any map.
            Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(Sp.s)) {
                Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f), shadowElevation = 3.dp) {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                            IconButton(onClick = {
                                if (span == MapSpan.TRIPS) tripIndex = (tripIndex + 1).coerceAtMost((trips.size - 1).coerceAtLeast(0))
                                else anchorStr = anchor.minusDays(when (span) { MapSpan.DAY -> 1; MapSpan.WEEK -> 7; else -> 30 }).toString()
                            }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Earlier") }
                            Text(
                                when (span) {
                                    MapSpan.DAY -> Fmt.day(anchor).let { if (it == "Today" || it == "Yesterday") it else anchor.format(DateTimeFormatter.ofPattern("EEE d MMM")) }
                                    MapSpan.WEEK -> "${anchor.minusDays(6).format(DateTimeFormatter.ofPattern("d MMM"))} – ${anchor.format(DateTimeFormatter.ofPattern("d MMM"))}"
                                    MapSpan.MONTH -> "30 days to ${anchor.format(DateTimeFormatter.ofPattern("d MMM"))}"
                                    MapSpan.TRIPS -> trips.getOrNull(tripIndex)?.let { "Trip · ${Fmt.dayTime(it.from)}" } ?: "No trips yet"
                                }, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, maxLines = 1,
                            )
                            IconButton(enabled = if (span == MapSpan.TRIPS) tripIndex > 0 else anchor < LocalDate.now(), onClick = {
                                if (span == MapSpan.TRIPS) tripIndex = (tripIndex - 1).coerceAtLeast(0)
                                else anchorStr = minOf(LocalDate.now(), anchor.plusDays(when (span) { MapSpan.DAY -> 1; MapSpan.WEEK -> 7; else -> 30 })).toString()
                            }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Later") }
                            IconButton(onClick = { settingsOpen = true }) { Icon(Icons.Rounded.Settings, "Location history settings") }
                        }
                        LazyRow(contentPadding = PaddingValues(horizontal = Sp.m), horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
                            items(MapSpan.entries.filter { it != MapSpan.TRIPS || trips.isNotEmpty() }) { sp ->
                                FilterChip(span == sp, { spanName = sp.name }, label = { Text(sp.label) },
                                    colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surface))
                            }
                        }
                    }
                }
            }
            // Right: zoom, fit everything, where am I.
            Column(Modifier.align(Alignment.CenterEnd).padding(end = Sp.m), verticalArrangement = Arrangement.spacedBy(Sp.s)) {
                SmallFloatingActionButton(onClick = { ctl.zoomIn() }) { Icon(Icons.Rounded.Add, "Zoom in") }
                SmallFloatingActionButton(onClick = { ctl.zoomOut() }) { Icon(Icons.Rounded.Remove, "Zoom out") }
                SmallFloatingActionButton(onClick = { ctl.fit() }) { Icon(Icons.Rounded.FitScreen, "Show everything") }
                Box {
                    SmallFloatingActionButton(onClick = { styleMenu = true }) { Icon(Icons.Rounded.Layers, "Map style") }
                    androidx.compose.material3.DropdownMenu(styleMenu, { styleMenu = false }) {
                        listOf("auto" to "Auto (follows the app)", "light" to "Light", "dark" to "Dark", "terrain" to "Terrain").forEach { (k, l) ->
                            androidx.compose.material3.DropdownMenuItem(text = { Text(l + if (s.mapStyle == k) "  ✓" else "") }, onClick = { styleMenu = false; scope.launch { g.settings.update { it.copy(mapStyle = k) } } })
                        }
                    }
                }
                SmallFloatingActionButton(onClick = {
                    if (!g.travel.hasPermission(background = false)) coarse.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                    else scope.launch { g.places.recordNow()?.let { ctl.flyTo(it.lat, it.lon) } }
                }) { Icon(Icons.Rounded.MyLocation, "Where am I") }
            }
        }
    }
    if (settingsOpen) ModalBottomSheet(onDismissRequest = { settingsOpen = false }) { LocationSettings(s) }
    saving?.let { p -> SavePlaceDialog(p, onDone = { saving = null }) }
}

// ───────────────────────────── The map ─────────────────────────────

/** Dark map: inverted, mostly grey and a little dimmer, so roads read without loud colours. */
private val DARK_TILES = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix(floatArrayOf(
    -0.85f, 0f, 0f, 0f, 230f,
    0f, -0.85f, 0f, 0f, 230f,
    0f, 0f, -0.85f, 0f, 235f,
    0f, 0f, 0f, 1f, 0f,
)).apply { postConcat(android.graphics.ColorMatrix().apply { setSaturation(0.25f) }) })

/**
 * The osmdroid map. Overlays are rebuilt only when the data changes, and the view is
 * fitted only then — never on an ordinary redraw, so your zoom and position stay put.
 */
@Composable
private fun OsmMap(d: MapData?, s: Settings, selected: Int, ctl: MapCtl, onVisit: (Int) -> Unit, onLongPress: (GeoPoint) -> Unit) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val path = MaterialTheme.colorScheme.primary.toArgb()
    val accent = MaterialTheme.colorScheme.tertiary.toArgb()
    val density = ctx.resources.displayMetrics.density
    val map = remember {
        Configuration.getInstance().apply {
            userAgentValue = "EchoMind/1.0 (personal Android app)"
            osmdroidBasePath = ctx.cacheDir.resolve("osm")
            osmdroidTileCache = ctx.cacheDir.resolve("osm/tiles")
            tileFileSystemCacheMaxBytes = 120L * 1024 * 1024
        }
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            isTilesScaledToDpi = true
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            minZoomLevel = 3.0
            maxZoomLevel = 19.5
            controller.setZoom(12.0)
        }
    }
    DisposableEffect(lifecycle) {
        ctl.view = map
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) map.onResume() else if (e == Lifecycle.Event.ON_PAUSE) map.onPause() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); ctl.view = null; map.onDetach() }
    }
    // Tiles and style: auto follows the app theme; terrain is OpenTopoMap.
    val style = s.mapStyle
    val darkTiles = style == "dark" || (style == "auto" && dark)
    LaunchedEffect(s.mapTiles, darkTiles, style) {
        map.setTileSource(if (style == "terrain") TileSourceFactory.OpenTopo else TileSourceFactory.MAPNIK)
        map.setUseDataConnection(s.mapTiles)
        map.overlayManager.tilesOverlay.setColorFilter(if (darkTiles && style != "terrain") DARK_TILES else null)
        map.invalidate()
    }
    // Icons for the pins, drawn from the app's own Material icons.
    val homeIcon = androidx.compose.ui.graphics.vector.rememberVectorPainter(Icons.Rounded.Home)
    val workIcon = androidx.compose.ui.graphics.vector.rememberVectorPainter(Icons.Rounded.Work)
    val placeIcon = androidx.compose.ui.graphics.vector.rememberVectorPainter(Icons.Rounded.Place)
    val flagIcon = androidx.compose.ui.graphics.vector.rememberVectorPainter(Icons.Rounded.Flag)
    val chatIcon = androidx.compose.ui.graphics.vector.rememberVectorPainter(Icons.Rounded.ChatBubble)
    val positive = app.echomind.ui.theme.LocalSemantic.current.positive.toArgb()
    val here = android.graphics.Color.rgb(66, 133, 244)
    // Overlays: rebuilt when the data, your places or the selection change.
    LaunchedEffect(d, s.places, selected, dark) {
        map.overlays.clear()
        map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?) = false
            override fun longPressHelper(p: GeoPoint?): Boolean { p?.let(onLongPress); return true }
        }))
        // A scale bar, top left under the header.
        map.overlays.add(org.osmdroid.views.overlay.ScaleBarOverlay(map).apply {
            setAlignBottom(false); setScaleBarOffset((16 * density).toInt(), (200 * density).toInt())
            textPaint.color = if (darkTiles) android.graphics.Color.WHITE else android.graphics.Color.DKGRAY
            barPaint.color = if (darkTiles) android.graphics.Color.WHITE else android.graphics.Color.DKGRAY
        })
        val fixes = d?.fixes.orEmpty()
        val pts = fixes.map { GeoPoint(it.lat, it.lon) }
        // Consecutive readings at the same spot add nothing to a path (and a zero-length one breaks drawing).
        val line = pts.fold(ArrayList<GeoPoint>()) { acc, p -> if (acc.isEmpty() || acc.last().distanceToAsDouble(p) > 15) acc.add(p); acc }
        if (line.size >= 2) {
            // A soft casing under the line keeps it readable on any map.
            map.overlays.add(Polyline(map).apply {
                setPoints(line); infoWindow = null
                outlinePaint.color = android.graphics.Color.WHITE; outlinePaint.alpha = 170; outlinePaint.strokeWidth = 8 * density
                outlinePaint.strokeCap = Paint.Cap.ROUND; outlinePaint.strokeJoin = Paint.Join.ROUND; outlinePaint.isAntiAlias = true
            })
            map.overlays.add(Polyline(map).apply {
                setPoints(line); infoWindow = null
                outlinePaint.color = path; outlinePaint.strokeWidth = 4.5f * density; outlinePaint.strokeCap = Paint.Cap.ROUND; outlinePaint.strokeJoin = Paint.Join.ROUND
                outlinePaint.isAntiAlias = true
            })
            // Where the day's path starts.
            map.overlays.add(Marker(map).apply {
                position = line.first(); setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER); infoWindow = null
                icon = BitmapDrawable(ctx.resources, iconPin(flagIcon, positive, density, 26)); setOnMarkerClickListener { _, _ -> true }
            })
        }
        // Every reading as a small dot.
        if (pts.isNotEmpty()) map.overlays.add(Dots(pts, path, 2.5f * density))
        // Your saved places, with their kind's icon.
        s.places.forEach { p ->
            map.overlays.add(Marker(map).apply {
                position = GeoPoint(p.lat, p.lon); setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER); infoWindow = null
                icon = BitmapDrawable(ctx.resources, iconPin(when (p.kind) { "home" -> homeIcon; "work" -> workIcon; else -> placeIcon }, accent, density, 32))
                setOnMarkerClickListener { _, _ -> true }
            })
        }
        // Stops, numbered in the order of the timeline; a chat badge where something was said.
        d?.visits.orEmpty().forEachIndexed { i, v ->
            val talked = d!!.convs.any { it.startAt in (v.start - 10 * 60_000L)..(v.end + 10 * 60_000L) }
            map.overlays.add(Marker(map).apply {
                position = GeoPoint(v.lat, v.lon); setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER); infoWindow = null
                icon = BitmapDrawable(ctx.resources, pin("${i + 1}", if (i == selected) accent else path, density, big = i == selected).let { if (talked) withBadge(it, chatIcon, accent, density) else it })
                setOnMarkerClickListener { _, _ -> onVisit(i); true }
            })
        }
        // You are here: the latest reading (if recent), with how sure it is.
        fixes.lastOrNull()?.takeIf { System.currentTimeMillis() - it.at < 45 * 60_000L }?.let { f ->
            val centre = GeoPoint(f.lat, f.lon)
            map.overlays.add(org.osmdroid.views.overlay.Polygon(map).apply {
                points = org.osmdroid.views.overlay.Polygon.pointsAsCircle(centre, f.accuracyM.toDouble().coerceAtLeast(10.0))
                fillPaint.color = here; fillPaint.alpha = if (f.accuracyM > 500) 18 else 40
                outlinePaint.color = here; outlinePaint.alpha = 120; outlinePaint.strokeWidth = 1.5f * density
                infoWindow = null
            })
            map.overlays.add(Marker(map).apply {
                position = centre; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER); infoWindow = null
                icon = BitmapDrawable(ctx.resources, meDot(here, density)); setOnMarkerClickListener { _, _ -> true }
            })
        }
        map.invalidate()
    }
    // Fit once per data change (not when you select a stop or the screen redraws).
    LaunchedEffect(d) {
        if (d == null) return@LaunchedEffect
        val fixes = d.fixes
        ctl.fitPoints = fixes.map { GeoPoint(it.lat, it.lon) }.ifEmpty { s.places.map { GeoPoint(it.lat, it.lon) } }
        if (map.width > 0) ctl.fit() else map.addOnFirstLayoutListener { _, _, _, _, _ -> ctl.fit() }
    }
    AndroidView(factory = { map }, modifier = Modifier.fillMaxSize())
}

/** Readings as small dots: drawn straight from the projection, cheap for thousands, no bounding-box maths. */
private class Dots(private val points: List<GeoPoint>, color: Int, private val radius: Float) : org.osmdroid.views.overlay.Overlay() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; alpha = 200 }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = android.graphics.Color.WHITE; alpha = 180 }
    private val p = android.graphics.Point()
    override fun draw(c: Canvas, projection: org.osmdroid.views.Projection) {
        for (g in points) {
            projection.toPixels(g, p)
            c.drawCircle(p.x.toFloat(), p.y.toFloat(), radius + 1.5f, halo)
            c.drawCircle(p.x.toFloat(), p.y.toFloat(), radius, paint)
        }
    }
}

/** A round pin with a Material icon in it. */
private fun iconPin(icon: androidx.compose.ui.graphics.vector.VectorPainter, color: Int, density: Float, sizeDp: Int): Bitmap {
    val px = (sizeDp * density).toInt()
    val img = androidx.compose.ui.graphics.ImageBitmap(px, px)
    androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(androidx.compose.ui.unit.Density(density), androidx.compose.ui.unit.LayoutDirection.Ltr,
        androidx.compose.ui.graphics.Canvas(img), androidx.compose.ui.geometry.Size(px.toFloat(), px.toFloat())) {
        drawCircle(Color.White, radius = px / 2f)
        drawCircle(Color(color), radius = px / 2f - 2.5f * density)
        val inner = px * 0.56f
        translate((px - inner) / 2, (px - inner) / 2) {
            with(icon) { draw(androidx.compose.ui.geometry.Size(inner, inner), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color.White)) }
        }
    }
    return img.asAndroidBitmap()
}

/** A pin with a small icon badge in its corner (a chat bubble where something was said). */
private fun withBadge(pin: Bitmap, icon: androidx.compose.ui.graphics.vector.VectorPainter, color: Int, density: Float): Bitmap {
    val badge = iconPin(icon, color, density, 15)
    val out = Bitmap.createBitmap(pin.width + badge.width / 2, pin.height + badge.height / 2, Bitmap.Config.ARGB_8888)
    val c = Canvas(out)
    c.drawBitmap(pin, 0f, (badge.height / 2).toFloat(), null)
    c.drawBitmap(badge, (out.width - badge.width).toFloat(), 0f, null)
    return out
}

/** The blue "you are here" dot. */
private fun meDot(color: Int, density: Float): Bitmap {
    val px = (22 * density).toInt()
    val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp); val r = px / 2f
    c.drawCircle(r, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; alpha = 60 })
    c.drawCircle(r, r, r * 0.55f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = android.graphics.Color.WHITE })
    c.drawCircle(r, r, r * 0.42f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
    return bmp
}

/** A round pin with a number or letter. */
private fun pin(label: String, color: Int, density: Float, big: Boolean = false, ring: Boolean = false): Bitmap {
    val size = ((if (big) 34 else 26) * density).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val r = size / 2f
    c.drawCircle(r, r, r - density, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = android.graphics.Color.WHITE })
    c.drawCircle(r, r, r - 3 * density, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = if (ring) android.graphics.Color.WHITE else color; style = Paint.Style.FILL
    })
    if (ring) c.drawCircle(r, r, r - 4 * density, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = 3 * density })
    val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = if (ring) color else android.graphics.Color.WHITE; textAlign = Paint.Align.CENTER
        textSize = (if (big) 15 else 12) * density; typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    c.drawText(label, r, r - (tp.descent() + tp.ascent()) / 2, tp)
    return bmp
}

// ───────────────────────────── The timeline ─────────────────────────────

@Composable
private fun Timeline(
    span: MapSpan, d: MapData?, s: Settings, listState: androidx.compose.foundation.lazy.LazyListState, selected: Int,
    onSelect: (Int) -> Unit, onConversation: (Long) -> Unit, onDay: (String) -> Unit, precise: Boolean, onPrecise: () -> Unit, onTurnOn: () -> Unit,
) {
    val zone = ZoneId.systemDefault()
    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().fillMaxHeight(0.85f), contentPadding = PaddingValues(start = Sp.gutter, end = Sp.gutter, bottom = 32.dp)) {
        // Summary.
        item(key = "summary") {
            if (d == null) { LinearProgressIndicator(Modifier.fillMaxWidth()); return@item }
            val km = Whereabouts.distance(d.fixes) / 1000
            val hours = d.visits.sumOf { it.minutes } / 60.0
            Row(Modifier.fillMaxWidth().padding(bottom = Sp.s), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat("${d.visits.size}", "stops"); Stat(if (km < 1) "<1 km" else "${km.toInt()} km", "travelled")
                Stat(if (hours < 1) "${(hours * 60).toInt()} min" else "%.1f h".format(hours), "at places"); Stat("${d.fixes.size}", "readings")
            }
        }
        if (d == null) return@LazyColumn
        if (!s.locationHistory && d.fixes.isEmpty()) item(key = "setup") {
            EchoCard(color = MaterialTheme.colorScheme.primaryContainer) {
                Text("Remember where your days happen", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text("A rough location every 15 minutes (Wi-Fi and mobile networks, never GPS), kept on this phone. Your diary, day review and morning brief then know where things happened.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(vertical = Sp.s))
                Button(onClick = onTurnOn) { Text("Turn on location history") }
            }
        }
        // Approximate-only readings are blurred by Android to about ±2 km: say so, and offer the fix.
        if (precise && (s.locationHistory || d.fixes.isNotEmpty())) item(key = "precise") {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth().padding(bottom = Sp.s)) {
                Row(Modifier.padding(Sp.m), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.GpsNotFixed, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                    Spacer(Modifier.width(Sp.m))
                    Column(Modifier.weight(1f)) {
                        Text("Locations are approximate (about ±2 km)", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                        Text("Allow precise location for Wi-Fi-level accuracy. Still no constant GPS.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                    TextButton(onClick = onPrecise) { Text("Allow") }
                }
            }
        }
        if (d.visits.isEmpty() && d.fixes.isNotEmpty()) item(key = "few") {
            Text("${d.fixes.size} reading${if (d.fixes.size == 1) "" else "s"} so far, the latest at ${Fmt.time(d.fixes.last().at)}. A stop appears once you stay somewhere 15 minutes or more.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = Sp.s))
        }
        if (d.visits.isEmpty() && d.fixes.isEmpty() && s.locationHistory) item(key = "none") {
            Text("No readings in this range yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = Sp.s))
        }
        // Where the time went (longer ranges).
        if (span != MapSpan.DAY && d.visits.isNotEmpty()) item(key = "time") {
            val byPlace = d.visits.groupBy { it.name }.map { (n, vs) -> Triple(n, vs.sumOf { it.minutes }, vs.size) }.sortedByDescending { it.second }.take(6)
            val max = byPlace.maxOf { it.second }.coerceAtLeast(1)
            Text("Where your time went", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = Sp.s, bottom = 4.dp))
            byPlace.forEach { (n, m, times) ->
                Column(Modifier.padding(vertical = 3.dp)) {
                    Row { Text(n, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis); Text("${m / 60} h ${m % 60} min · $times×", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Box(Modifier.fillMaxWidth(m.toFloat() / max).height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.primary))
                }
            }
        }
        // The timeline: stop, leg, stop, leg…
        var lastDay: LocalDate? = null
        d.visits.forEachIndexed { i, v ->
            val vd = Instant.ofEpochMilli(v.start).atZone(zone).toLocalDate()
            if (vd != lastDay) {
                lastDay = vd
                item(key = "day$vd") {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Sp.m)) {
                        Column(Modifier.weight(1f)) {
                            Text(Fmt.day(vd).let { if (it == "Today" || it == "Yesterday") it else vd.format(DateTimeFormatter.ofPattern("EEEE d MMMM")) }, style = MaterialTheme.typography.titleSmall)
                            d.weather[vd.toString()]?.let { w -> Whereabouts.weatherLine(w)?.let { WeatherLine(it, w.hours.groupingBy { h -> h.code }.eachCount().maxByOrNull { e -> e.value }?.key ?: 0) } }
                        }
                        TextButton(onClick = { onDay(vd.toString()) }) { Text("Day review") }
                    }
                }
            }
            item(key = "stop$i") { Stop(i, v, d, selected == i, onSelect, onConversation) }
            d.legs.getOrNull(i)?.takeIf { i < d.visits.lastIndex && Instant.ofEpochMilli(d.visits[i + 1].start).atZone(zone).toLocalDate() == vd }?.let { leg -> item(key = "leg$i") { LegRow(leg) } }
        }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One stop: its number (matching the pin), where, when, how long, and what happened there. */
@Composable
private fun Stop(i: Int, v: Visit, d: MapData, isSelected: Boolean, onSelect: (Int) -> Unit, onConversation: (Long) -> Unit) {
    val here = d.convs.filter { it.startAt in (v.start - 10 * 60_000L)..(v.end + 10 * 60_000L) }
    val weather = d.weather[Instant.ofEpochMilli(v.start).atZone(ZoneId.systemDefault()).toLocalDate().toString()]
        ?.let { w -> Whereabouts.weatherLine(w, Instant.ofEpochMilli(v.start).atZone(ZoneId.systemDefault()).hour, Instant.ofEpochMilli(v.end).atZone(ZoneId.systemDefault()).hour) }
    val weatherCode = d.weather[Instant.ofEpochMilli(v.start).atZone(ZoneId.systemDefault()).toLocalDate().toString()]?.hours
        ?.firstOrNull { it.hour == Instant.ofEpochMilli(v.start).atZone(ZoneId.systemDefault()).hour }?.code ?: 0
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent).clickable { onSelect(i) }.padding(vertical = Sp.s, horizontal = 4.dp)) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(if (isSelected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
            Text("${i + 1}", style = MaterialTheme.typography.labelLarge, color = Color.White, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(Sp.m))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                v.place?.let { p -> Icon(when (p.kind) { "home" -> Icons.Rounded.Home; "work" -> Icons.Rounded.Work; else -> Icons.Rounded.Place }, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)) }
                Text(v.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text("${Fmt.time(v.start)} – ${Fmt.time(v.end)} · ${if (v.minutes >= 60) "${v.minutes / 60} h ${v.minutes % 60} min" else "${v.minutes} min"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            weather?.let { WeatherLine(it, weatherCode) }
            here.forEach { c ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onConversation(c.id) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.ChatBubbleOutline, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("${Fmt.time(c.startAt)} · ${c.title.orEmpty()}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Weather with an icon that matches it. */
@Composable
private fun WeatherLine(text: String, code: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
        Icon(weatherIcon(code), null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** WMO weather code → icon. */
fun weatherIcon(code: Int): androidx.compose.ui.graphics.vector.ImageVector = when (code) {
    0, 1 -> Icons.Rounded.WbSunny
    2 -> Icons.Rounded.WbCloudy
    3 -> Icons.Rounded.Cloud
    45, 48 -> Icons.Rounded.Dehaze
    in 51..67, in 80..82 -> Icons.Rounded.Umbrella
    in 71..77, 85, 86 -> Icons.Rounded.AcUnit
    in 95..99 -> Icons.Rounded.Thunderstorm
    else -> Icons.Rounded.Cloud
}

/** The move between two stops, on the timeline's rail. */
@Composable
private fun LegRow(l: Leg) {
    Row(Modifier.padding(start = 17.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(2.dp).height(34.dp).background(MaterialTheme.colorScheme.outlineVariant))
        Spacer(Modifier.width(Sp.l))
        Icon(if (l.kmh in 0.5..7.0) Icons.Rounded.DirectionsWalk else Icons.Rounded.DirectionsCar, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text("${Geo.km(l.meters)} · ${if (l.minutes >= 60) "${l.minutes / 60} h ${l.minutes % 60} min" else "${l.minutes} min"}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
    }
}

// ───────────────────────────── Dialogs ─────────────────────────────

/** Long-press on the map: save that spot as one of your places. */
@Composable
private fun SavePlaceDialog(p: GeoPoint, onDone: () -> Unit) {
    val g = App.graph
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("other") }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Save this place") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Sp.s)) {
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }, placeholder = { Text("Gym, Parents, Office…") }, modifier = Modifier.fillMaxWidth())
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("home" to "Home", "work" to "Work", "other" to "Other").forEachIndexed { i, (k, l) ->
                        SegmentedButton(kind == k, { kind = k }, SegmentedButtonDefaults.itemShape(i, 3)) { Text(l) }
                    }
                }
                Text("Stops here will be called “${name.ifBlank { "…" }}”, and being here never counts as travelling.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                scope.launch { g.settings.update { it.copy(places = it.places + Place(UUID.randomUUID().toString(), name.trim(), kind, p.latitude, p.longitude, 400)) } }
                onDone()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
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
        Text("Tip: long-press the map to save a spot as one of your places.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Sp.l, vertical = Sp.s))
        TextButton(onClick = { scope.launch { g.places.forgetAll() } }, modifier = Modifier.padding(horizontal = Sp.s)) { Text("Delete all location history", color = MaterialTheme.colorScheme.error) }
    }
}
