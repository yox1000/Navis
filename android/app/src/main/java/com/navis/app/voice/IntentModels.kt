package com.navis.app.voice

import com.navis.app.util.NavisLog
import org.json.JSONObject

data class NeuralSeekIntent(
    val intent: Int,
    val entity: String
) {
    val friendlyDescription: String
        get() = when (intent) {
            1 -> if (entity.isNotBlank()) "Navigating to $entity" else "Navigating to destination"
            2 -> "Checking if you are on route"
            3 -> if (entity.isNotBlank()) "Looking for nearby $entity" else "Exploring nearby places"
            4 -> "I didn’t hear a navigation request, but I’m right here whenever you’d like directions."
            else -> "Unknown intent"
        }
}

private const val PARSER_TAG = "NavisIntentParser"
private val fencedJsonRegex = Regex(
    pattern = """```(?:json)?\s*([\s\S]*?)```""",
    option = RegexOption.IGNORE_CASE
)

fun parseNeuralSeekIntent(rawSnippet: String): Result<NeuralSeekIntent> = runCatching {
    val trimmed = rawSnippet.trim()
    NavisLog.d(PARSER_TAG, "Raw NeuralSeek snippet=$trimmed")
    val defenced = extractFencePayload(trimmed)
    val unquoted = defenced.removeSurrounding("\"").trim()
    val candidate = when {
        looksLikeJsonObject(unquoted) -> unquoted
        else -> extractFirstJsonObject(unquoted)
            ?: throw IllegalStateException("No JSON object found in intent snippet")
    }

    NavisLog.d(PARSER_TAG, "Sanitized intent JSON=$candidate")
    val json = JSONObject(candidate)
    val intentValue = json.optInt("intent", -1)
    if (intentValue == -1) {
        throw IllegalStateException("Intent missing in snippet: $candidate")
    }
    val entityValue = json.optString("entity", "")
    NeuralSeekIntent(intentValue, entityValue)
}

private fun extractFencePayload(text: String): String {
    val match = fencedJsonRegex.find(text)
    return when {
        match != null -> match.groupValues[1]
        text.startsWith("```") -> text.trim('`', ' ', '\n', '\r', '\t')
        else -> text
    }
}

private fun looksLikeJsonObject(text: String): Boolean =
    text.startsWith("{") && text.endsWith("}")

private fun extractFirstJsonObject(text: String): String? {
    var depth = 0
    var inQuotes = false
    var escaped = false
    var startIndex = -1
    text.forEachIndexed { index, char ->
        if (escaped) {
            escaped = false
            return@forEachIndexed
        }
        when (char) {
            '\\' -> {
                if (inQuotes) {
                    escaped = true
                }
            }
            '"' -> {
                inQuotes = !inQuotes
            }
        }
        if (inQuotes) return@forEachIndexed

        if (char == '{') {
            if (depth == 0) {
                startIndex = index
            }
            depth++
        } else if (char == '}') {
            depth--
            if (depth == 0 && startIndex != -1) {
                return text.substring(startIndex, index + 1)
            }
        }
    }
    return null
}
