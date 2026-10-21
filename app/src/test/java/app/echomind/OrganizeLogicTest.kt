package app.echomind

import app.echomind.ai.Schemas
import app.echomind.brain.Actions
import app.echomind.brain.Organizer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrganizeLogicTest {
    @Test fun `folder paths are cleaned and capped at three levels`() {
        assertEquals("Work/Eazeebox/Clients", Organizer.cleanFolder(" work / eazeebox//clients/ "))
        assertEquals("Home/Renovation", Organizer.cleanFolder("home > renovation"))
        assertEquals("A/B/C", Organizer.cleanFolder("a/b/c/d"))
        assertNull(Organizer.cleanFolder(" / "))
        assertNull(Organizer.cleanFolder(null))
    }

    @Test fun `instructions are told apart from questions`() {
        assertTrue(Actions.looksLikeInstruction("Move my insurance notes to Finance"))
        assertTrue(Actions.looksLikeInstruction("tag the goa trip note with travel"))
        assertTrue(Actions.looksLikeInstruction("Rahul is my manager"))
        assertTrue(Actions.looksLikeInstruction("Ankit works at Tata as a site engineer"))
        assertTrue(Actions.looksLikeInstruction("schedule a call with the CA on Friday at 4"))
        assertFalse(Actions.looksLikeInstruction("what did Priya say about the budget?"))
        assertFalse(Actions.looksLikeInstruction("when is the dentist"))
    }

    /** Every object in every schema closes its properties and requires all of them (strict mode). */
    @Test fun `new schemas are strict`() {
        fun check(o: JsonObject, path: String) {
            if ((o["type"] as? JsonPrimitive)?.content == "object") {
                val props = (o["properties"] as JsonObject).keys
                val req = (o["required"] as JsonArray).map { (it as JsonPrimitive).content }.toSet()
                assertEquals("required at $path", props, req)
                assertEquals("closed at $path", "false", (o["additionalProperties"] as JsonPrimitive).content)
                (o["properties"] as JsonObject).forEach { (k, v) -> check(v as JsonObject, "$path.$k") }
            }
            (o["items"] as? JsonObject)?.let { check(it, "$path[]") }
        }
        listOf(Schemas.extract, Schemas.person, Schemas.organize, Schemas.actions, Schemas.review, Schemas.journal).forEachIndexed { i, s -> check(s, "schema$i") }
    }
}
