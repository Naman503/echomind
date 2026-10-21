package app.echomind.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Small, forgiving JSON helpers: model output is not always perfect JSON. */
object Js {
    val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true; explicitNulls = false }

    fun parseObject(text: String): JsonObject = json.parseToJsonElement(text) as JsonObject

    /**
     * Parses model output that should be a JSON object but may arrive wrapped
     * in code fences, with prose around it, trailing commas, or cut off at the
     * token limit (open strings and brackets are closed).
     */
    fun parseLenient(raw: String): JsonObject? {
        var t = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = t.indexOf('{')
        if (start < 0) return null
        t = t.substring(start)
        runCatching { return parseObject(t) }
        val end = t.lastIndexOf('}')
        if (end > 0) runCatching { return parseObject(t.substring(0, end + 1)) }
        runCatching { return parseObject(repair(t)) }
        return null
    }

    /** Closes whatever a truncated JSON document left open. */
    fun repair(t: String): String {
        val stack = ArrayDeque<Char>()
        var inString = false
        var escape = false
        val sb = StringBuilder()
        for (c in t) {
            sb.append(c)
            if (escape) { escape = false; continue }
            if (c == '\\') { escape = true; continue }
            if (c == '"') { inString = !inString; continue }
            if (inString) continue
            when (c) {
                '{' -> stack.addLast('}')
                '[' -> stack.addLast(']')
                '}', ']' -> if (stack.isNotEmpty()) stack.removeLast()
            }
        }
        if (inString) sb.append('"')
        var s = sb.toString().trimEnd()
        // Drop a dangling key or comma before closing.
        s = s.replace(Regex(",\\s*$"), "").replace(Regex(",\\s*\"[^\"]*\"\\s*:?\\s*$"), "").replace(Regex(":\\s*$"), ": null")
        while (stack.isNotEmpty()) s += stack.removeLast()
        return s.replace(Regex(",\\s*([}\\]])"), "$1")
    }

    /** Text of a Gemini generateContent response, skipping thought parts. */
    fun geminiText(r: JsonObject): String? {
        val parts = r.arr("candidates")?.firstOrNull()?.let { it as? JsonObject }?.obj("content")?.arr("parts") ?: return null
        return parts.mapNotNull { it as? JsonObject }
            .filter { it["thought"]?.let { t -> (t as? JsonPrimitive)?.booleanOrNull } != true }
            .mapNotNull { it.str("text") }
            .joinToString("")
            .ifEmpty { null }
    }
}

fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
fun JsonObject.dbl(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
fun JsonObject.int(key: String): Int? = long(key)?.toInt()
fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
fun JsonObject.strings(key: String): List<String> = arr(key)?.mapNotNull { it.str()?.trim()?.takeIf(String::isNotEmpty) }.orEmpty()
fun JsonObject.objects(key: String): List<JsonObject> = arr(key)?.mapNotNull { it as? JsonObject }.orEmpty()
fun JsonElement.str(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
fun JsonElement.dbl(): Double = (this as? JsonPrimitive)?.doubleOrNull ?: 0.0
@Suppress("unused") fun JsonElement.prim() = jsonPrimitive
