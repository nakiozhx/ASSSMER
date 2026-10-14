package com.asmr.player

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.util.concurrent.TimeUnit

/** Run after importing test-assets/RJ10 through the system folder picker. No test-only import bypass. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class PlayerIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var controller: MediaController
    private fun main(block: () -> Unit) { instrumentation.runOnMainSync(block) }
    private fun await(timeout: Long = 10_000, condition: () -> Boolean) {
        val end = android.os.SystemClock.elapsedRealtime()+timeout
        while(android.os.SystemClock.elapsedRealtime()<end) {
            var ready=false; main {ready=condition()};if(ready)return;Thread.sleep(100)
        }
        fail("Condition did not become true within $timeout ms")
    }
    @Before fun connect() {
        lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaController>
        main {future=MediaController.Builder(context,SessionToken(context,ComponentName(context,PlaybackService::class.java))).buildAsync()}
        controller=future.get(10,TimeUnit.SECONDS)
        main {controller.pause();controller.repeatMode=Player.REPEAT_MODE_OFF;controller.setPlaybackSpeed(1f)}
    }
    @After fun cleanup() {main {controller.pause();controller.sendCustomCommand(SessionCommand(PlaybackService.TIMER_COMMAND,Bundle.EMPTY),Bundle().apply{putInt("minutes",0)});controller.release()}}

    @Test fun a_folderImportAssociatesAndOrdersWithoutMergingNames() {
        val store=LibraryStore(context);val library=store.load()
        val a=library.tracks.filter {it.folder=="RJ10/A"}.sortedWith {x,y->NaturalOrder.compare(x.name,y.name)}
        assertEquals(listOf("1.wav","2.wav","10.wav"),a.map {it.name})
        assertTrue(a[0].subtitles.any {it.name=="1.txt"})
        assertFalse(a[0].subtitles.any {it.name=="2.vtt"})
        assertEquals(listOf("2.vtt"),a[1].subtitles.map {it.name})
        assertTrue(a[2].subtitles.all { Sidecars.matches(a[2].name, it.name) })
        assertEquals(1,library.tracks.count {it.folder=="RJ10/Bonus/A"})
        assertEquals(1,library.tracks.count {it.folder=="RJ10/B"})
        val txt=a[0].subtitles.first {it.name=="1.txt"}
        val key=java.security.MessageDigest.getInstance("SHA-256").digest(txt.uri.toByteArray()).joinToString("") {"%02x".format(it)}
        val converted=File(context.filesDir,"converted/$key.vtt")
        if(converted.exists()) assertTrue(converted.delete())
        val scanned=store.scan(android.net.Uri.parse(library.roots.first()))
        assertEquals(5,scanned.tracks.size);assertTrue(scanned.warnings.isEmpty())
        assertTrue("Folder scan must create VTT before opening the track",converted.exists())
        assertTrue(converted.readText().contains("00:00:08.000 --> 00:00:12.000"))
    }
    @Test fun b_txtConversionPersistsVttAndKeepsSourceIntact() {
        val store=LibraryStore(context)
        val subtitle=store.load().tracks.first {it.folder=="RJ10/A"&&it.name=="1.wav"}.subtitles.first {it.name=="1.txt"}
        val original=context.contentResolver.openInputStream(android.net.Uri.parse(subtitle.uri))!!.use {it.readBytes()}
        val parsed=store.readSubtitle(subtitle,12_000)
        assertEquals(listOf(1000L,4000L,8000L),parsed.cues.map {it.startMs})
        val cached=File(context.filesDir,"converted").listFiles()!!.first()
        val vtt=cached.readText()
        assertTrue(vtt.startsWith("WEBVTT"));assertTrue(vtt.contains("00:00:08.000 --> 00:00:12.000"))
        val after=context.contentResolver.openInputStream(android.net.Uri.parse(subtitle.uri))!!.use {it.readBytes()}
        assertArrayEquals(original,after)
        assertEquals(parsed.cues,Subtitles.parse(vtt,"vtt",12_000).cues)
    }
    @Test fun c_playbackSeeksChangesSpeedAndAdvancesInsideFolder() {
        val tracks=LibraryStore(context).load().tracks.filter {it.folder=="RJ10/A"}.sortedWith {a,b->NaturalOrder.compare(a.name,b.name)}
        main {controller.setMediaItems(tracks.map {it.mediaItem()},0,0);controller.prepare();controller.play()}
        await {controller.isPlaying&&controller.duration==12_000L}
        main {controller.seekTo(5000);controller.setPlaybackSpeed(1.5f)}
        await {controller.currentPosition>=5000&&controller.playbackParameters.speed==1.5f}
        main {controller.setPlaybackSpeed(1f);controller.seekTo(11_000)}
        await {controller.currentMediaItem?.mediaId==tracks[1].id&&controller.isPlaying}
        main {controller.seekTo(7000);controller.pause()}
        Thread.sleep(1500)
        val saved=context.getSharedPreferences("playback",0).getLong("position:${tracks[1].id}",0)
        assertTrue("Saved progress must be near the requested seek",saved in 6900..8500)
    }
    @Test fun d_sleepTimerPausesWhileAppIsInBackground() {
        val track=LibraryStore(context).load().tracks.first {it.folder=="RJ10/A"&&it.name=="2.wav"}
        main {controller.setMediaItem(track.mediaItem());controller.prepare();controller.play()}
        await {controller.isPlaying}
        main {controller.sendCustomCommand(SessionCommand(PlaybackService.TIMER_COMMAND,Bundle.EMPTY),Bundle().apply{putInt("minutes",1)})}
        await {PlaybackService.sleepDeadline.value>0}
        instrumentation.uiAutomation.executeShellCommand("input keyevent HOME").close()
        Thread.sleep(2500)
        main {assertTrue("Audio must keep playing in background",controller.isPlaying);assertTrue(controller.currentPosition>1000)}
        await(70_000) {PlaybackService.sleepDeadline.value==0L&&!controller.isPlaying}
        main {assertTrue("Timer must pause before the track ends",controller.currentPosition in 58_000..65_000)}
    }
    @Test fun e_repeatModesAndFavoritesPersist() {
        val store=LibraryStore(context)
        val tracks=store.load().tracks.filter {it.folder=="RJ10/A"}.sortedWith {a,b->NaturalOrder.compare(a.name,b.name)}
        val old=store.favorites()
        store.setFavorites(old+tracks[0].id)
        assertTrue(LibraryStore(context).favorites().contains(tracks[0].id))
        store.setFavorites(old)
        main {controller.setMediaItems(tracks.map {it.mediaItem()},0,11_000);controller.repeatMode=Player.REPEAT_MODE_ONE;controller.prepare();controller.play()}
        await {controller.isPlaying&&controller.currentMediaItem?.mediaId==tracks[0].id&&controller.currentPosition<5000}
        main {controller.repeatMode=Player.REPEAT_MODE_ALL;controller.seekTo(2,14_000)}
        await {controller.isPlaying&&controller.currentMediaItem?.mediaId==tracks[0].id&&controller.currentPosition<5000}
        assertEquals("RJ10/A",tracks.first().folder)
    }
    @Test fun f_doubleExtensionParentFolderAndSettingsPersist() {
        val store = LibraryStore(context)
        val scan = store.scan(android.net.Uri.parse(store.load().roots.first()))
        val track = scan.tracks.first { it.folder=="RJ10/A" && it.name=="10.wav" }
        assertTrue(track.subtitles.any { it.name=="10.wav.vtt" })
        assertEquals("primary:Documents/RJ10/A", android.provider.DocumentsContract.getDocumentId(store.parent(track)!!))
        assertTrue(track.modified>0)
        val keys=store.folderFavorites(); val key="${track.root}|${track.folder}"
        store.setFolderFavorites(keys+key)
        assertTrue(key in LibraryStore(context).folderFavorites())
        store.setFolderFavorites(keys)
        val old=store.setting("grid", "list")
        store.setSetting("grid", "small")
        assertEquals("small", LibraryStore(context).setting("grid"))
        store.setSetting("grid", old)
    }
    @Test fun g_overlayFollowsBackgroundPlaybackAndCloses() {
        assertTrue("Enable overlay permission through system Settings before testing", android.provider.Settings.canDrawOverlays(context))
        val track=LibraryStore(context).load().tracks.first { it.folder=="RJ10/A" && it.name=="2.wav" }
        main {
            controller.setMediaItem(track.mediaItem());controller.prepare();controller.play()
            controller.sendCustomCommand(SessionCommand(PlaybackService.OVERLAY_COMMAND,Bundle.EMPTY),Bundle().apply {putBoolean("enabled",true)})
        }
        await {controller.isPlaying && PlaybackService.overlayVisible.value}
        context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Thread.sleep(1000)
        await {controller.isPlaying && PlaybackService.overlayVisible.value}
        main {controller.sendCustomCommand(SessionCommand(PlaybackService.OVERLAY_COMMAND,Bundle.EMPTY),Bundle().apply {putBoolean("enabled",false)})}
        await { !PlaybackService.overlayVisible.value }
    }
    @Test fun h_batchExportSkipsExistingFilesAndDeletedSubtitleIsAbsent() {
        val store=LibraryStore(context)
        val tracks=store.load().tracks.filter {it.folder=="RJ10/A"}
        val tree=store.writableTree(tracks.first()) ?: error("Grant writing to folder A first")
        val track=tracks.first {it.name=="10.wav"}
        val directory=android.provider.DocumentsContract.buildDocumentUriUsingTree(tree,android.provider.DocumentsContract.getDocumentId(store.parent(track)!!))
        val before=context.contentResolver.openInputStream(android.net.Uri.parse(tracks.first {it.name=="1.wav"}.subtitles.first {it.name=="1.vtt"}.uri))!!.use {it.readBytes()}
        val result=store.batchExport(tracks,"vtt",null)
        assertTrue(result.any {it.contains("1.vtt：同名文件已存在")})
        val after=context.contentResolver.openInputStream(android.net.Uri.parse(tracks.first {it.name=="1.wav"}.subtitles.first {it.name=="1.vtt"}.uri))!!.use {it.readBytes()}
        assertArrayEquals(before,after)
        val source=store.readSubtitle(track.subtitles.first {it.name=="10.txt"},15000)
        val target=android.provider.DocumentsContract.createDocument(context.contentResolver,directory,"application/octet-stream","10.cleanup.vtt")!!
        try {
            assertEquals(true,store.exists(target.toString()))
            context.contentResolver.openOutputStream(target,"wt")!!.bufferedWriter().use {it.write(Subtitles.vtt(source,15000))}
            assertEquals(source.cues,store.readSubtitle(SubtitleFile(target.toString(),"10.cleanup.vtt"),15000).cues)
        } finally {assertTrue(android.provider.DocumentsContract.deleteDocument(context.contentResolver,target))}
        assertEquals(false,store.exists(target.toString()))
    }
    @Test @androidx.test.filters.SdkSuppress(minSdkVersion=30)
    fun i_floatingLyricsHasFixedSizeLockAndTransparentBackground() {
        val store=LibraryStore(context)
        val locked=store.setting("overlayLocked","false")
        val transparent=store.setting("overlayTransparent","false")
        val track=store.load().tracks.first {it.folder=="RJ10/A"&&it.name=="1.wav"}
        var overlay: FloatingLyrics? = null
        fun root(): android.view.View? = android.view.inspector.WindowInspector.getGlobalWindowViews().firstOrNull { view ->
            fun hasLock(v: android.view.View): Boolean = v.contentDescription?.toString()?.contains("悬浮字幕位置")==true ||
                (v is android.view.ViewGroup && (0 until v.childCount).any {hasLock(v.getChildAt(it))})
            hasLock(view)
        }
        fun text(view: android.view.View): List<String> = if(view is android.widget.TextView) listOf(view.text.toString())
            else if(view is android.view.ViewGroup) (0 until view.childCount).flatMap {text(view.getChildAt(it))} else emptyList()
        try {
            main {
                controller.setMediaItem(track.mediaItem());controller.prepare();controller.seekTo(1500)
                val display=context.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(android.view.Display.DEFAULT_DISPLAY)
                val wc=context.createDisplayContext(display).createWindowContext(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null)
                overlay=FloatingLyrics(wc,controller);overlay!!.enable(true)
            }
            await {controller.duration==12000L && root()!=null}
            main {overlay!!.update()}
            await {overlay!!.update();text(root()!!).contains("这是第一段测试音频")}
            var width=0;var height=0
            main {width=root()!!.width;height=root()!!.height;assertTrue(width>0 && height>0)}
            main {controller.seekTo(2850);overlay!!.update();controller.play()}
            await {
                overlay!!.update()
                val host=(root() as android.widget.LinearLayout).getChildAt(1) as android.widget.FrameLayout
                host.getChildAt(0).translationY<0
            }
            main {controller.pause();controller.seekTo(8500);overlay!!.update()}
            await {overlay!!.update();text(root()!!).contains("点击文字可以跳转")}
            main {assertEquals(width,root()!!.width);assertEquals(height,root()!!.height)}
            main {
                store.setSetting("overlayTransparent","true");PlaybackService.overlayTransparent.value=true;overlay!!.update()
                assertNull(root()!!.background)
                PlaybackService.overlayTransparent.value=false;overlay!!.update();assertNotNull(root()!!.background)
            }
            fun drag() {
                val view=root()!!;val now=android.os.SystemClock.uptimeMillis()
                val y=context.resources.displayMetrics.density*70
                listOf(android.view.MotionEvent.ACTION_DOWN to y,android.view.MotionEvent.ACTION_MOVE to y+100,android.view.MotionEvent.ACTION_UP to y+100).forEachIndexed {index,pair ->
                    val event=android.view.MotionEvent.obtain(now,now+index*20L,pair.first,80f,pair.second,0)
                    view.dispatchTouchEvent(event);event.recycle()
                }
            }
            var before=0
            main {PlaybackService.overlayLocked.value=true;overlay!!.update();before=(root()!!.layoutParams as android.view.WindowManager.LayoutParams).y;drag()
                assertEquals(before,(root()!!.layoutParams as android.view.WindowManager.LayoutParams).y)}
            main {PlaybackService.overlayLocked.value=false;overlay!!.update();drag()
                assertTrue((root()!!.layoutParams as android.view.WindowManager.LayoutParams).y>before)}
        } finally {
            main {overlay?.destroy()}
            store.setSetting("overlayLocked",locked);store.setSetting("overlayTransparent",transparent)
            PlaybackService.overlayLocked.value=locked.toBoolean();PlaybackService.overlayTransparent.value=transparent.toBoolean()
        }
    }
}
