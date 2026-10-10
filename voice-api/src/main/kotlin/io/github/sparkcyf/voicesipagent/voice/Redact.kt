package io.github.sparkcyf.voicesipagent.voice

/** Log-safe form of a phone number: only the last two digits survive. */
fun redactNumber(number: String?): String {
    if (number.isNullOrEmpty()) return "<none>"
    return if (number.length <= 2) "**" else "**" + number.takeLast(2)
}
