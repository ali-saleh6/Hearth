package com.sal7one.transiber.caption

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptionDeltaTest {
    @Test
    fun firstReadingIsPassedOnWhole() {
        assertEquals("And I say, \"OK.\"", CaptionDelta.newPart(null, "And I say, \"OK.\""))
    }

    @Test
    fun ocrVariantsOfTheSameSubtitleAreNotRepeated() {
        assertNull(CaptionDelta.newPart("If you want a career where you're a self-starter --",
            "If you want a career where you're a self-starter"))
    }

    @Test
    fun aScrolledLineOnlyAddsWhatIsNew() {
        assertEquals("there's also all kinds of things",
            CaptionDelta.newPart("something in the arts, something entrepreneurial -",
                "something in the arts, something entrepreneurial - there's also all kinds of things"))
    }

    @Test
    fun theTopLineScrollingOffIsNotSaidAgain() {
        assertEquals("and the plan was",
            CaptionDelta.newPart("I had a year to write it so the deadline was far off",
                "so the deadline was far off and the plan was"))
    }

    @Test
    fun oneSharedWordIsCoincidenceNotAScroll() {
        assertEquals("the end of everything", CaptionDelta.newPart("I thought it was the", "the end of everything"))
    }

    @Test
    fun aNewSubtitleIsPassedOnWhole() {
        assertEquals("But a week later I get a call,",
            CaptionDelta.newPart("I thought that was the end of everything.", "But a week later I get a call,"))
    }
}
