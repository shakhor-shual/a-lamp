package com.ailamp.app

internal object AgentSyntaxHighlighter {
    data class Token(val start: Int, val end: Int, val color: Int)

    private const val PURPLE = 0xFF6940A2.toInt()
    private const val GREEN = 0xFF226B49.toInt()
    private const val BLUE = 0xFF235D9A.toInt()
    private const val ORANGE = 0xFF965019.toInt()
    private val colors = listOf(PURPLE, GREEN, BLUE, ORANGE)

    private val jsonString = """"(?:\\.|[^"\\\r\n])*""""
    private val json = Regex(
        """($jsonString)(?=\s*:)|($jsonString)|(-?\b(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?\b)|(\b(?:true|false|null)\b)"""
    )

    private val markdown = Regex(
        """(?m)(^ {0,3}#{1,6}(?=[ \t]|$)[^\r\n]*)|(^ {0,3}(?:`{3,}|~{3,})[^\r\n]*(?:\r?\n|$)(?s:.*?)(?:^ {0,3}(?:`{3,}|~{3,})[ \t]*$|\z)|`[^`\r\n]+`)|(\[[^\]\r\n]+\]\([^\r\n)]+\))|(^[ \t]*(?:[>+*-]|\d+[.)])(?=[ \t])|\*\*[^*\r\n]+\*\*|__[^_\r\n]+__|\*[^*\r\n]+\*|(?<!\w)_[^_\r\n]+_(?!\w))"""
    )

    fun tokens(text: String, isJson: Boolean): List<Token> =
        (if (isJson) json else markdown).findAll(text).map { match ->
            val group = colors.indices.first { match.groups[it + 1] != null }
            Token(match.range.first, match.range.last + 1, colors[group])
        }.toList()
}
