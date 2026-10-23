package app.echomind

import app.echomind.brain.Memory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JunkNameTest {
    @Test fun labelsAndPromptWordsAreJunk() {
        listOf("S13", "s2", "Speaker 3", "Owner", "me", "Current Date/Time", "L12").forEach { assertTrue(it, Memory.isJunkName(it)) }
    }

    @Test fun realNamesAreKept() {
        listOf("Rahul", "Priya Sharma", "Eazeebox", "Sam", "Dr. Mehta").forEach { assertFalse(it, Memory.isJunkName(it)) }
    }
}
