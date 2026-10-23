package app.echomind

import app.echomind.brain.Dedup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DedupTest {
    @Test fun sameIdeaDifferentWords() {
        assertTrue(Dedup.same("Add task visibility for all tasks (including done tasks)", "Add task visibility for all tasks"))
        assertTrue(Dedup.same("Call Rahul tomorrow.", "call rahul"))
        assertFalse(Dedup.same("Call Rahul", "Email Priya about the invoice"))
    }

    @Test fun keepsFirstOfEachGroup() {
        val l = listOf("Book the cab to Bel Road", "Pay the electricity bill", "book cab bel road", "Pay electricity bill today")
        assertEquals(listOf("Book the cab to Bel Road", "Pay the electricity bill"), Dedup.strings(l))
    }

    @Test fun withoutWhatWasSaid() {
        assertEquals(listOf("Buy milk"), Dedup.without(listOf("Buy milk", "Call mom at 6"), listOf("Call mom")) { it })
    }
}
