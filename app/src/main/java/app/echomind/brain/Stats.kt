package app.echomind.brain

import app.echomind.data.Db
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Numbers for the dashboard, computed from what was recorded (nothing is estimated). */
class Stats(private val db: Db) {
    data class Day(
        val date: LocalDate,
        val talkMin: Int, val meMin: Int, val othersMin: Int,
        /** Seconds of talk per named person (unknown voices together as "Others"). */
        val byPerson: List<Pair<String, Int>>,
        /** Minutes of talk per hour of the day: you, and everyone else. */
        val hourlyMe: IntArray, val hourlyOthers: IntArray,
        val conversations: Int, val calls: Int, val words: Int, val myWords: Int,
        val tasksMade: Int, val tasksDone: Int,
        val listenedMin: Int, val gapMin: Int,
        val songs: Int,
        /** Minutes per activity from the surroundings timeline. */
        val activities: List<Pair<String, Int>>,
        val productivity: Int?, val mood: Float?,
        val firstAt: Long?, val lastAt: Long?,
    ) {
        val meShare get() = if (meMin + othersMin == 0) 0 else meMin * 100 / (meMin + othersMin)
    }

    private val zone get() = ZoneId.systemDefault()

    suspend fun day(d: LocalDate): Day {
        val from = d.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val lines = db.utterances().range(from, to).filter { it.speaker != "Song" }
        val hourlyMe = IntArray(24); val hourlyOthers = IntArray(24)
        val bySpeaker = HashMap<String, Long>()
        var meMs = 0L; var otherMs = 0L; var myWords = 0
        for (u in lines) {
            val ms = (u.endAt - u.startAt).coerceIn(300, 60_000)
            val h = Instant.ofEpochMilli(u.startAt).atZone(zone).hour
            if (u.speaker == "me") { meMs += ms; hourlyMe[h] += (ms / 1000).toInt(); myWords += u.text.split(' ').size }
            else {
                otherMs += ms; hourlyOthers[h] += (ms / 1000).toInt()
                val who = u.speaker?.takeIf { it != "?" && !(it.startsWith("S") && it.length <= 3) } ?: "Others"
                bySpeaker[who] = (bySpeaker[who] ?: 0) + ms
            }
        }
        val convs = db.conversations().inRangeOnce(from, to).filter { it.state == "done" && !it.ignored }
        val audio = db.audio().overlapping(from, to).filter { it.source == "mic" }
        val gaps = db.gaps().inRangeOnce(from, to)
        val sounds = db.sounds().range(from, to)
        val entry = db.days().get(d.toString())
        return Day(
            date = d,
            talkMin = ((meMs + otherMs) / 60_000).toInt(), meMin = (meMs / 60_000).toInt(), othersMin = (otherMs / 60_000).toInt(),
            byPerson = bySpeaker.entries.sortedByDescending { it.value }.map { it.key to (it.value / 1000).toInt() }.filter { it.second >= 5 },
            hourlyMe = IntArray(24) { (hourlyMe[it] + 30) / 60 }, hourlyOthers = IntArray(24) { (hourlyOthers[it] + 30) / 60 },
            conversations = convs.size, calls = convs.count { it.source == "call" }, words = lines.sumOf { it.text.split(' ').size } , myWords = myWords,
            tasksMade = db.tasks().createdIn(from, to).size, tasksDone = db.tasks().completedIn(from, to).size,
            listenedMin = (audio.sumOf { minOf(it.endedAt, to) - maxOf(it.startedAt, from) } / 60_000).toInt(),
            gapMin = (gaps.sumOf { minOf(it.endAt, to) - maxOf(it.startAt, from) } / 60_000).toInt(),
            songs = sounds.count { it.kind == "song" },
            activities = sounds.filter { it.kind == "ambient" }.groupBy { it.label }.mapValues { e -> (e.value.sumOf { it.endAt - it.startAt } / 60_000).toInt() }
                .filter { it.value > 0 }.entries.sortedByDescending { it.value }.map { it.key to it.value },
            productivity = entry?.rating?.let { it * 20 } ?: entry?.productivity, mood = entry?.mood,
            firstAt = lines.minOfOrNull { it.startAt }, lastAt = lines.maxOfOrNull { it.endAt },
        )
    }

    suspend fun week(end: LocalDate): List<Day> = (6 downTo 0).map { day(end.minusDays(it.toLong())) }
}
