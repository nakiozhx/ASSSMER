package com.asmr.player

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.sin

private val Background: Color @Composable get() = MaterialTheme.colorScheme.background
private val Panel: Color @Composable get() = MaterialTheme.colorScheme.surface
private val Green: Color @Composable get() = MaterialTheme.colorScheme.primary
private val Muted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
private val Cream: Color @Composable get() = MaterialTheme.colorScheme.onSurface

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent {
            val vm: PlayerViewModel = viewModel()
            val dark = when(vm.theme) { "light" -> false; "dark" -> true; else -> androidx.compose.foundation.isSystemInDarkTheme() }
            val primary = when(vm.accent) {
                "blue" -> if(dark) Color(0xFFB1CEF5) else Color(0xFF325F92)
                "purple" -> if(dark) Color(0xFFD3BDED) else Color(0xFF755399)
                else -> if(dark) Color(0xFFB9D7C1) else Color(0xFF3D7050)
            }
            val colors = if(dark) darkColorScheme(primary=primary, onPrimary=Color(0xFF101916),
                background=Color(0xFF101916), surface=Color(0xFF1C2923), onSurface=Color(0xFFF0EDDF),
                onBackground=Color(0xFFF0EDDF), onSurfaceVariant=Color(0xFF9FAC9F), secondaryContainer=Color(0xFF314838), onSecondaryContainer=primary)
                else lightColorScheme(primary=primary, onPrimary=Color.White, background=Color(0xFFF7F9F5),
                    surface=Color(0xFFEDF1EA), onSurface=Color(0xFF202B24), onBackground=Color(0xFF202B24),
                    onSurfaceVariant=Color(0xFF647368), secondaryContainer=Color(0xFFDCE8DD), onSecondaryContainer=primary)
            SideEffect {
                val style = if(dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle=style, navigationBarStyle=style)
            }
            MaterialTheme(colorScheme=colors) { AsmrApp(vm) }
        }
    }
}

@Composable
private fun AsmrApp(vm: PlayerViewModel = viewModel()) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    var subtitleTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var exportFormat by rememberSaveable { mutableStateOf<String?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data?.takeIf { result.resultCode == android.app.Activity.RESULT_OK }
        if (uri != null) vm.pendingExport?.let { vm.writeExport(uri, it) }
        vm.pendingExport = null
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                catch(_: Exception) { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                vm.importRoot(uri)
            } catch (_: Exception) { vm.notify("此目录不支持长期访问，请选择其他文件夹") }
        }
    }
    val subtitle = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data?.takeIf { result.resultCode == android.app.Activity.RESULT_OK }
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                vm.attachSubtitle(uri, subtitleTarget)
            } catch (_: Exception) { vm.notify("字幕授权失败，请重新选择文件") }
        }
    }
    val lifecycleOwner=androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer=androidx.lifecycle.LifecycleEventObserver { _, event ->
            if(event==androidx.lifecycle.Lifecycle.Event.ON_RESUME) vm.checkSubtitles()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {lifecycleOwner.lifecycle.removeObserver(observer)}
    }
    val importAction = { import.launch(null) }
    LaunchedEffect(Unit) { if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS) }
    LaunchedEffect(vm.message) { vm.message?.let { snackbar.showSnackbar(it); vm.clearMessage() } }
    BackHandler(vm.screen != "library" || vm.selectedFolder != null) {
        if (vm.screen != "library") vm.screen = "library" else vm.selectedFolder = null
    }
    Scaffold(containerColor = Background, snackbarHost = { SnackbarHost(snackbar) }, bottomBar = {
        Column {
            if (vm.screen != "player" && vm.currentTrack != null) MiniPlayer(vm)
            NavigationBar(containerColor = Background, tonalElevation = 0.dp) {
                listOf("library" to "音频库", "player" to "正在听", "settings" to "设置").forEach { (key, label) ->
                    NavigationBarItem(selected = vm.screen == key, onClick = { vm.screen = key },
                        icon = { Text(when(key) { "library" -> "▤"; "player" -> "♫"; else -> "⚙" }, fontSize = 23.sp) }, label = { Text(label) })
                }
            }
        }
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (vm.screen) {
                "library" -> LibraryScreen(vm, importAction)
                "player" -> PlayerScreen(vm, onAttach = {
                    subtitleTarget = vm.currentTrack?.id
                    subtitle.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        type="*/*"; addCategory(Intent.CATEGORY_OPENABLE)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                        vm.parentUri()?.let { putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, it) }
                    })
                }, onExport = { exportFormat = "choose" })
                else -> SettingsScreen(vm, importAction)
            }
        }
    }
    if(exportFormat != null) AlertDialog(onDismissRequest={exportFormat=null}, title={Text("导出字幕")},
        text={Column { listOf("vtt", "lrc").forEach { format ->
            TextButton(onClick={
                vm.exportText(format)?.let { text ->
                    vm.pendingExport=text
                    export.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        type=if(format=="vtt") "text/vtt" else "application/octet-stream"; addCategory(Intent.CATEGORY_OPENABLE)
                        putExtra(Intent.EXTRA_TITLE, (vm.currentTrack?.name?.substringBeforeLast('.') ?: "字幕") + ".$format")
                        vm.parentUri()?.let { putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, it) }
                    })
                }; exportFormat=null
            }) { Text(format.uppercase()) }
        } }}, confirmButton={TextButton(onClick={exportFormat=null}){Text("取消")}})

}

@Composable
private fun Heading(title: String) {
    Column(Modifier.padding(top = 10.dp, bottom = 20.dp)) {
        Text(title, fontSize = 29.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun LibraryScreen(vm: PlayerViewModel, onImport: () -> Unit) {
    var batchFormat by rememberSaveable {mutableStateOf<String?>(null)}
    var batchKey by rememberSaveable {mutableStateOf<String?>(null)}
    var batchDialog by remember {mutableStateOf(false)}
    val context=androidx.compose.ui.platform.LocalContext.current
    val batchGrant=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if(uri!=null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                batchKey?.let {vm.batchExport(it,batchFormat ?: "vtt",uri)}
            } catch(_: Exception) {vm.notify("未获得写入权限，请重新选择音频文件夹")}
        }
    }
    var sortMenu by remember { mutableStateOf(false) }
    var viewMenu by remember { mutableStateOf(false) }
    val groups = vm.filteredTracks.groupBy { vm.folderKey(it) }.entries.sortedWith { a,b ->
        val name = NaturalOrder.compare(a.value.first().folder,b.value.first().folder)
        when(vm.sort) {
            "reverse" -> -name
            "newest" -> b.value.maxOf { it.modified }.compareTo(a.value.maxOf { it.modified }).takeIf { it != 0 } ?: name
            "oldest" -> a.value.minOf { it.modified }.compareTo(b.value.minOf { it.modified }).takeIf { it != 0 } ?: name
            else -> name
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp)) {
        Heading("ASSSMER")
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("${vm.library.tracks.size} 个音频 · ${vm.library.tracks.map { vm.folderKey(it) }.distinct().size} 个分组", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            FilledTonalButton(onClick = onImport, enabled = !vm.busy, contentPadding = PaddingValues(horizontal=14.dp, vertical=8.dp)) { Text("导入文件夹") }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(value = vm.filter, onValueChange = { vm.filter = it }, placeholder = { Text("搜索", fontSize = 13.sp) },
            singleLine = true, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = vm.favoriteView == "all", onClick = { vm.favoriteView = "all"; vm.selectedFolder = null }, label = { Text("全部") })
            FilterChip(selected = vm.favoriteView != "all", onClick = { vm.favoriteView = if(vm.favoriteView == "all") "folders" else "all"; vm.selectedFolder = null }, label = { Text("收藏") })
            Spacer(Modifier.weight(1f))
            Box {
                TextButton(onClick={sortMenu=true}, contentPadding=PaddingValues(4.dp)){Text("排序")}
                DropdownMenu(expanded=sortMenu,onDismissRequest={sortMenu=false}) {
                    listOf("name" to "名称升序", "reverse" to "名称降序", "newest" to "最新修改", "oldest" to "最早修改").forEach { (key,label) ->
                        DropdownMenuItem(text={Text((if(vm.sort==key) "✓ " else "")+label)},onClick={vm.sort(key);sortMenu=false})
                    }
                }
            }
            Box {
                TextButton(onClick={viewMenu=true}, contentPadding=PaddingValues(4.dp)){Text("视图")}
                DropdownMenu(expanded=viewMenu,onDismissRequest={viewMenu=false}) {
                    listOf("list" to "列表", "small" to "小网格", "medium" to "中网格", "large" to "大网格").forEach { (key,label) ->
                        DropdownMenuItem(text={Text((if(vm.grid==key) "✓ " else "")+label)},onClick={vm.grid(key);viewMenu=false})
                    }
                }
            }
            if (vm.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        if(vm.favoriteView != "all") Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            FilterChip(selected=vm.favoriteView=="folders",onClick={vm.favoriteView="folders";vm.selectedFolder=null},label={Text("★ 文件夹")})
            FilterChip(selected=vm.favoriteView=="tracks",onClick={vm.favoriteView="tracks";vm.selectedFolder=null},label={Text("♥ 单曲")})
        }
        if (vm.selectedFolder != null) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                TextButton(onClick = { vm.selectedFolder = null }, contentPadding = PaddingValues(0.dp),modifier=Modifier.weight(1f)) { Text("‹ 返回文件夹") }
                TextButton(onClick={batchDialog=true},enabled=!vm.busy) {Text("批量导出 TXT")}
            }
        }
        if (vm.library.tracks.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                SoundArt(Modifier.size(110.dp))
                Spacer(Modifier.height(24.dp)); Text("暂无音频", fontSize = 21.sp)
                Spacer(Modifier.height(22.dp)); Button(onClick = onImport, enabled = !vm.busy) { Text("选择音频目录") }
            }
        } else if(vm.favoriteView == "tracks") {
            val tracks=vm.sorted(vm.filteredTracks)
            LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(bottom=18.dp)) {
                if(tracks.isEmpty()) item {Text("暂无收藏单曲",color=Muted,modifier=Modifier.padding(24.dp))}
                itemsIndexed(tracks,key={_,track->track.id}) {index,track->TrackRow(vm,track,index)}
            }
        } else if (vm.selectedFolder == null && vm.grid != "list") {
            if(groups.isEmpty()) Text("没有找到匹配的音频",color=Muted,modifier=Modifier.padding(24.dp))
            LazyVerticalGrid(columns=GridCells.Adaptive(when(vm.grid){"small"->100.dp;"medium"->150.dp;else->240.dp}),
                modifier=Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(bottom=18.dp)) {
                gridItems(groups,key={it.key}) { group -> FolderCard(vm,group.key,group.value,true) }
            }
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 18.dp)) {
                if (vm.selectedFolder == null) {
                    if (groups.isEmpty()) item { Text("没有找到匹配的音频", color = Muted, modifier = Modifier.padding(24.dp)) }
                    items(groups, key = { it.key }) { group ->
                        FolderCard(vm,group.key,group.value,false)
                    }
                } else {
                    val tracks = vm.sorted(vm.filteredTracks.filter { vm.folderKey(it) == vm.selectedFolder })
                    item { Text(tracks.firstOrNull()?.folder ?: "此分组没有匹配的音频", color = Muted, fontSize = 12.sp) }
                    itemsIndexed(tracks, key = { _, t -> t.id }) { index, track ->
                        TrackRow(vm, track, index)
                    }
                }
            }
        }
    }
    if(batchDialog) AlertDialog(onDismissRequest={batchDialog=false},title={Text("批量导出 TXT")},text={Column {
        Text("保存到音频所在文件夹。同名文件会跳过，原 TXT 保留。",fontSize=13.sp)
        listOf("vtt","lrc").forEach { format -> TextButton(onClick={
            batchDialog=false
            vm.selectedFolder?.let {key ->
                batchKey=key;batchFormat=format
                if(vm.batchNeedsPermission(key)) {
                    vm.notify("请选择音频所在文件夹或上级目录，允许保存字幕")
                    batchGrant.launch(vm.batchInitialUri(key))
                } else vm.batchExport(key,format)
            }
        }) {Text("全部导出为 "+format.uppercase())} }
    }},confirmButton={TextButton(onClick={batchDialog=false}){Text("取消")}})
    if(vm.batchReport.isNotEmpty()) AlertDialog(onDismissRequest={vm.clearBatchReport()},title={Text("导出结果")},
        text={Text(vm.batchReport.joinToString("\n"),modifier=Modifier.heightIn(max=320.dp).verticalScroll(rememberScrollState()))},
        confirmButton={TextButton(onClick={vm.clearBatchReport()}){Text("完成")}})

}

@Composable
private fun FolderCard(vm: PlayerViewModel, key: String, tracks: List<Track>, grid: Boolean) {
    val path = tracks.first().folder
    Surface(onClick={vm.selectedFolder=key},shape=RoundedCornerShape(18.dp),color=Panel,modifier=Modifier.fillMaxWidth()) {
        if(grid) Column(Modifier.height(if(vm.grid=="large") 190.dp else 170.dp).padding(12.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text("▤",color=Green,fontSize=28.sp,modifier=Modifier.weight(1f))
                TextButton(onClick={vm.favoriteFolder(key)},contentPadding=PaddingValues(0.dp),modifier=Modifier.size(38.dp)) {
                    Text(if(key in vm.folderFavorites) "★" else "☆",fontSize=23.sp)
                }
            }
            Text(path.substringAfterLast('/'),fontWeight=FontWeight.Medium,maxLines=2,overflow=TextOverflow.Ellipsis)
            Text(path,color=Muted,fontSize=11.sp,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=4.dp))
            Spacer(Modifier.weight(1f)); Text("${tracks.size} 个音频",color=Green,fontSize=11.sp,modifier=Modifier.padding(top=8.dp))
        } else Row(Modifier.padding(start=18.dp,top=12.dp,bottom=12.dp),verticalAlignment=Alignment.CenterVertically) {
            Text("▤",color=Green,fontSize=28.sp)
            Column(Modifier.weight(1f).padding(horizontal=14.dp)) {
                Text(path.substringAfterLast('/'),fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis)
                Text(path,color=Muted,fontSize=11.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                Text("${tracks.size} 个音频",color=Green,fontSize=11.sp,modifier=Modifier.padding(top=5.dp))
            }
            TextButton(onClick={vm.favoriteFolder(key)}){Text(if(key in vm.folderFavorites) "★" else "☆",fontSize=23.sp)}
        }
    }
}

@Composable
private fun TrackRow(vm: PlayerViewModel, track: Track, index: Int) {
    val active = track.id == vm.playback.trackId
    Surface(shape = RoundedCornerShape(15.dp), color = if (active) MaterialTheme.colorScheme.secondaryContainer else Panel) {
        Row(Modifier.fillMaxWidth().clickable(enabled = vm.ready) { vm.play(track) }.padding(start=16.dp, top=10.dp, bottom=10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (active) "♫" else "%02d".format(index+1), color = Green, fontSize = 14.sp, modifier = Modifier.width(34.dp))
            Column(Modifier.weight(1f)) {
                Text(track.name, maxLines=2, overflow=TextOverflow.Ellipsis, fontSize=15.sp)
                Spacer(Modifier.height(4.dp)); Text(if(track.subtitles.isEmpty()) "暂无字幕" else "${track.subtitles.size} 个关联字幕", color=Muted, fontSize=11.sp)
            }
            TextButton(onClick = { vm.favorite(track) }) { Text(if(track.id in vm.favorites) "♥" else "♡", fontSize=23.sp, color=if(track.id in vm.favorites) Green else Muted) }
        }
    }
}

@Composable
private fun MiniPlayer(vm: PlayerViewModel) {
    Surface(color = Panel, modifier = Modifier.padding(horizontal=12.dp).clip(RoundedCornerShape(18.dp))) {
        Row(Modifier.fillMaxWidth().padding(start=15.dp).clickable { vm.screen="player" }, verticalAlignment=Alignment.CenterVertically) {
            Text("♫", color=Green, fontSize=23.sp)
            Column(Modifier.weight(1f).padding(12.dp)) {
                Text(vm.currentTrack?.name.orEmpty(), fontSize=13.sp, maxLines=1, overflow=TextOverflow.Ellipsis)
                Text(vm.currentTrack?.folder.orEmpty(), color=Muted, fontSize=10.sp, maxLines=1, overflow=TextOverflow.Ellipsis)
            }
            TextButton(onClick={vm.togglePlay()}) { Text(if(vm.playback.playing) "暂停" else "播放") }
        }
    }
}

@Composable
private fun PlayerScreen(vm: PlayerViewModel, onAttach: () -> Unit, onExport: () -> Unit) {
    val track = vm.currentTrack
    if (track == null) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment=Alignment.CenterHorizontally, verticalArrangement=Arrangement.Center) {
            SoundArt(Modifier.size(120.dp)); Spacer(Modifier.height(24.dp)); Text("未选择音频", fontSize=19.sp)
            Spacer(Modifier.height(18.dp)); Button(onClick={vm.screen="library"}) { Text("前往音频库") }
        }
        return
    }
    var speedMenu by remember { mutableStateOf(false) }
    var timerMenu by remember { mutableStateOf(false) }
    var subtitleMenu by remember { mutableStateOf(false) }
    var offsetMenu by remember { mutableStateOf(false) }
    var dragPosition by remember(track.id) { mutableStateOf<Float?>(null) }
    Column(Modifier.fillMaxSize().padding(horizontal=22.dp)) {
        Row(Modifier.fillMaxWidth().padding(top=6.dp), horizontalArrangement=Arrangement.End, verticalAlignment=Alignment.CenterVertically) {
            TextButton(onClick={vm.favorite(track)}) { Text(if(track.id in vm.favorites) "♥ 收藏" else "♡ 收藏") }
        }
        Text(track.name.substringBeforeLast('.'), fontSize=24.sp, fontWeight=FontWeight.Medium, maxLines=2, overflow=TextOverflow.Ellipsis)
        Text(track.folder, color=Muted, fontSize=12.sp, maxLines=1, overflow=TextOverflow.Ellipsis, modifier=Modifier.padding(top=6.dp, bottom=12.dp))
        PlaybackService.playbackError.collectAsState().value?.let { Text(it, color=MaterialTheme.colorScheme.error, fontSize=12.sp) }
        Surface(shape=RoundedCornerShape(22.dp), color=Panel, modifier=Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.padding(horizontal=16.dp, vertical=12.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text("字幕", color=Muted, fontSize=11.sp, modifier=Modifier.weight(1f))
                    Box {
                        TextButton(onClick={vm.checkSubtitles {subtitleMenu=true}}, contentPadding=PaddingValues(horizontal=6.dp)) { Text("${vm.selectedSubtitle?.extension?.uppercase() ?: "关联"} ▾", fontSize=11.sp) }
                        DropdownMenu(expanded=subtitleMenu, onDismissRequest={subtitleMenu=false}) {
                            track.subtitles.forEach { file -> DropdownMenuItem(text={Text(file.name)}, onClick={vm.loadSubtitle(file);subtitleMenu=false}) }
                            DropdownMenuItem(text={Text("选择字幕文件…")}, onClick={onAttach();subtitleMenu=false})
                            DropdownMenuItem(text={Text("导出字幕…")}, enabled=vm.document?.timed == true && vm.playback.duration > 0, onClick={onExport();subtitleMenu=false})
                        }
                    }
                    TextButton(onClick={offsetMenu=true}, contentPadding=PaddingValues(horizontal=6.dp)) { Text("同步", fontSize=11.sp) }
                }
                Transcript(vm, Modifier.weight(1f).fillMaxWidth())
                vm.document?.warnings?.takeIf { it.isNotEmpty() }?.let { warnings ->
                    var show by remember { mutableStateOf(false) }
                    TextButton(onClick={show=true}, contentPadding=PaddingValues(0.dp)) { Text("${warnings.size} 条解析提示 · 点击查看", color=Color(0xFFE2C49E), fontSize=11.sp) }
                    if(show) AlertDialog(onDismissRequest={show=false}, title={Text("字幕解析提示")}, text={Text(warnings.joinToString("\n"), modifier=Modifier.heightIn(max=300.dp).verticalScroll(rememberScrollState()))}, confirmButton={TextButton(onClick={show=false}){Text("知道了")}})
                }
            }
        }
        Slider(value = dragPosition ?: vm.playback.position.toFloat().coerceAtMost(vm.playback.duration.toFloat()),
            onValueChange={dragPosition=it}, onValueChangeFinished={dragPosition?.let { vm.seek(it.toLong()) };dragPosition=null},
            valueRange=0f..vm.playback.duration.toFloat().coerceAtLeast(1f), enabled=vm.playback.duration>0,
            modifier=Modifier.fillMaxWidth().height(36.dp).padding(top=6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween) {
            Text(clock((dragPosition?.toLong() ?: vm.playback.position)), color=Muted, fontSize=11.sp)
            Text(clock(vm.playback.duration), color=Muted, fontSize=11.sp)
        }
        Row(Modifier.fillMaxWidth().padding(vertical=6.dp), horizontalArrangement=Arrangement.SpaceEvenly, verticalAlignment=Alignment.CenterVertically) {
            TextButton(onClick={vm.previous()}) { Text("上一段", fontSize=12.sp) }
            TextButton(onClick={vm.skip(-10_000)}) { Text("−10s", fontSize=12.sp) }
            FilledIconButton(onClick={vm.togglePlay()}, modifier=Modifier.size(58.dp).semantics { contentDescription = if(vm.playback.playing) "暂停播放" else "开始播放" }, shape=CircleShape) {
                Text(if(vm.playback.buffering) "…" else if(vm.playback.playing) "Ⅱ" else "▶", fontSize=24.sp)
            }
            TextButton(onClick={vm.skip(10_000)}) { Text("+10s", fontSize=12.sp) }
            TextButton(onClick={vm.next()}) { Text("下一段", fontSize=12.sp) }
        }
        Row(Modifier.fillMaxWidth().padding(bottom=4.dp), horizontalArrangement=Arrangement.SpaceBetween) {
            Box {
                TextButton(onClick={speedMenu=true}) { Text("${vm.playback.speed}× 倍速", fontSize=12.sp) }
                DropdownMenu(expanded=speedMenu, onDismissRequest={speedMenu=false}) {
                    listOf(0.5f,0.75f,1f,1.25f,1.5f,2f).forEach { speed -> DropdownMenuItem(text={Text("${speed}×")}, onClick={vm.speed(speed);speedMenu=false}) }
                }
            }
            TextButton(onClick={vm.repeat((vm.playback.repeat+1)%3)}) { Text(when(vm.playback.repeat){1->"单段循环";2->"分组循环";else->"顺序播放"}, fontSize=12.sp) }
            Box {
                TextButton(onClick={timerMenu=true}) { Text(if(vm.playback.sleepRemaining>0) "定时 ${clock(vm.playback.sleepRemaining)}" else "睡眠定时", fontSize=12.sp) }
                DropdownMenu(expanded=timerMenu,onDismissRequest={timerMenu=false}) {
                    listOf(0,5,15,30,60,90).forEach { m -> DropdownMenuItem(text={Text(if(m==0) "关闭定时" else "$m 分钟后暂停")},onClick={vm.timer(m);timerMenu=false}) }
                }
            }
        }
    }
    if(offsetMenu) AlertDialog(onDismissRequest={offsetMenu=false}, title={Text("字幕同步")}, text={
        Column {
            Text("偏移 ${vm.offset / 1000.0} 秒", color=Green)
            Text("正值让字幕晚出现，负值让字幕提前。", fontSize=12.sp)
            Row { TextButton(onClick={vm.offset(vm.offset-500)}) {Text("提前 0.5s")}; TextButton(onClick={vm.offset(vm.offset+500)}) {Text("延后 0.5s")} }
            TextButton(onClick={vm.offset(0)}) {Text("恢复同步")}
            Text("字幕字号 ${vm.fontSize.toInt()}", fontSize=13.sp)
            Slider(value=vm.fontSize,onValueChange={vm.font(it)},valueRange=14f..32f,steps=17)
        }
    },confirmButton={TextButton(onClick={offsetMenu=false}) {Text("完成")}})
}

@Composable
private fun Transcript(vm: PlayerViewModel, modifier: Modifier) {
    val doc = vm.document
    when {
        vm.subtitleBusy -> Box(modifier, contentAlignment=Alignment.Center) { CircularProgressIndicator(Modifier.size(26.dp),strokeWidth=2.dp) }
        vm.subtitleError != null -> Box(modifier, contentAlignment=Alignment.Center) { Text(vm.subtitleError.orEmpty(),color=Muted,fontSize=13.sp) }
        doc == null -> Column(modifier, verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
            SoundArt(Modifier.size(90.dp));Spacer(Modifier.height(20.dp));Text("暂无字幕",color=Muted,fontSize=13.sp)
        }
        !doc.timed -> Column(modifier.verticalScroll(rememberScrollState())) {
            Text("文稿",color=Green,fontSize=11.sp,modifier=Modifier.padding(bottom=14.dp))
            Text(doc.plainText, fontSize=vm.fontSize.sp,lineHeight=(vm.fontSize*1.65f).sp)
        }
        else -> {
            val state = rememberLazyListState()
            var follow by remember(vm.selectedSubtitle?.uri) { mutableStateOf(true) }
            val time = vm.playback.position-vm.offset
            val active = doc.at(time)
            val index = active.firstOrNull()
            LaunchedEffect(index,follow,vm.selectedSubtitle?.uri) { if(index != null && follow && !state.isScrollInProgress) state.animateScrollToItem((index-1).coerceAtLeast(0)) }
            Column(modifier) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    TextButton(onClick={follow=!follow}, contentPadding=PaddingValues(0.dp)) {Text(if(follow) "跟随中" else "自由阅读",fontSize=10.sp)}
                }
                LazyColumn(state=state,modifier=Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp),contentPadding=PaddingValues(bottom=50.dp)) {
                    itemsIndexed(doc.cues) { i,cue ->
                        val isActive = i in active
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if(isActive) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                            .clickable { vm.seek(cue.startMs+vm.offset) }.padding(12.dp)) {
                            if(cue.words.isNotEmpty() && isActive) {
                                val annotated = androidx.compose.ui.text.buildAnnotatedString {
                                    cue.words.forEach { w ->
                                        pushStyle(androidx.compose.ui.text.SpanStyle(color=if(time>=w.startMs) Cream else Muted))
                                        append(w.text);pop()
                                    }
                                }
                                Text(annotated,fontSize=vm.fontSize.sp,lineHeight=(vm.fontSize*1.5f).sp)
                            } else Text(cue.text,color=if(isActive) Cream else Muted,fontSize=vm.fontSize.sp,lineHeight=(vm.fontSize*1.5f).sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(vm: PlayerViewModel, onImport: () -> Unit) {
    var remove by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=22.dp)) {
        Heading("设置")
        Text("主题",color=Green,fontSize=13.sp)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf("system" to "随系统", "light" to "亮色", "dark" to "暗色").forEach { (key,label) ->
                FilterChip(selected=vm.theme==key,onClick={vm.theme(key)},label={Text(label)})
            }
        }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf("green" to "绿色", "blue" to "蓝色", "purple" to "紫色").forEach { (key,label) ->
                FilterChip(selected=vm.accent==key,onClick={vm.accent(key)},label={Text(label)})
            }
        }
        Spacer(Modifier.height(18.dp))
        OverlaySetting(vm)
        Spacer(Modifier.height(24.dp))
        Text("音频目录",color=Green,fontSize=13.sp)
        Spacer(Modifier.height(12.dp))
        vm.library.roots.forEach { root ->
            val name = vm.library.tracks.firstOrNull { it.root==root }?.folder?.substringBefore('/') ?: "空目录"
            Surface(color=Panel,shape=RoundedCornerShape(15.dp),modifier=Modifier.fillMaxWidth().padding(bottom=10.dp)) {
                Row(Modifier.padding(15.dp),verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(name);Text("${vm.library.tracks.count { it.root==root }} 个音频",color=Muted,fontSize=12.sp) }
                    TextButton(onClick={remove=root},enabled=!vm.busy) {Text("移除")}
                }
            }
        }
        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            Button(onClick=onImport,enabled=!vm.busy){Text("添加目录")}
            OutlinedButton(onClick={vm.refresh()},enabled=!vm.busy && vm.library.roots.isNotEmpty()){Text("重新扫描")}
        }
        if(vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top=12.dp))
        vm.scanWarnings.forEach {Text(it,color=Color(0xFFE2C49E),fontSize=12.sp,modifier=Modifier.padding(top=8.dp))}
        Spacer(Modifier.height(26.dp))
        Text("阅读",color=Green,fontSize=13.sp)
        Text("字幕字号：${vm.fontSize.toInt()}",modifier=Modifier.padding(top=12.dp),fontSize=14.sp)
        Slider(value=vm.fontSize,onValueChange={vm.font(it)},valueRange=14f..32f,steps=17)
        Spacer(Modifier.height(22.dp));Text("ASSSMER  /  0.3.1",color=Muted,fontSize=11.sp)
    }
    remove?.let { root -> AlertDialog(onDismissRequest={remove=null},title={Text("移除音频目录？")},text={Text("只移除音频库中的记录，原音频和字幕文件会保留。")},
        confirmButton={TextButton(onClick={vm.removeRoot(root);remove=null}){Text("移除")}},dismissButton={TextButton(onClick={remove=null}){Text("取消")}}) }
}

@Composable
private fun OverlaySetting(vm: PlayerViewModel) {
    val context=androidx.compose.ui.platform.LocalContext.current
    val visible by PlaybackService.overlayVisible.collectAsState()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if(android.provider.Settings.canDrawOverlays(context)) vm.overlay(true)
        else vm.notify("未开启悬浮窗权限")
    }
    Column {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("悬浮字幕",color=Green,fontSize=13.sp)
            Text("上一句 · 当前句 · 下一句",color=Muted,fontSize=11.sp,modifier=Modifier.padding(top=6.dp))
        }
        Switch(checked=visible,enabled=vm.ready,onCheckedChange={on ->
            if(!on || android.provider.Settings.canDrawOverlays(context)) vm.overlay(on)
            else {
                try { permission.launch(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:"+context.packageName))) }
                catch(_: Exception) { vm.notify("请在系统设置中允许 ASSSMER 显示在其他应用上层") }
            }
        })
    }
    val locked by PlaybackService.overlayLocked.collectAsState()
    val transparent by PlaybackService.overlayTransparent.collectAsState()
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        Text("锁定位置",modifier=Modifier.weight(1f),fontSize=14.sp)
        Switch(checked=locked,onCheckedChange={vm.overlayLocked(it)})
    }
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        Text("透明背景",modifier=Modifier.weight(1f),fontSize=14.sp)
        Switch(checked=transparent,onCheckedChange={vm.overlayTransparent(it)})
    }
    }

}

@Composable
private fun SoundArt(modifier: Modifier) {
    val artColor = Green
    Canvas(modifier.clip(CircleShape).background(Color(0xFF253B2E))) {
        val center = size.width/2
        for(i in -5..5) {
            val height = size.height * (0.13f + 0.3f * (1f - kotlin.math.abs(i)/6f))
            val x = center+i*size.width/15f
            drawLine(if(i%2==0) artColor else Color(0xFFD3BD9D),androidx.compose.ui.geometry.Offset(x,(size.height-height)/2),androidx.compose.ui.geometry.Offset(x,(size.height+height)/2),strokeWidth=size.width/35f,cap=StrokeCap.Round)
        }
    }
}

fun clock(ms: Long): String {
    val sec = ms.coerceAtLeast(0)/1000
    return if(sec>=3600) "%d:%02d:%02d".format(sec/3600,sec/60%60,sec%60) else "%02d:%02d".format(sec/60,sec%60)
}
