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
}
