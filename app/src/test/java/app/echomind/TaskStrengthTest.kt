package app.echomind

import app.echomind.brain.Tasks
import app.echomind.brain.Tasks.Companion.Strength
import org.junit.Assert.assertEquals
import org.junit.Test

/** Real tasks EchoMind made from conversations in Oct–Nov 2026, and what should have happened to them. */
class TaskStrengthTest {
    private fun s(title: String, quote: String, owner: String = "me", time: Boolean = false, reminder: Boolean = false) = Tasks.strength(title, quote, owner, time, reminder)

    @Test fun chatIsNotATask() {
        assertEquals(Strength.DROP, s("Eat dinner", "me: Bhai khana."))
        assertEquals(Strength.DROP, s("Determine what is going on", "?: Chal raha hai."))
        assertEquals(Strength.DROP, s("Decide where to go", "Kahan Chalna."))
        assertEquals(Strength.DROP, s("Check flight status", "तो खली दिखा रहा है। 25 लिखा रहा है।"))
        assertEquals(Strength.DROP, s("Tell the person to think about it now", "तो उसको बोल देना अभी उसको ई थिंक लाइक है।"))
        assertEquals(Strength.DROP, s("Take medication for fever", "खाना खाओ और फिर सो जाओ सरा बुखार से हो जाएगा।"))
    }

    @Test fun realCommitmentsStay() {
        assertEquals(Strength.STRONG, s("Add text to speech functionality", "I want to add text to speech functionity as well. I will add that read aloud thing."))
        assertEquals(Strength.STRONG, s("Send the invoice to Priya", "Kal Priya ko invoice bhej dena hai yaar"))
        assertEquals(Strength.STRONG, s("Bring clothes from the terrace", "Remind me to bring the clothes from the terrace", reminder = true))
        assertEquals(Strength.STRONG, s("Call the CA about GST", "मुझे कल CA को GST के बारे में कॉल करना है", time = true))
    }

    @Test fun unclearOnesWaitForReview() {
        // Said, but not clearly a commitment of yours.
        assertEquals(Strength.WEAK, s("Update transcription sorting to latest first", "In the latest first order we can select that or change that in the settings, transcription sorting"))
        assertEquals(Strength.WEAK, s("Send the report", "He will send the report tomorrow", owner = "other"))
    }
}

class NotePointTest {
    @Test fun recordingTalkAndLineNumbersAreRemoved() {
        val g = app.echomind.brain.Grounding
        org.junit.Assert.assertNull(g.cleanPoint("Conversation occurred around 12:01 PM to 12:02 PM."))
        org.junit.Assert.assertNull(g.cleanPoint("Said at 5:48 PM"))
        org.junit.Assert.assertNull(g.cleanPoint("Speaker is experiencing a fever."))
        org.junit.Assert.assertNull(g.cleanPoint("for that would be around"))
        assertEquals("Planning the Diwali trip with travel and stay", g.cleanPoint("- Planning the Diwali trip with travel and stay (32)"))
        assertEquals("Took a pill for the headache; it helped for a while", g.cleanPoint("Took a pill for the headache; it helped for a while L12"))
    }
}

class DiaryStockTest {
    @Test fun stockSentencesGoRealOnesStay() {
        val d = "We went over the cleanup and the two tracks. Mandir sent a group message.\n\nIt was a short trip, maybe two kilometers, and the air felt different there. We settled on the two-day mountain plan. Tomorrow, the mountains will call, and I will prepare for that."
        val out = app.echomind.brain.Journal.dropStock(d)
        org.junit.Assert.assertFalse(out, out.contains("air felt"))
        org.junit.Assert.assertFalse(out, out.contains("mountains will call"))
        org.junit.Assert.assertTrue(out, out.contains("Mandir sent a group message.") && out.contains("two-day mountain plan"))
    }
}
