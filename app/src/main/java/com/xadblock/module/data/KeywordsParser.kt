package com.xadblock.module.data

/** One parsed rule specification. */
data class RuleSpec(val kind: String, val pattern: String)

object KeywordsParser {
    private const val REGEX = Contract.KIND_REGEX
    private const val LITERAL = Contract.KIND_LITERAL
    private const val ALL_OF = Contract.KIND_ALL_OF

    /** Parses a whole keyword text in the x-comment-blocker community format. */
    fun parseText(text: String): List<RuleSpec> {
        return text.lineSequence().mapNotNullTo(ArrayList()) { line -> parseLine(line) }
    }

    /** Parses a single logical line; returns null for comments/blank lines. */
    fun parseLine(raw: String): RuleSpec? {
        val line = stripInvisible(raw.trim().replace("\u200B", "").replace("\uFEFF", "").trim())
        if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) return null
        if (line.indexOf(Contract.ALL_OF_SEPARATOR) >= 0) return RuleSpec(ALL_OF, line)
        if (line.length >= 3 && line.startsWith("/")) {
            val lastSlash = line.lastIndexOf('/')
            if (lastSlash > 0) {
                val pattern = line.substring(1, lastSlash)
                val flags = line.substring(lastSlash + 1)
                if (flags.all { it in "imsu" } && pattern.isNotEmpty()) {
                    val inline = buildString {
                        if ('i' in flags) append('i')
                        if ('m' in flags) append('m')
                        if ('s' in flags) append('s')
                    }
                    return RuleSpec(REGEX, if (inline.isEmpty()) pattern else "(?$inline)$pattern")
                }
            }
        }
        return RuleSpec(LITERAL, line)
    }

    fun stripInvisible(value: String): String {
        val sb = StringBuilder(value.length)
        var offset = 0
        while (offset < value.length) {
            val codePoint = value.codePointAt(offset)
            offset += Character.charCount(codePoint)
            val type = Character.getType(codePoint)
            if (type == Character.FORMAT.toInt() || type == Character.CONTROL.toInt()) {
                continue
            }
            sb.appendCodePoint(codePoint)
        }
        return sb.toString()
    }
}
