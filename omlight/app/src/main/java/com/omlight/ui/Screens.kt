package com.omlight.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.omlight.cam.CamEntry
import com.omlight.cam.OpcClient
import com.omlight.dl.DownloadService

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppRoot(vm: CamViewModel = viewModel()) {
    val snack = remember { SnackbarHostState() }
    var tab by remember { mutableStateOf(0) }
    var showProps by remember { mutableStateOf(false) }

    // Auto-connect on cold start: zero taps when the camera AP is already
    // joined. Failures land on the gate with a retry.
    LaunchedEffect(Unit) {
        if (vm.conn == Conn.Idle) vm.connect()
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
                    when (tab) {
                        0 -> PhotosTab(vm)
                        else -> ShootTab(vm)
                    }
                    if (vm.linkDown) LinkDownBanner(vm)
                    DetailSheet(vm)
                    if (showProps) PropsSheet(vm, onClose = { showProps = false })
                }
            }
            DlBanner()
        }
    }
}

@Composable
private fun ConnectGate(vm: CamViewModel) {
    val failed = (vm.conn as? Conn.Failed)?.msg
    var showAp by remember { mutableStateOf(false) }
    var ssid by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
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
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = vm::connect) { Text("接続") }
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
                    Button(
                        onClick = { vm.connectAp(ssid.trim(), pass) },
                        enabled = ssid.isNotBlank() && pass.length >= 8,
                    ) { Text("このWi-Fiに接続") }
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

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // Date filter chips (horizontal, newest-first).
            if (vm.shootDays.size > 1) {
                androidx.compose.foundation.lazy.LazyRow(
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        FilterChip(
                            selected = vm.filterDate == null,
                            onClick = { vm.selectFilterDate(null) },
                            label = { Text("すべて") },
                        )
                    }
                    items(vm.shootDays.size) { i ->
                        val day = vm.shootDays[i]
                        FilterChip(
                            selected = vm.filterDate == day,
                            onClick = {
                                vm.selectFilterDate(if (vm.filterDate == day) null else day)
                            },
                            label = { Text(day.substring(5)) },
                        )
                    }
                }
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
                LazyVerticalGrid(
                    GridCells.Adaptive(120.dp),
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(list, key = { it.path }) { e ->
                        androidx.compose.runtime.LaunchedEffect(e.path) {
                            vm.needThumb(e)
                        }
                        PhotoCell(
                            entry = e,
                            jpeg = vm.thumbFor(e.path),
                            selected = e.path in selected,
                            selecting = selecting,
                            onTap = {
                                if (selecting) {
                                    selected = if (e.path in selected) selected - e.path
                                    else selected + e.path
                                } else {
                                    vm.openDetail(e.path)
                                }
                            },
                            onLongPress = {
                                selecting = true
                                selected = selected + e.path
                            },
                        )
                    }
                }
            }
            }
        }
        // Action row: refresh + download-all when idle; selection actions when selecting.
        Row(
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
                FloatingActionButton(onClick = { selecting = false; selected = emptySet() }) {
                    Text("×")
                }
            } else if (list.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { vm.download(list.map { it.path }) },
                    icon = { Icon(Icons.Default.CloudDownload, null) },
                    text = { Text(if (vm.filterDate != null) "この日を保存" else "すべて保存") },
                )
            }
        }
    }
}

@Composable
private fun PhotoCell(
    entry: CamEntry,
    jpeg: ByteArray?,
    selected: Boolean,
    selecting: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    Card(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .combinedClickable(onClick = onTap, onLongClick = onLongPress),
    ) {
        Box(Modifier.fillMaxSize()) {
            if (jpeg != null) {
                val bmp: ImageBitmap? = remember(jpeg) {
                    runCatching {
                        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.asImageBitmap()
                    }.getOrNull()
                }
                if (bmp != null) {
                    androidx.compose.foundation.Image(
                        bmp, entry.name,
                        Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            } else {
                Box(
                    Modifier.fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                }
            }
            if (entry.isMovie) {
                Icon(
                    Icons.Default.Movie, null,
                    Modifier.align(Alignment.BottomStart).padding(4.dp),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
            if (selecting) {
                Icon(
                    Icons.Default.CheckCircle, null,
                    Modifier.align(Alignment.TopEnd).padding(4.dp),
                    tint = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ShootTab(vm: CamViewModel) {
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Liveview frame or placeholder. Tap = focus-and-shoot at that point.
        Card(
            Modifier.fillMaxWidth().aspectRatio(4f / 3f)
                .pointerInput(vm.liveFrame != null) {
                    detectTapGestures { off ->
                        if (vm.liveFrame != null && !vm.shooting) {
                            vm.shootAt(off.x / size.width, off.y / size.height)
                        }
                    }
                },
        ) {
            val jpeg = vm.liveFrame
            if (jpeg != null) {
                val bmp: ImageBitmap? = remember(jpeg) {
                    runCatching {
                        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.asImageBitmap()
                    }.getOrNull()
                }
                if (bmp != null) {
                    androidx.compose.foundation.Image(
                        bmp, "liveview (tap to focus & shoot)",
                        Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (vm.liveRunning) "受信中…" else "ライブビュー停止中")
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
            Button(onClick = vm::refresh, enabled = !vm.refreshing) {
                Icon(Icons.Default.Refresh, null)
                Text("更新")
            }
            ZoomHoldButton("T", OpcClient.ZoomDir.TELE, vm)
        }
    }
}

@Composable
private fun ZoomHoldButton(label: String, dir: OpcClient.ZoomDir, vm: CamViewModel) {
    FilledIconButton(
        onClick = {},
        modifier = Modifier.size(56.dp)
            .pointerInput(dir) {
                awaitEachGesture {
                    awaitFirstDown()
                    vm.zoomHold(dir, true)
                    waitForUpOrCancellation()
                    vm.zoomHold(dir, false)
                }
            },
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}

/** Slim progress banner pinned above the bottom bar while downloads run. */
@Composable
private fun DlBanner() {
    val s by DownloadService.state.collectAsState()
    val active = s.active
    if (active == null && s.queued == 0 && s.failed.isEmpty() && s.done == 0) return
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Card(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        when {
                            s.paused -> "一時停止中"
                            active != null -> "保存中: ${active.substringAfterLast('/')}"
                            s.queued > 0 -> "待機中… (${s.queued}件)"
                            else -> "完了"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (active != null || s.queued > 0) {
                        TextButton(onClick = {
                            if (s.paused) DownloadService.resume(ctx)
                            else DownloadService.pause(ctx)
                        }) {
                            Text(if (s.paused) "再開" else "停止")
                        }
                    }
                    if (s.failed.isNotEmpty()) {
                        TextButton(onClick = { DownloadService.retryFailed(ctx) }) {
                            Text("再試行")
                        }
                    }
                }
                if (s.activeTotal > 0 && active != null && !s.paused) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { (s.activeBytes.toFloat() / s.activeTotal).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (s.done > 0 || s.failed.isNotEmpty() || s.skipped > 0) {
                    Text(
                        "完了 ${s.done}件" +
                            (if (s.skipped > 0) " / スキップ ${s.skipped}件" else "") +
                            (if (s.failed.isNotEmpty()) " / 失敗 ${s.failed.size}件" else ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                s.lastError?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
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
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier.fillMaxWidth().aspectRatio(4f / 3f)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceContainer),
                contentAlignment = Alignment.Center,
            ) {
                val jpeg = vm.detailJpeg
                if (jpeg != null) {
                    val bmp: ImageBitmap? = remember(jpeg) {
                        runCatching {
                            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.asImageBitmap()
                        }.getOrNull()
                    }
                    if (bmp != null) {
                        androidx.compose.foundation.Image(
                            bmp, path,
                            Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                    }
                } else {
                    CircularProgressIndicator()
                }
            }
            Spacer(Modifier.height(12.dp))
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
