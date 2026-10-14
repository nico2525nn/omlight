package com.omlight.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.IndeterminateCheckBox
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.omlight.cam.CamEntry
import com.omlight.cam.OpcClient
import com.omlight.cam.Thumb
import com.omlight.dl.DownloadService

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppRoot(vm: CamViewModel = viewModel()) {
    val snack = remember { SnackbarHostState() }
    var tab by remember { mutableStateOf(0) }
    var showProps by remember { mutableStateOf(false) }
    var showTransfer by remember { mutableStateOf(false) }

    // Auto-connect on cold start: zero taps when the camera AP is already
    // joined. Failures land on the gate with a retry.
    LaunchedEffect(Unit) {
        if (vm.conn == Conn.Idle) vm.connect()
    }
    // Parked downloads (stop-for-now / link death): surface the resume
    // banner as soon as the service ends with work left on disk.
    val dlFinished by DownloadService.state.collectAsState()
    LaunchedEffect(dlFinished.active, dlFinished.queued) {
        if (dlFinished.active == null && dlFinished.queued == 0) vm.checkResume()
    }
    // Notification permission (Android 13+): requested on first download
    // so progress survives the app going to the background.
    if (android.os.Build.VERSION.SDK_INT >= 33) {
        val notifPerm = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { vm.clearNotifPermFlag() }
        LaunchedEffect(vm.needNotifPerm) {
            if (vm.needNotifPerm) {
                notifPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    LaunchedEffect(vm.statusMsg) {
        vm.statusMsg?.let {
            snack.showSnackbar(it)
            vm.clearStatus()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OMLight") },
                subtitle = {
                    when (val c = vm.conn) {
                        is Conn.Ready -> Text(c.model + if (vm.linkDown) " (再接続中…)" else "")
                        is Conn.Searching -> Text("接続中…")
                        is Conn.Failed -> Text("未接続")
                        Conn.Idle -> Text("未接続")
                    }
                },
                actions = {
                    when (vm.conn) {
                        is Conn.Ready -> {
                            IconButton(onClick = {
                                showProps = true
                                vm.loadProps()
                            }) {
                                Icon(Icons.Default.Settings, "カメラ設定")
                            }
                            IconButton(onClick = vm::disconnect) {
                                Icon(Icons.Default.Wifi, "切断")
                            }
                        }
                        else -> {}
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.PhotoLibrary, null) },
                    label = { Text("写真") },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Default.PhotoCamera, null) },
                    label = { Text("撮影") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (vm.conn) {
                Conn.Idle, is Conn.Failed -> ConnectGate(vm)
                Conn.Searching -> Searching()
                is Conn.Ready -> {
                    LaunchedEffect(tab) {
                        vm.onShootVisible(tab == 1)
                    }
                    when (tab) {
                        0 -> PhotosTab(vm)
                        else -> ShootTab(vm, onSeePhotos = { tab = 0 })
                    }
                    if (vm.linkDown) LinkDownBanner(vm)
                    DetailSheet(vm)
                    if (showProps) PropsSheet(vm, onClose = { showProps = false })
                }
            }
            // Fullscreen viewer above everything (incl. sheets).
            if (vm.viewerPath != null) ViewerSheet(vm)
            DlBanner(vm, onOpenSheet = { showTransfer = true })
            if (showTransfer) TransferSheet(vm, onClose = { showTransfer = false })
        }
    }
}

@Composable
private fun ConnectGate(vm: CamViewModel) {
    val failed = (vm.conn as? Conn.Failed)?.msg
    var showAp by remember { mutableStateOf(false) }
    var ssid by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // Nearby-wifi permission is requested only when the user actually
    // tries the in-app AP join — never at app launch.
    var pendingJoin by remember { mutableStateOf(false) }
    val wifiPerm = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && pendingJoin) {
            pendingJoin = false
            vm.connectAp(ssid.trim(), pass)
        } else if (!granted) {
            pendingJoin = false
        }
    }
    // Local-network permission (Android 17+): without it the OS blocks
    // ALL LAN sockets and the camera is unreachable. Request inline as
    // part of pressing Connect — the failure mode otherwise is a
    // confusing "camera not found".
    var pendingConnect by remember { mutableStateOf(false) }
    val lnpPerm = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // Granted or not, try: denial surfaces as a clear error below.
        if (pendingConnect) {
            pendingConnect = false
            vm.connect()
        }
    }
    fun doConnect() {
        if (android.os.Build.VERSION.SDK_INT >= 37 &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                ctx, "android.permission.ACCESS_LOCAL_NETWORK"
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            pendingConnect = true
            lnpPerm.launch("android.permission.ACCESS_LOCAL_NETWORK")
        } else {
            vm.connect()
        }
    }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Default.WifiOff, null,
            Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text("カメラのWi-Fiに接続してから", style = MaterialTheme.typography.bodyLarge)
        Text("「接続」を押してください", style = MaterialTheme.typography.bodyLarge)
        if (failed != null) {
            Spacer(Modifier.height(8.dp))
            Text(failed, color = MaterialTheme.colorScheme.error)
            vm.diagText?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = ::doConnect) { Text("接続") }
        Spacer(Modifier.height(8.dp))
        // In-app AP join (Android 10+). Needs nearby-wifi/location permission,
        // requested on first use by the activity.
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            TextButton(onClick = { showAp = !showAp }) {
                Text(if (showAp) "閉じる" else "アプリからWi-Fiに接続する")
            }
            if (showAp) {
                OutlinedTextField(
                    ssid, { ssid = it },
                    label = { Text("SSID (例 OLYMPUS-XXXX)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    pass, { pass = it },
                    label = { Text("パスワード") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                // Paste a WIFI: payload scanned by the system camera.
                var qr by remember { mutableStateOf("") }
                var qrErr by remember { mutableStateOf<String?>(null) }
                var showQr by remember { mutableStateOf(false) }
                OutlinedTextField(
                    qr, {
                        qr = it
                        qrErr = null
                    },
                    label = { Text("QRの内容を貼り付け (WIFI:…)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                qrErr?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { showQr = true }) { Text("スキャン") }
                    TextButton(onClick = {
                        val cred = com.omlight.cam.WifiQr.parse(qr)
                        if (cred == null) {
                            qrErr = "WIFI:形式として読めませんでした"
                        } else {
                            ssid = cred.ssid
                            pass = cred.pass
                            qr = ""
                        }
                    }) { Text("QRを反映") }
                }
                if (showQr) {
                    QrScanTrigger(
                        start = true,
                        onScanned = { text ->
                            val cred = com.omlight.cam.WifiQr.parse(text)
                            if (cred == null) {
                                qrErr = "Wi-FiのQRではありませんでした"
                            } else {
                                ssid = cred.ssid
                                pass = cred.pass
                            }
                        },
                        onDone = { showQr = false },
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val perm = if (android.os.Build.VERSION.SDK_INT >= 33) {
                                android.Manifest.permission.NEARBY_WIFI_DEVICES
                            } else {
                                android.Manifest.permission.ACCESS_FINE_LOCATION
                            }
                            if (androidx.core.content.ContextCompat.checkSelfPermission(
                                    ctx, perm
                                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                            ) {
                                vm.connectAp(ssid.trim(), pass)
                            } else {
                                pendingJoin = true
                                wifiPerm.launch(perm)
                            }
                        },
                        enabled = ssid.isNotBlank() && pass.length >= 8,
                    ) { Text("このWi-Fiに接続") }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Text(
            "カメラの通信だけカメラWi-Fiに流し、他の通信は遮断しません",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Searching() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text("カメラを探しています…")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PhotosTab(vm: CamViewModel) {
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    val list = vm.visibleEntries
    val sections = vm.sections
    // Hoisted: one collector for all cell download marks.
    val dlState by DownloadService.state.collectAsState()

    fun toggle(path: String) {
        selected = if (path in selected) selected - path else selected + path
        if (selected.isEmpty()) selecting = false
    }

    fun selectDay(day: String, paths: List<String>) {
        selecting = true
        val unsaved = paths.filter { it !in vm.savedPaths }
        val sel = selected
        selected = when {
            // Step 1: nothing (of this day) selected -> unsaved only.
            paths.none { it in sel } && unsaved.isNotEmpty() -> sel + unsaved
            // Step 2: unsaved selected -> whole day.
            unsaved.isNotEmpty() && unsaved.all { it in sel } &&
                paths.any { it !in sel } -> sel + paths
            // Step 3: whole day (or mixed) selected -> clear day.
            else -> {
                val next = sel - paths.toSet()
                if (next.isEmpty()) selecting = false
                next
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // One-Time restriction banner.
            if (vm.oneTime) {
                var confirmClear by remember { mutableStateOf(false) }
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                ) {
                    Row(
                        Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "ワンタイム接続: シェア予約された写真のみ表示されます",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        if (confirmClear) {
                            TextButton(onClick = {
                                confirmClear = false
                                vm.clearReservations()
                            }) { Text("解除する") }
                        } else {
                            TextButton(onClick = { confirmClear = true }) { Text("予約解除") }
                        }
                    }
                }
            }
            // Selection header (Photos style): × + count + select-all.
            if (selecting) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = {
                        selecting = false
                        selected = emptySet()
                    }) { Icon(Icons.Default.Close, "選択を閉じる") }
                    Text(
                        "${selected.size}件選択中",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        selected = if (selected.size == list.size) emptySet()
                        else list.map { it.path }.toSet()
                        if (selected.isEmpty()) selecting = false
                    }) {
                        Text(if (selected.size == list.size) "解除" else "すべて選択")
                    }
                }
                HorizontalDivider()
            }
            PullToRefreshBox(vm.refreshing, onRefresh = vm::refresh, Modifier.weight(1f).fillMaxWidth()) {
            if (list.isEmpty() && !vm.refreshing) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp),
                    ) {
                        Text(
                            if (vm.filterDate != null) "この日の写真がありません"
                            else "写真がありません"
                        )
                        vm.emptyHint?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                val listState = rememberLazyListState()
                // Lookahead: when the user scrolls, prefetch thumbs just
                // beyond the viewport so cells are warm on arrival.
                // Row keys are "row-<day>-<i>"; resolve packed paths via
                // the shared pack inputs (sections + aspects).
                val lookaheadAspects = remember(sections, vm.thumbs.size) {
                    sections.associate { (day, items) ->
                        day to items.map { it.path to vm.aspectFor(it.path) }
                    }
                }
                LaunchedEffect(listState.isScrollInProgress, lookaheadAspects) {
                    if (!listState.isScrollInProgress) return@LaunchedEffect
                    val layout = listState.layoutInfo
                    val keys = layout.visibleItemsInfo.mapNotNull { it.key as? String }
                    // Visible rows -> their packed paths (approx: whole
                    // visible days' paths, cheap and good enough).
                    val days = keys.mapNotNull {
                        if (it.startsWith("row-")) it.removePrefix("row-").substringBeforeLast('-') else null
                    }.toSet()
                    val paths = days.flatMap { d -> lookaheadAspects[d].orEmpty().map { it.first } }
                    val tail = paths.takeLast(6)
                    if (tail.isNotEmpty()) vm.prefetchAhead(tail)
                    // Also warm the next section's worth past the viewport.
                    val flat = list.map { it.path }
                    val anchor = flat.indexOf(paths.lastOrNull())
                    if (anchor >= 0) {
                        vm.prefetchAhead(flat.drop(anchor + 1).take(18))
                    }
                }
                // Justified rows (Flickr-style): cells keep their aspect,
                // each row stretches to the full width. Row height floats
                // around the zoom target: landscape rows come out short
                // with few cells, portrait rows tall with more — no
                // column count. Pinch anywhere steps the zoom level.
                androidx.compose.foundation.layout.BoxWithConstraints(
                    Modifier.fillMaxSize()
                        .pointerInput(Unit) {
                            // Level-stepped pinch: accumulate span ratio,
                            // fire one level per 25% past the threshold.
                            // awaitEachGesture defers to scroll/single-finger.
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                var acc = 1f
                                do {
                                    val e = awaitPointerEvent()
                                    val fingers = e.changes.count { it.pressed }
                                    if (fingers < 2 || e.changes.isEmpty()) {
                                        e.changes.forEach { it.consume() }
                                        break
                                    }
                                    val z = e.calculateZoom()
                                    if (!z.isNaN() && z > 0f) acc *= z
                                    if (acc >= 1.25f) {
                                        acc = 1f
                                        e.changes.forEach { it.consume() }
                                        vm.stepGridZoom(+1)
                                    } else if (acc <= 1f / 1.25f) {
                                        acc = 1f
                                        e.changes.forEach { it.consume() }
                                        vm.stepGridZoom(-1)
                                    }
                                } while (e.changes.any { it.pressed })
                            }
                        }
                ) {
                    val density = androidx.compose.ui.platform.LocalDensity.current
                    val widthPx = with(density) { maxWidth.toPx() } - with(density) { 16.dp.toPx() }
                    val targetPx = with(density) {
                        CamViewModel.ZOOM_ROW_DP[vm.gridZoom.coerceIn(0, CamViewModel.ZOOM_LEVELS - 1)].dp.toPx()
                    }
                    val spacingPx = with(density) { 4.dp.toPx() }
                    val spacingDp = 4.dp
                    // Pack per section so rows never mix days. Aspect reads
                    // make this recompose as thumbs (and aspects) land.
                    val packed = remember(lookaheadAspects, widthPx, vm.gridZoom) {
                        lookaheadAspects.flatMap { (day, cells) ->
                            Justify.pack(
                                cells.map { Justify.Cell(it.first, it.second) },
                                widthPx, targetPx, spacing = spacingPx,
                            ).mapIndexed { i, row -> Triple(day, i, row) }
                        }
                    }
                    val byPath = remember(list) { list.associateBy { it.path } }
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        state = listState,
                        contentPadding = PaddingValues(8.dp),
                        verticalArrangement = Arrangement.spacedBy(spacingDp),
                    ) {
                        var lastDay: String? = null
                        for ((day, i, row) in packed) {
                            if (day != lastDay) {
                                lastDay = day
                                val items = sections.first { it.first == day }.second
                                item(key = "hdr-$day") {
                                    // Day header: label + round check toggle (Photos style).
                                    val allSel = items.all { it.path in selected }
                                    val someSel = !allSel && items.any { it.path in selected }
                                    Row(
                                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            vm.dayLabel(day),
                                            style = MaterialTheme.typography.titleSmall,
                                            modifier = Modifier.weight(1f),
                                        )
                                        IconButton(onClick = {
                                            selectDay(day, items.map { it.path })
                                        }) {
                                            SelectRing(
                                                state = when {
                                                    allSel -> 2
                                                    someSel -> 1
                                                    else -> 0
                                                },
                                                desc = "この日を選択",
                                            )
                                        }
                                    }
                                }
                            }
                            item(key = "row-$day-$i") {
                                Row(
                                    Modifier.fillMaxWidth().height(with(density) { row.height.toDp() }),
                                    horizontalArrangement = Arrangement.spacedBy(spacingDp),
                                ) {
                                    for (cell in row.cells) {
                                        val e = byPath[cell.key] ?: continue
                                        androidx.compose.runtime.LaunchedEffect(e.path) {
                                            vm.needThumb(e)
                                        }
                                        PhotoCell(
                                            entry = e,
                                            thumb = vm.thumbFor(e.path),
                                            selected = e.path in selected,
                                            selecting = selecting,
                                            saved = e.path in vm.savedPaths && e.path !in selected,
                                            dl = dlMarkFor(dlState, e.path),
                                            onTap = {
                                                if (selecting) toggle(e.path)
                                                else vm.openDetail(e.path)
                                            },
                                            onLongPress = {
                                                selecting = true
                                                selected = selected + e.path
                                            },
                                            modifier = Modifier.weight(cell.aspect, fill = true),
                                        )
                                    }
                                    // Unjustified last row: pad the tail so
                                    // cells keep their packed widths.
                                    if (!row.justified) {
                                        val usedW = row.cells.sumOf { it.aspect.toDouble() } * row.height +
                                            spacingPx * (row.cells.size - 1).coerceAtLeast(0)
                                        val tailW = widthPx - usedW.toFloat()
                                        if (tailW > 1f) {
                                            Spacer(Modifier.width(with(density) { tailW.toDp() }))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            }
        }
        // Bottom actions. Hidden while a transfer runs OR is parked:
        // the slim strip owns the bottom slot then (no FAB/banner
        // overlap). Resume/discard live in the strip + transfer sheet.
        val dlBusy = DownloadService.state.collectAsState().value.let {
            it.active != null || it.queued > 0 || it.parked
        }
        if (!dlBusy) Row(
            Modifier.align(Alignment.BottomCenter).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (selecting) {
                ExtendedFloatingActionButton(
                    onClick = {
                        vm.download(selected.toList())
                        selecting = false
                        selected = emptySet()
                    },
                    icon = { Icon(Icons.Default.CloudDownload, null) },
                    text = { Text("選択を保存 (${selected.size})") },
                )
            } else if (list.isNotEmpty()) {
                val unsaved = list.filter { it.path !in vm.savedPaths }
                ExtendedFloatingActionButton(
                    onClick = {
                        val targets = if (unsaved.isNotEmpty()) unsaved else list
                        vm.download(targets.map { it.path })
                    },
                    icon = { Icon(Icons.Default.CloudDownload, null) },
                    text = {
                        Text(
                            when {
                                vm.filterDate != null && unsaved.isNotEmpty() ->
                                    "この日の未保存を保存 (${unsaved.size})"
                                vm.filterDate != null -> "この日を保存"
                                unsaved.isNotEmpty() && unsaved.size != list.size ->
                                    "未保存を保存 (${unsaved.size})"
                                else -> "すべて保存"
                            }
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun ThumbImage(
    thumb: Thumb,
    desc: String,
    modifier: Modifier,
    scale: ContentScale = ContentScale.Crop,
) {
    val jpeg = thumb.jpeg
    var bmp: ImageBitmap? by remember(jpeg) { mutableStateOf(null) }
    // Header rotation wins: camera thumbnails carry no EXIF of their
    // own, so per-bind EXIF reads always resolve to 0 here. EXIF
    // fallback stays for direct-JPEG callers (detail previews).
    LaunchedEffect(jpeg) {
        withContext(Dispatchers.Default) {
            runCatching {
                val b0 = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                if (b0 != null) {
                    var deg = thumb.degrees
                    if (deg == 0) {
                        deg = when (androidx.exifinterface.media.ExifInterface(
                            java.io.ByteArrayInputStream(jpeg)
                        ).getAttributeInt(
                            androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                            androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL,
                        )) {
                            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                            else -> 0
                        }
                    }
                    // Physical rotation: justified cells size from the
                    // bitmap bounds, so a graphicsLayer spin would lie
                    // about the aspect (portrait drawn in a landscape
                    // box). Detail previews pass Fit and are unaffected.
                    val m = android.graphics.Matrix()
                    if (deg != 0) m.postRotate(deg.toFloat())
                    val b = if (deg != 0) {
                        android.graphics.Bitmap.createBitmap(b0, 0, 0, b0.width, b0.height, m, true)
                    } else b0
                    if (b !== b0) b0.recycle()
                    bmp = b.asImageBitmap()
                }
            }
        }
    }
    if (bmp != null) {
        androidx.compose.foundation.Image(
            bmp!!, desc,
            modifier,
            contentScale = scale,
        )
    }
}

@Composable
private fun PhotoCell(
    entry: CamEntry,
    thumb: Thumb?,
    selected: Boolean,
    selecting: Boolean,
    saved: Boolean,
    /** null = none, -1 = queued, 0..1 = active progress. */
    dl: Float?,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier
            .fillMaxHeight()
            .combinedClickable(onClick = onTap, onLongClick = onLongPress),
    ) {
        Box(Modifier.fillMaxSize()) {
            if (thumb != null) {
                ThumbImage(thumb, entry.name, Modifier.fillMaxSize())
            } else {
                Box(
                    Modifier.fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                }
            }
            // Saved veil: dim the whole cell like stock (downloaded =
            // quieter). Sits under the badges, above the image.
            if (saved && !selected) {
                Box(
                    Modifier.fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.38f))
                )
            }
            if (entry.isMovie) {
                Icon(
                    Icons.Default.Movie, "動画",
                    Modifier.align(Alignment.BottomStart).padding(4.dp),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
            // RAW badge: bottom-left pill. Movies show the film icon
            // instead (never both at once).
            if (entry.isRaw && !entry.isMovie) {
                Box(
                    Modifier.align(Alignment.BottomStart).padding(6.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "RAW",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
            // Saved badge: verified in MediaStore.
            if (saved) {
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(6.dp)
                        .size(22.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.CloudDownload, "保存済み",
                        Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
            // Selection check: hollow ring when idle, filled circle w/
            // cutout check when selected (Photos style). Ring stays visible
            // over any thumbnail via a scrim behind it.
            if (selecting) {
                Box(
                    Modifier.align(Alignment.TopStart).padding(6.dp),
                ) {
                    SelectRing(
                        state = if (selected) 2 else 0,
                        desc = "選択",
                        overImage = true,
                    )
                }
            }
            // Queued mark: hollow download icon, top-end.
            if (dl != null && dl < 0f) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp)
                        .size(22.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Download, "保存待ち",
                        Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
            // Active mark: highlight pours top-to-bottom with progress,
            // then the pulse sweep keeps moving while bytes flow.
            if (dl != null && dl >= 0f) {
                DownloadPour(
                    progress = dl,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp)
                        .size(22.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Downloading, "保存中",
                        Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
    }
}

/**
 * Per-cell download mark: null = none, 0..1 = active progress, -1 = queued.
 * Reads DlState once per grid (caller hoists to avoid N collectors).
 */
@Composable
private fun dlMarkFor(s: com.omlight.dl.DlState, path: String): Float? {
    if (s.active == path) {
        if (s.activeTotal <= 0L) return 0f
        return (s.activeBytes.toFloat() / s.activeTotal).coerceIn(0f, 1f)
    }
    if (path in s.queuedPaths) return -1f
    return null
}

/**
 * Progress pour: the filled region grows top-to-bottom with [progress],
 * and a soft highlight band sweeps down continuously while active.
 */
@Composable
private fun DownloadPour(progress: Float, modifier: Modifier = Modifier) {
    val t by rememberInfiniteTransition(label = "pour").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sweep",
    )
    val fill = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val p = progress.coerceIn(0f, 1f)
        // Done region: translucent primary wash.
        drawRect(
            color = fill,
            topLeft = androidx.compose.ui.geometry.Offset.Zero,
            size = androidx.compose.ui.geometry.Size(size.width, size.height * p),
            alpha = 0.30f,
        )
        // Sweep band over the whole cell: keeps motion visible even at
        // 0% (handshake) and 100% (verify/publish).
        val y = t * size.height * 1.2f - size.height * 0.1f
        val bandH = size.height * 0.25f
        drawRect(
            color = fill,
            topLeft = androidx.compose.ui.geometry.Offset(0f, (y - bandH).coerceAtLeast(0f)),
            size = androidx.compose.ui.geometry.Size(
                size.width,
                (y - (y - bandH).coerceAtLeast(0f)).coerceAtMost(size.height - (y - bandH).coerceAtLeast(0f)),
            ),
            alpha = 0.22f,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ShootTab(vm: CamViewModel, onSeePhotos: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Liveview frame, last shot, or placeholder.
        // Tap liveview = AF lock at that point (tap again to release).
        Card(
            Modifier.fillMaxWidth().aspectRatio(4f / 3f)
                .pointerInput(vm.liveFrame != null) {
                    detectTapGestures { off ->
                        if (vm.liveFrame != null && !vm.shooting) {
                            vm.toggleAf(off.x / size.width, off.y / size.height)
                        }
                    }
                },
        ) {
            Box(Modifier.fillMaxSize()) {
                // Liveview wins while running; else the just-taken shot.
                val jpeg = vm.liveFrame ?: vm.lastShot
                val label = if (vm.liveFrame != null) "liveview (tap to lock AF)"
                else "撮影した写真"
                if (jpeg != null) {
                    // LV frames arrive ~4fps; skip the async hop for them
                    // (small + already validated). Detail/preview use async.
                    val bmp: ImageBitmap? = remember(jpeg) {
                        runCatching {
                            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.asImageBitmap()
                        }.getOrNull()
                    }
                    if (bmp != null) {
                        androidx.compose.foundation.Image(
                            bmp, label,
                            Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    }
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(if (vm.liveRunning) "受信中…" else "ライブビュー停止中")
                    }
                }
                // AF lock marker, positioned by fraction.
                vm.afPoint?.let { (fx, fy) ->
                    androidx.compose.foundation.layout.BoxWithConstraints(
                        Modifier.fillMaxSize()
                    ) {
                        val size = 44.dp
                        val x = (maxWidth * fx - size / 2).coerceAtLeast(0.dp)
                        val y = (maxHeight * fy - size / 2).coerceAtLeast(0.dp)
                        Box(
                            modifier = Modifier
                                .padding(start = x, top = y)
                                .size(size)
                                .border(
                                    2.dp, MaterialTheme.colorScheme.primary,
                                    MaterialTheme.shapes.extraSmall,
                                ),
                        )
                    }
                }
                // Last-shot banner with a jump to Photos.
                if (vm.liveFrame == null && vm.lastShot != null) {
                    FilledTonalButton(
                        onClick = onSeePhotos,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp),
                    ) {
                        Icon(Icons.Default.PhotoLibrary, null)
                        Text("写真タブで見る")
                    }
                }
            }
        }
        vm.captureState?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledIconButton(
                onClick = vm::toggleLive,
                modifier = Modifier.size(56.dp),
            ) {
                Icon(Icons.Default.Videocam, "ライブビュー")
            }
            LargeFloatingActionButton(onClick = vm::shoot) {
                if (vm.shooting) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                } else {
                    Icon(Icons.Default.PhotoCamera, "撮影")
                }
            }
            FilledIconButton(
                onClick = vm::toggleMovie,
                modifier = Modifier.size(56.dp),
            ) {
                Icon(
                    Icons.Default.Movie, "録画",
                    tint = if (vm.recording) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
        if (vm.recording) {
            Spacer(Modifier.height(8.dp))
            Text("● 録画中", color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(12.dp))
        // Hold-to-zoom (optical). Buttons repeat start/stop on press.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZoomHoldButton("W", OpcClient.ZoomDir.WIDE, vm)
            // Quick settings: the 3 most-touched props inline.
            QuickProp(vm, "expcomp", "露出")
            ZoomHoldButton("T", OpcClient.ZoomDir.TELE, vm)
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QuickProp(vm, "isospeedvalue", "ISO")
            if (vm.selfTimerSupported) {
                FilledIconButton(
                    onClick = vm::toggleSelfTimer,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        Icons.Default.Timer, "セルフタイマー",
                        tint = if (vm.selfTimer) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            QuickProp(vm, "wbvalue", "WB")
        }
        Spacer(Modifier.height(8.dp))
        // Interval shoot: preset chips or stop while running.
        if (vm.intervalLeft > 0) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "残り ${vm.intervalLeft} 枚",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = vm::stopInterval) { Text("中止") }
            }
        } else {
            var count by remember { mutableStateOf(10) }
            var secs by remember { mutableStateOf(5) }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("連写", style = MaterialTheme.typography.bodySmall)
                FilterChip(
                    selected = false,
                    onClick = { count = if (count == 10) 30 else if (count == 30) 100 else 10 },
                    label = { Text("$count 枚") },
                )
                FilterChip(
                    selected = false,
                    onClick = { secs = if (secs == 5) 10 else if (secs == 10) 30 else 5 },
                    label = { Text("$secs 秒") },
                )
                Button(onClick = { vm.startInterval(count, secs) }) { Text("開始") }
            }
        }
    }
}

/** Inline +/- stepper for one camera prop (loads props on demand). */
@Composable
private fun QuickProp(vm: CamViewModel, name: String, label: String) {
    val prop = vm.props.firstOrNull { it.name == name }
    LaunchedEffect(Unit) {
        if (vm.props.isEmpty() && !vm.propsLoading) vm.loadProps()
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$label ", style = MaterialTheme.typography.bodySmall)
        val values = prop?.values.orEmpty()
        val idx = values.indexOf(prop?.value)
        FilledIconButton(
            onClick = {
                if (idx > 0) vm.setProp(name, values[idx - 1])
            },
            modifier = Modifier.size(40.dp),
            enabled = prop?.writable == true && idx > 0,
        ) { Text("−") }
        Text(
            prop?.value ?: "…",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        FilledIconButton(
            onClick = {
                if (idx >= 0 && idx + 1 < values.size) vm.setProp(name, values[idx + 1])
            },
            modifier = Modifier.size(40.dp),
            enabled = prop?.writable == true && idx >= 0 && idx + 1 < values.size,
        ) { Text("＋") }
    }
}

@Composable
private fun ZoomHoldButton(label: String, dir: OpcClient.ZoomDir, vm: CamViewModel) {
    FilledIconButton(
        onClick = {},
        modifier = Modifier.size(56.dp)
            .pointerInput(dir) {
                detectTapGestures(
                    onDoubleTap = { vm.zoomEnd(dir) },
                    onPress = {
                        vm.zoomHold(dir, true)
                        try {
                            awaitRelease()
                        } finally {
                            vm.zoomHold(dir, false)
                        }
                    },
                )
            },
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * Slim transfer strip: one line, always above the bottom bar and below
 * the save FAB (FAB hides while busy). Queued/downloaded counts replace
 * the old multi-row card; details live in the transfer sheet.
 */
@Composable
private fun DlBanner(vm: CamViewModel, onOpenSheet: () -> Unit) {
    val s by DownloadService.state.collectAsState()
    // Downloads finished outside refresh(): re-check MediaStore so saved
    // veils/badges appear without a manual pull-to-refresh.
    LaunchedEffect(s.done, s.failed.size) {
        if (s.done > 0 || s.failed.isNotEmpty()) vm.reconcileSaved()
    }
    val active = s.active
    // Parked (user stop / link death): the service is gone; the queue is
    // journaled, not running. busy=false so the FAB returns and the strip
    // shows the resume action instead of pause/stop.
    val parkedIdle = (s.parked || s.linkDown) && active == null
    val busy = !parkedIdle && (active != null || s.queued > 0)
    // Idle + clean: nothing to show (saved veils + notification carry it).
    // The strip owns the bottom slot only while busy, failed, parked,
    // or link-parked.
    val showable = busy || s.failed.isNotEmpty() || s.parked || (s.linkDown && s.queued > 0)
    if (!showable) return
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Box(
        Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Card(
            Modifier.fillMaxWidth().clickable(onClick = onOpenSheet),
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (busy) {
                    DownloadingPulse(Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    when {
                        s.paused -> "一時停止中"
                        active != null -> active.substringAfterLast('/')
                        s.queued > 0 && !parkedIdle -> "待機中… (${s.queued}件)"
                        s.parked -> "中断中 — 残り${s.queued}件"
                        s.linkDown -> "切断 — 残り${s.queued}件は保存済み"
                        s.failed.isNotEmpty() -> "失敗 ${s.failed.size}件"
                        else -> "完了 ${s.done}件"
                    } + (if (active != null && s.speedBps > 0) " ・ ${formatSpeed(s.speedBps)}" else ""),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                if (busy) {
                    TextButton(onClick = {
                        if (s.paused) DownloadService.resume(ctx)
                        else DownloadService.pause(ctx)
                    }) {
                        Text(if (s.paused) "再開" else "停止")
                    }
                    TextButton(onClick = { DownloadService.stopLater(ctx) }) {
                        Text("中断")
                    }
                } else if (s.failed.isNotEmpty()) {
                    TextButton(onClick = vm::retryFailed) {
                        Text("再試行")
                    }
                } else if ((s.parked || s.linkDown) && s.queued > 0) {
                    TextButton(onClick = vm::resumePending) {
                        Text(if (s.linkDown) "再接続して再開" else "続きから再開")
                    }
                }
            }
            // Whole-queue progress: done / (done + queued + active + failed).
            val total = s.queued + s.done + s.failed.size + (if (active != null) 1 else 0)
            if (busy && total > 0) {
                LinearProgressIndicator(
                    progress = { (s.done.toFloat() / total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private fun formatSpeed(bps: Long): String = when {
    bps < 1024 -> "$bps B/s"
    bps < 1024 * 1024 -> "${bps / 1024} KB/s"
    else -> "%.1f MB/s".format(bps / 1024.0 / 1024.0)
}

/**
 * Downloading mark: a highlight band sweeps top-to-bottom over the icon,
 * repeating while the transfer runs.
 */
@Composable
private fun DownloadingPulse(modifier: Modifier = Modifier) {
    val t by rememberInfiniteTransition(label = "dl").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sweep",
    )
    val base = MaterialTheme.colorScheme.onSurfaceVariant
    val hi = MaterialTheme.colorScheme.primary
    Box(modifier, contentAlignment = Alignment.Center) {
        Icon(Icons.Default.Downloading, "ダウンロード中", tint = base)
        // Sweep band: alpha peaks at the moving center.
        Canvas(Modifier.matchParentSize()) {
            val y = t * size.height
            val bandH = size.height * 0.45f
            val top = (y - bandH).coerceAtLeast(0f)
            drawRect(
                color = hi,
                topLeft = androidx.compose.ui.geometry.Offset(0f, top),
                size = androidx.compose.ui.geometry.Size(size.width, (y - top).coerceAtLeast(0f)),
                alpha = 0.35f,
            )
        }
        Icon(Icons.Default.Download, null, tint = hi.copy(alpha = 0.9f))
    }
}

/** Transfer details: counts, errors, failures, resume-later. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransferSheet(vm: CamViewModel, onClose: () -> Unit) {
    val s by DownloadService.state.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheet) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("保存の進行状況", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "完了 ${s.done}件" +
                    (if (s.queued > 0) " / 待機 ${s.queued}件" else "") +
                    (if (s.skipped > 0) " / スキップ ${s.skipped}件" else "") +
                    (if (s.failed.isNotEmpty()) " / 失敗 ${s.failed.size}件" else ""),
                style = MaterialTheme.typography.bodyMedium,
            )
            s.active?.let {
                Text(
                    "保存中: ${it.substringAfterLast('/')}" +
                        (if (s.speedBps > 0) " ・ ${formatSpeed(s.speedBps)}" else ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (s.activeTotal > 0 && s.active != null) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { (s.activeBytes.toFloat() / s.activeTotal).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            s.lastError?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (s.failed.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("失敗したファイル:", style = MaterialTheme.typography.bodySmall)
                s.failed.take(8).forEach {
                    Text(
                        "・${it.substringAfterLast('/')}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (s.failed.size > 8) {
                    Text(
                        "他 ${s.failed.size - 8}件",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val busy = s.active != null || s.queued > 0
                if (busy) {
                    Button(onClick = {
                        if (s.paused) DownloadService.resume(ctx)
                        else DownloadService.pause(ctx)
                    }) {
                        Text(if (s.paused) "再開" else "一時停止")
                    }
                    OutlinedButton(onClick = {
                        DownloadService.stopLater(ctx)
                        onClose()
                    }) {
                        Text("中断して後で")
                    }
                }
                if (s.failed.isNotEmpty()) {
                    Button(onClick = vm::retryFailed) { Text("失敗を再試行") }
                }
                if (s.linkDown && s.queued > 0) {
                    Button(onClick = {
                        vm.resumePending()
                        onClose()
                    }) { Text("再接続して再開") }
                }
            }
            // Parked queue from an older run (service not running).
            vm.pendingResume?.let { (n, bytes) ->
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "中断した保存が${n}件あります" +
                            (if (bytes > 0) " (約${bytes / 1024 / 1024}MB)" else ""),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        vm.resumePending()
                        onClose()
                    }) { Text("再開") }
                    TextButton(onClick = vm::discardPending) { Text("破棄") }
                }
            }
        }
    }
}

/** Link-down strip with a manual retry. */
@Composable
private fun LinkDownBanner(vm: CamViewModel) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Card(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
        ) {
            Row(
                Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "カメラとの通信が不安定です",
                    Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Button(onClick = vm::retry) { Text("再試行") }
            }
        }
    }
}

/** Full preview + save/delete/protect actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailSheet(vm: CamViewModel) {
    val path = vm.detailPath ?: return
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var confirmDelete by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = vm::closeDetail, sheetState = sheet) {
        Column(Modifier.padding(16.dp)) {
            Text(path.substringAfterLast('/'), style = MaterialTheme.typography.titleMedium)
            vm.detailMovieLen?.let {
                Text(
                    "動画 $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier.fillMaxWidth().aspectRatio(4f / 3f)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .clickable { vm.openViewer(path) },
                contentAlignment = Alignment.Center,
            ) {
                val jpeg = vm.detailJpeg
                if (jpeg != null) {
                    ThumbImage(Thumb(jpeg, 0), path, Modifier.fillMaxSize(), ContentScale.Fit)
                } else {
                    CircularProgressIndicator()
                }
            }
            Spacer(Modifier.height(12.dp))
            // RGB histogram under the preview.
            vm.detailHist?.let { hist ->
                if (!hist.isEmpty) {
                    HistogramGraph(hist)
                    Spacer(Modifier.height(8.dp))
                }
            }
            // Shooting + color info, collapsible.
            val info = vm.detailInfo
            if (!info.isEmpty) {
                var expanded by remember(path) { mutableStateOf(false) }
                // Headline: exposure trio always visible.
                val head = info.rows.filter {
                    it.first in setOf("絞り", "シャッター", "ISO", "露出補正")
                }.take(4).joinToString(" ・ ") { it.second }
                Row(
                    Modifier.fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .clickable { expanded = !expanded }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Info, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        head.ifEmpty { "撮影情報" },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        if (expanded) "閉じる" else "開く",
                    )
                }
                if (expanded) {
                    Column(Modifier.padding(start = 32.dp)) {
                        info.rows.forEach { (k, v) ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            ) {
                                Text(
                                    k,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(130.dp),
                                )
                                Text(v, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
            if (confirmDelete) {
                Text("カメラから削除しますか? この操作は取り消せません。")
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        confirmDelete = false
                        vm.deleteDetail()
                    }) { Text("削除する") }
                    TextButton(onClick = { confirmDelete = false }) { Text("やめる") }
                }
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    TextButton(onClick = { vm.download(listOf(path)) }) {
                        Icon(Icons.Default.CloudDownload, null)
                        Text("保存")
                    }
                    // Delete/protect exist only on some bodies (E-M5 II: no).
                    if (vm.adminSupported) {
                        TextButton(onClick = { vm.protectDetail(true) }) {
                            Icon(Icons.Default.Lock, null)
                            Text("保護")
                        }
                        TextButton(onClick = { vm.protectDetail(false) }) {
                            Icon(Icons.Default.LockOpen, null)
                            Text("解除")
                        }
                        TextButton(
                            onClick = { confirmDelete = true },
                            colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            Icon(Icons.Default.Delete, null)
                            Text("削除")
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Writable camera properties (exposure comp, WB, etc. — body dependent). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PropsSheet(vm: CamViewModel, onClose: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheet) {
        Column(Modifier.padding(16.dp)) {
            Text("カメラ設定", style = MaterialTheme.typography.titleLarge)
            vm.cardFree?.let {
                Text(
                    "カード空き: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (vm.playSlot > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "スロット: ${vm.playSlot}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = vm::toggleSlot) { Text("切替") }
                }
            }
            var confirmPower by remember { mutableStateOf<String?>(null) }
            if (confirmPower != null) {
                val isReboot = confirmPower == "reboot"
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (isReboot) "カメラを再起動しますか?" else "カメラの電源を切りますか?",
                        Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        confirmPower = null
                        if (isReboot) vm.reboot() else vm.powerOff()
                        onClose()
                    }) { Text(if (isReboot) "再起動" else "切る") }
                    TextButton(onClick = { confirmPower = null }) { Text("やめる") }
                }
            } else {
                Row {
                    TextButton(onClick = { confirmPower = "off" }) {
                        Icon(Icons.Default.PowerSettingsNew, null)
                        Text("カメラの電源を切る")
                    }
                    TextButton(onClick = { confirmPower = "reboot" }) {
                        Text("再起動")
                    }
                    TextButton(onClick = vm::clearHistory) {
                        Text("保存履歴をクリア")
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = vm::mysetBackup) {
                        Text("設定を保存")
                    }
                    TextButton(onClick = vm::mysetRestore) {
                        Text("設定を復元")
                    }
                    vm.mysetMsg?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (vm.propsLoading) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (vm.props.isEmpty()) {
                Text("設定を取得できませんでした。更新を押してください。")
                Spacer(Modifier.height(8.dp))
                Button(onClick = vm::loadProps) { Text("更新") }
            } else {
                androidx.compose.foundation.lazy.LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(vm.props.size) { i ->
                        val p = vm.props[i]
                        var open by remember(p.name) { mutableStateOf(false) }
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(p.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    if (p.writable) "変更可" else "読取専用",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (p.writable && p.values.isNotEmpty()) {
                                Box {
                                    TextButton(onClick = { open = true }) {
                                        Text(p.value.ifEmpty { "-" })
                                        Icon(Icons.Default.ArrowDropDown, null)
                                    }
                                    DropdownMenu(open, onDismissRequest = { open = false }) {
                                        p.values.forEach { v ->
                                            DropdownMenuItem(
                                                text = { Text(v) },
                                                onClick = {
                                                    open = false
                                                    vm.setProp(p.name, v)
                                                },
                                            )
                                        }
                                    }
                                }
                            } else {
                                Text(
                                    p.value.ifEmpty { "-" },
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                        if (i < vm.props.size - 1) {
                            HorizontalDivider()
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Photos-style selection ring.
 * state 0 = hollow ring only (no check), 1 = ring + dash (partial),
 * 2 = filled circle with cutout check. [overImage] adds a scrim so the
 * ring reads over any thumbnail.
 */
@Composable
private fun SelectRing(state: Int, desc: String, overImage: Boolean = false) {
    val filled = state == 2
    Box(
        Modifier
            .size(26.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(
                when {
                    filled -> MaterialTheme.colorScheme.primary
                    overImage -> MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f)
                    else -> androidx.compose.ui.graphics.Color.Transparent
                }
            )
            .border(
                2.dp,
                when {
                    filled -> MaterialTheme.colorScheme.primary
                    overImage -> MaterialTheme.colorScheme.onPrimary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                androidx.compose.foundation.shape.CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            2 -> Icon(
                Icons.Default.Check, desc,
                Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onPrimary,
            )
            1 -> Icon(
                Icons.Default.Remove, desc,
                Modifier.size(16.dp),
                tint = if (overImage) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 0: bare ring, no glyph.
        }
    }
}

/**
 * RGB + luminance histogram as line graphs. One polyline per channel
 * (no fills), white luminance on top, quarter grid, clipping readout.
 * Fixed height so the detail sheet doesn't jump while it computes.
 */
@Composable
private fun HistogramGraph(hist: Histogram.Rgb, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        androidx.compose.foundation.Canvas(
            Modifier
                .fillMaxWidth()
                .height(110.dp)
                .clip(MaterialTheme.shapes.small)
                .background(androidx.compose.ui.graphics.Color.Black)
                .padding(horizontal = 6.dp, vertical = 6.dp),
        ) {
            if (hist.isEmpty) return@Canvas
            val n = Histogram.BINS
            val bw = size.width / n
            // Log-ish scale: raw peaks bury the midtones. sqrt keeps
            // spikes visible without flattening everything else.
            fun yOf(v: Int, peak: Float): Float {
                if (v <= 0) return size.height
                return size.height - size.height * kotlin.math.sqrt(v / peak)
            }
            val peak = hist.max.toFloat()
            // Quarter grid.
            val grid = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.18f)
            for (q in 1..3) {
                val x = size.width * q / 4
                drawLine(grid, androidx.compose.ui.geometry.Offset(x, 0f),
                    androidx.compose.ui.geometry.Offset(x, size.height), 1f)
            }
            drawLine(grid, androidx.compose.ui.geometry.Offset(0f, size.height - 1),
                androidx.compose.ui.geometry.Offset(size.width, size.height - 1), 1f)
            fun line(data: IntArray, color: androidx.compose.ui.graphics.Color, width: Float) {
                val path = androidx.compose.ui.graphics.Path()
                for (i in 0 until n) {
                    val x = i * bw + bw / 2
                    val y = yOf(data[i], peak)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(
                    path, color,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = width),
                )
            }
            line(hist.b, androidx.compose.ui.graphics.Color(0xFF3B82F6), 1.5f)
            line(hist.g, androidx.compose.ui.graphics.Color(0xFF22C55E), 1.5f)
            line(hist.r, androidx.compose.ui.graphics.Color(0xFFEF4444), 1.5f)
            line(hist.y, androidx.compose.ui.graphics.Color.White.copy(alpha = 0.9f), 2f)
        }
        // Clipping readout.
        val clips = buildList {
            if (hist.blackClip > 0.005f) add("黒つぶれ %.1f%%".format(hist.blackClip * 100))
            if (hist.whiteClip > 0.005f) add("白とび %.1f%%".format(hist.whiteClip * 100))
        }
        if (clips.isNotEmpty()) {
            Text(
                clips.joinToString(" ・ "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * Fullscreen pinch-zoom viewer. Fit-by-default (never cropped),
 * pinch + double-tap to zoom up to 4x, drag to pan with correct
 * 1:1 tracking and edge clamping against the *displayed* image rect
 * (not the screen), back/X to close. Reuses the already-fetched
 * detail JPEG: zero extra requests when opened from the detail sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViewerSheet(vm: CamViewModel) {
    val path = vm.viewerPath ?: return
    val jpeg = vm.detailJpeg
    var scale by remember(path) { mutableStateOf(1f) }
    var offset by remember(path) { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var chrome by remember(path) { mutableStateOf(true) }
    // Displayed (Fit) image size in px, set once the bitmap loads.
    var imgW by remember(path) { mutableStateOf(0f) }
    var imgH by remember(path) { mutableStateOf(0f) }

    androidx.compose.foundation.layout.BoxWithConstraints(
        Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black),
    ) {
        val cw = with(androidx.compose.ui.platform.LocalDensity.current) { maxWidth.toPx() }
        val ch = with(androidx.compose.ui.platform.LocalDensity.current) { maxHeight.toPx() }

        fun clampOffset(
            s: Float,
            o: androidx.compose.ui.geometry.Offset,
        ): androidx.compose.ui.geometry.Offset {
            if (imgW <= 0f || imgH <= 0f) return androidx.compose.ui.geometry.Offset.Zero
            val maxX = ((imgW * s - cw).coerceAtLeast(0f)) / 2
            val maxY = ((imgH * s - ch).coerceAtLeast(0f)) / 2
            return androidx.compose.ui.geometry.Offset(
                o.x.coerceIn(-maxX, maxX),
                o.y.coerceIn(-maxY, maxY),
            )
        }

        Box(
            Modifier.fillMaxSize()
                .pointerInput(path) {
                    detectTapGestures(
                        onDoubleTap = { pos ->
                            if (scale > 1.2f) {
                                scale = 1f
                                offset = androidx.compose.ui.geometry.Offset.Zero
                            } else {
                                // Zoom toward the tap point: keep it anchored.
                                val c = androidx.compose.ui.geometry.Offset(
                                    pos.x - cw / 2, pos.y - ch / 2
                                )
                                val ns = 2.5f
                                offset = clampOffset(
                                    ns,
                                    c + (offset - c) * (ns / scale),
                                )
                                scale = ns
                            }
                        },
                        onTap = { chrome = !chrome },
                    )
                },
        ) {
            if (jpeg != null) {
                var bmp: ImageBitmap? by remember(jpeg) { mutableStateOf(null) }
                var bw by remember(jpeg) { mutableStateOf(0) }
                var bh by remember(jpeg) { mutableStateOf(0) }
                LaunchedEffect(jpeg) {
                    withContext(Dispatchers.Default) {
                        runCatching {
                            // Full-res decode for zoom sharpness.
                            val b = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                            if (b != null) {
                                bw = b.width
                                bh = b.height
                                bmp = b.asImageBitmap()
                            }
                        }
                    }
                }
                // Displayed Fit rect from the bitmap aspect.
                LaunchedEffect(bw, bh, cw, ch) {
                    if (bw > 0 && bh > 0) {
                        val a = bw.toFloat() / bh
                        if (cw / ch > a) {
                            imgH = ch
                            imgW = ch * a
                        } else {
                            imgW = cw
                            imgH = cw / a
                        }
                    }
                }
                if (bmp != null) {
                    androidx.compose.foundation.Image(
                        bmp!!, path.substringAfterLast('/'),
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                rotationZ = vm.viewerRotation
                                scaleX = scale
                                scaleY = scale
                                translationX = offset.x
                                translationY = offset.y
                            }
                            .pointerInput(path) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    val ns = (scale * zoom).coerceIn(1f, 4f)
                                    // 1:1 tracking: pan is screen px, translation
                                    // is post-scale parent px — add directly.
                                    offset = clampOffset(ns, offset + pan)
                                    scale = ns
                                    if (scale == 1f) offset =
                                        androidx.compose.ui.geometry.Offset.Zero
                                }
                            },
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("読込中…", color = MaterialTheme.colorScheme.onPrimary)
                }
            }
            // Minimal chrome: floating close + save. Tap toggles.
            if (chrome) {
                IconButton(
                    onClick = vm::closeViewer,
                    modifier = Modifier.align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(8.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f)),
                ) {
                    Icon(Icons.Default.Close, "閉じる", tint = MaterialTheme.colorScheme.onPrimary)
                }
                ExtendedFloatingActionButton(
                    onClick = { vm.download(listOf(path)) },
                    icon = { Icon(Icons.Default.CloudDownload, null) },
                    text = { Text("保存") },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
                )
            }
        }
    }
    BackHandler(onBack = vm::closeViewer)
}
