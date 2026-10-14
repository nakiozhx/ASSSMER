package com.asmr.player

import org.junit.Assert.*
import org.junit.Test

class FloatingFramesTest {
    private val doc = SubtitleDocument(listOf(Cue(1000,3000,"第一句"),Cue(3000,5000,"第二句"),Cue(6000,8000,"第三句")),"",emptyList())
    @Test fun showsPreviousCurrentAndNext() {
        assertEquals(FloatingFrame(1,"第一句","第二句","第三句"),FloatingFrames.at(doc,3500))
    }
    @Test fun beforeFirstAndAfterLastDoNotInventCurrentText() {
        assertEquals(FloatingFrame(-1,"","","第一句"),FloatingFrames.at(doc,0))
        assertEquals(FloatingFrame(-1,"第三句","",""),FloatingFrames.at(doc,9000))
    }
    @Test fun subtitleGapClearsCurrentLine() {
        assertEquals(FloatingFrame(-1,"第二句","","第三句"),FloatingFrames.at(doc,5500))
    }
    @Test fun seekingBackwardsSelectsCorrectThreeLines() {
        assertEquals(2,FloatingFrames.at(doc,6500).index)
        assertEquals(FloatingFrame(0,"","第一句","第二句"),FloatingFrames.at(doc,1500))
    }
    @Test fun overlapShowsAllActiveText() {
        val overlap=SubtitleDocument(listOf(Cue(0,4000,"A"),Cue(1000,3000,"B"),Cue(5000,6000,"C")),"",emptyList())
        assertEquals(FloatingFrame(0,"","A\nB","C"),FloatingFrames.at(overlap,2000))
    }
    @Test fun noCuesGivesEmptyFrame() {
        assertEquals(FloatingFrame(-1,"","",""),FloatingFrames.at(SubtitleDocument(emptyList(),"",emptyList()),0))
    }
    @Test fun removesIndentationBlankLinesAndCjkPadding() {
        assertEquals("你好，世界。下一句",FloatingText.compact("  你 好，\t 世界。\n\n　 下一句  "))
        assertEquals("こんにちは世界",FloatingText.compact("こんにちは　 世界"))
    }
    @Test fun keepsEnglishWordBoundariesAndMixedText() {
        assertEquals("Good night my friend",FloatingText.compact("  Good\t night\n\nmy   friend  "))
        assertEquals("请听 ASMR test 10",FloatingText.compact("请听　 ASMR   test  10"))
    }
    @Test fun cleansAllThreeRowsWithoutChangingDocumentOrTiming() {
        val source=SubtitleDocument(listOf(Cue(1000,2000,"　 上 一句 "),Cue(2000,3000,"当前\n\n  句"),Cue(3000,4000," Next   line ")),"",emptyList())
        assertEquals(FloatingFrame(1,"上一句","当前句","Next line"),FloatingFrames.at(source,2500))
        assertEquals("当前\n\n  句",source.cues[1].text)
        assertEquals(2000L,source.cues[1].startMs)
        assertEquals(FloatingFrame(-1,"","","上一句"),FloatingFrames.at(source,500))
    }
    @Test fun unicodeWhitespaceDoesNotLeaveInvisiblePadding() {
        assertEquals("Hello world",FloatingText.compact("\uFEFFHello\u00A0\u200Bworld　"))
        assertEquals("",FloatingText.compact(" 　\n\t"))
    }
}
