package app.echomind

import app.echomind.brain.Grounding
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroundingTest {
    private val part = Grounding.Part(listOf(
        "L4 [4:05 PM] me: kal Rahul ko call karna hai invoice ke baare mein",
        "L5 [4:06 PM] S1: haan, aur cab book kar dena Bel Road ke liye",
    ))

    private fun obj(s: String) = Json.parseToJsonElement(s) as JsonObject
    private fun size(o: JsonObject, k: String) = (o[k] as JsonArray).size

    @Test fun keepsBackedItemsDropsInvented() {
        val o = obj("""{
          "tasks": [
            {"title": "Call Rahul about the invoice", "quote": "kal Rahul ko call karna hai", "lines": [4]},
            {"title": "Book flight to Goa", "quote": "flight book karo Goa ke liye", "lines": [9]},
            {"title": "Book a cab to Bel Road", "quote": "", "lines": [5]}
          ],
          "people": [{"name": "Rahul"}, {"name": "Priya"}],
          "facts": [{"statement": "x", "lines": [4]}, {"statement": "y", "lines": [12]}]
        }""")
        val r = Grounding.check(o, part)
        assertEquals(2, size(r, "tasks"))
        assertEquals(1, size(r, "people"))
        assertEquals(1, size(r, "facts"))
    }

    @Test fun nameAcrossScripts() {
        assertTrue(Grounding.nameIn("Rahul", Grounding.Part(listOf("L1 [1:00 PM] me: राहुल आ गया"))))
        assertFalse(Grounding.nameIn("Priya", part))
    }

    @Test fun coachQuotesMustBeReal() {
        assertTrue(Grounding.ownerSaid("So I have this things in plant", "So, I have this things in plant."))
        assertFalse(Grounding.ownerSaid("I am working on some projects", "Hello there. Working on stops."))
    }
}
