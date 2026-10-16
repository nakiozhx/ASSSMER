package com.asmr.player

import org.junit.Assert.*
import org.junit.Test

class FloatingFramesTest {
    private val doc = SubtitleDocument(listOf(Cue(1000,3000,"第一句"),Cue(3000,5000,"第二句"),Cue(6000,8000,"第三句")),"",emptyList())
    @Test fun showsPreviousCurrentAndNext() {
        assertEquals(FloatingFrame(1,"第一句","第二句","第三句"),FloatingFrames.at(doc,3500))
    }
    @Test fun boundaryPreviewsAreNotHighlightedBeforeOrAfterTimeline() {
        assertEquals(FloatingFrame(0,"","第一句","第二句",false),FloatingFrames.at(doc,0))
        assertEquals(FloatingFrame(2,"第二句","第三句","",false),FloatingFrames.at(doc,9000))
    }
    @Test fun subtitleGapKeepsThreeLinesAndDoesNotHighlightUpcomingText() {
        assertEquals(FloatingFrame(1,"第一句","第二句","第三句",false),FloatingFrames.at(doc,5500))
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
        assertEquals(FloatingFrame(0,"","上一句","当前句",false),FloatingFrames.at(source,500))
    }
    @Test fun unicodeWhitespaceDoesNotLeaveInvisiblePadding() {
        assertEquals("Hello world",FloatingText.compact("\uFEFFHello\u00A0\u200Bworld　"))
        assertEquals("",FloatingText.compact(" 　\n\t"))
    }
    @Test fun longGapDoesNotCreateAnEmptyMiddleSlot() {
        val source=SubtitleDocument(listOf(Cue(1000,2000,"上一句"),Cue(3000,4000,"真是可爱啊..."),Cue(20000,21000,"好舒服..."),Cue(25000,26000,"第四句")),"",emptyList())
        assertEquals(FloatingFrame(1,"上一句","真是可爱啊...","好舒服...",false),FloatingFrames.at(source,12000))
        assertEquals(FloatingFrame(2,"真是可爱啊...","好舒服...","第四句"),FloatingFrames.at(source,20500))
    }
    @Test fun invisibleBlankCuesDoNotTakeUpContextSlots() {
        val source=SubtitleDocument(listOf(Cue(0,1000,"A"),Cue(1000,2000,"\u200B　"),Cue(3000,4000,"B"),Cue(5000,6000,"C")),"",emptyList())
        assertEquals(FloatingFrame(0,"","A","B",false),FloatingFrames.at(source,1500))
        assertEquals(FloatingFrame(1,"A","B","C"),FloatingFrames.at(source,3500))
        assertEquals(4,source.cues.size)
    }
    @Test fun floatingColorsHaveAutomaticFallbackAndPersistentChoiceKeys() {
        assertEquals(0xFFFFFFFF.toInt(),FloatingPalette.color("auto",true))
        assertEquals(0xFF18231C.toInt(),FloatingPalette.color("auto",false))
        assertEquals(FloatingPalette.color("auto",false),FloatingPalette.color("unknown",false))
        FloatingPalette.choices.keys.filter {it!="auto"}.forEach {
            assertEquals(FloatingPalette.color(it,true),FloatingPalette.color(it,false))
            assertEquals(255,FloatingPalette.color(it,true).ushr(24))
        }
    }
}
