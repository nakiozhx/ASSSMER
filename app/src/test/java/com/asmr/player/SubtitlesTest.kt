package com.asmr.player

import org.junit.Assert.*
import org.junit.Test

class SubtitlesTest {
    @Test fun chineseTxtBecomesRoundTrippableVtt() {
        val txt = "【00:01.500 你好 & <欢迎>】\n[00:05.200] 放松\n00:12 闭上眼睛"
        val parsed = Subtitles.parse(txt, "TXT", 20_000)
        assertEquals(listOf(1500L,5200L,12000L),parsed.cues.map {it.startMs})
        assertEquals(listOf(5200L,12000L,20000L),parsed.cues.map {it.endMs})
        assertEquals("你好 & <欢迎>",parsed.cues.first().text)
        val exported = Subtitles.vtt(parsed,20_000)
        assertTrue(exported.startsWith("WEBVTT\n\n"))
        assertEquals(parsed.cues,Subtitles.parse(exported,"vtt",20_000).cues)
    }
    @Test fun acceptsHoursAndCommaMilliseconds() {
        assertEquals(3_723_004L,Subtitles.time("01:02:03,004"))
        assertEquals(10L,Subtitles.time("00:00.01"))
        assertNull(Subtitles.time("00:60"))
        assertNull(Subtitles.time("01:60:00"))
    }
    @Test fun preservesPlainTxtInsteadOfInventingTiming() {
        val doc = Subtitles.parse("普通文稿\n没有时间轴", "txt")
        assertFalse(doc.timed)
        assertEquals("普通文稿\n没有时间轴",doc.plainText)
        assertThrows(IllegalArgumentException::class.java) {Subtitles.vtt(doc,20_000)}
    }
    @Test fun lrcOffsetMultipleTagsAndWords() {
        val doc = Subtitles.parse("[ti:示例]\n[offset:+500]\n[00:01.00][00:03.00]你好\n[00:05.00]<00:05.00>放<00:05.50>松", "lrc", 10_000)
        assertEquals(listOf(1500L,3500L,5500L),doc.cues.map {it.startMs})
        assertEquals("放松",doc.cues.last().text)
        assertEquals(listOf(5500L,6000L),doc.cues.last().words.map {it.startMs})
    }
    @Test fun reportsInvalidAndOutOfDurationLinesAndSorts() {
        val doc = Subtitles.parse("[00:05] 五秒\n[00:02] 两秒\n[00:99] 错误\n[00:20] 超出", "txt",10_000)
        assertEquals(listOf(2000L,5000L),doc.cues.map {it.startMs})
        assertTrue(doc.warnings.any {it.contains("第 3 行")})
        assertTrue(doc.warnings.any {it.contains("超出")})
        assertTrue(doc.warnings.any {it.contains("排序")})
    }
    @Test fun srtHasMultilineTextAndExplicitGaps() {
        val doc = Subtitles.parse("1\n00:00:01,000 --> 00:00:03,000\n第一行\n第二行\n\n2\n00:00:05,000 --> 00:00:07,000\n下一句", "srt",10_000)
        assertEquals("第一行\n第二行",doc.cues.first().text)
        assertEquals(listOf(0),doc.at(2500))
        assertTrue(doc.at(4000).isEmpty())
        assertTrue(doc.at(7000).isEmpty())
    }
    @Test fun vttOverlapAndNoteBlocks() {
        val text = "WEBVTT\n\nNOTE comment\nignore this\n\n1\n00:01.000 --> 00:04.000 align:start\n<v speaker>第一句</v>\n\n2\n00:02.000 --> 00:03.000\n第二句\n"
        val doc = Subtitles.parse(text,"vtt",10_000)
        assertEquals(listOf(0,1),doc.at(2500))
        assertEquals("第一句",doc.cues.first().text)
    }
    @Test fun assReadsEventFormatAndCommasInDialogue() {
        val text = "[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\nDialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,{\\b1}你好, 世界\\N下一行"
        val doc = Subtitles.parse(text,"ass",10_000)
        assertEquals(Cue(1000,2000,"你好, 世界\n下一行"),doc.cues.single())
    }
    @Test fun decodesUtf8BomUtf16AndGbk() {
        val text="[00:01] 中文"
        assertEquals(text,Subtitles.decode(("\uFEFF"+text).toByteArray()))
        assertEquals(text,Subtitles.decode(byteArrayOf(0xFF.toByte(),0xFE.toByte())+text.toByteArray(Charsets.UTF_16LE)))
        assertEquals(text,Subtitles.decode(text.toByteArray(java.nio.charset.Charset.forName("GBK"))))
    }
    @Test fun naturalOrderDoesNotSortTenBeforeTwoOrOverflow() {
        val names=listOf("10.MP3","2.MP3","1.MP3","999999999999999999999999.MP3")
        assertEquals(listOf("1.MP3","2.MP3","10.MP3","999999999999999999999999.MP3"),names.sortedWith(NaturalOrder))
    }
    @Test fun rejectsMissingAudioDurationForLastTxtCue() {
        assertThrows(IllegalArgumentException::class.java) {Subtitles.vtt(Subtitles.parse("00:01 最后一句","txt"),0)}
    }
    @Test fun lrcExportPreservesMillisecondsGapsAndLongAudio() {
        val doc = SubtitleDocument(listOf(Cue(1234, 2345, "第一句"), Cue(3601234, 3602234, "第二句")), "", emptyList())
        val text = Subtitles.lrc(doc, 3603000)
        assertTrue(text.contains("[60:01.234]第二句"))
        val back = Subtitles.parse(text, "lrc", 3603000)
        assertEquals(doc.cues, back.cues)
        assertTrue(back.warnings.isEmpty())
        assertTrue(back.at(3000).isEmpty())
    }
    @Test fun lrcExportFlattensOverlappingCaptions() {
        val doc = SubtitleDocument(listOf(Cue(1000, 4000, "A"), Cue(2000, 3000, "B")), "", emptyList())
        val back = Subtitles.parse(Subtitles.lrc(doc, 5000), "lrc", 5000)
        assertEquals("A B", back.cues[back.at(2500).single()].text)
        assertTrue(back.at(4500).isEmpty())
    }
    @Test fun sidecarsMatchStemOrWholeAudioNameOnly() {
        assertTrue(Sidecars.matches("声音.WAV", "声音.wav.vtt"))
        assertTrue(Sidecars.matches("声音.wav", "声音.txt"))
        assertTrue(Sidecars.matches("声音.part1.mp3", "声音.part1.mp3.LRC"))
        assertFalse(Sidecars.matches("声音.part1.mp3", "声音.txt"))
        assertFalse(Sidecars.matches("1.wav", "11.wav.vtt"))
    }
    @Test fun txtWithSrtContentExportsOnlyDialogueAndPreservesEndTimes() {
        val text="1\n00:00:10,000 --> 00:00:20,260\n嗯...\n\n2\n00:00:30,000 --> 00:00:34,940\n嗯？ 嘻嘻... 没事呀...\n\n3\n00:00:35,000 --> 00:00:39,355\n你睡得着吗？"
        val doc=Subtitles.parse(text,"txt",60000)
        assertEquals(listOf("嗯...","嗯？ 嘻嘻... 没事呀...","你睡得着吗？"),doc.cues.map {it.text})
        assertEquals(20260L,doc.cues.first().endMs)
        assertEquals(doc.cues,Subtitles.parse(Subtitles.vtt(doc,60000),"vtt",60000).cues)
        assertEquals(doc.cues,Subtitles.parse(Subtitles.lrc(doc,60000),"lrc",60000).cues)
    }
    @Test fun numberedTxtBlocksWithoutBlankLinesKeepDialogueNumbers() {
        val text="1\n00:00:01.000 --> 00:00:03.000\n10点左右起床吧 7\n2\n00:00:04.000 --> 00:00:05.000\n123\n下一行"
        val doc=Subtitles.parse(text,"txt",10000)
        assertEquals(listOf(Cue(1000,3000,"10点左右起床吧 7"),Cue(4000,5000,"123\n下一行")),doc.cues)
    }
}
