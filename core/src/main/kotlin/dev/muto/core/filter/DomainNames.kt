package dev.muto.core.filter

/** Normalisation and validation shared by the parser, the rule editor and the matcher. */
object DomainNames {

    private const val MAX_LENGTH = 253
    private const val MAX_LABEL_LENGTH = 63

    /**
     * Lower-cases [raw], strips a trailing root dot and a leading wildcard, and returns null if
     * what is left is not a plausible domain. Anything rejected here is a list line we skip or a
     * user rule we refuse, so it is deliberately strict.
     */
    fun normalize(raw: String): String? {
        var value = raw.trim().lowercase()
        if (value.isEmpty()) return null

        // "*.example.com" and "example.com" mean the same thing to a suffix matcher.
        if (value.startsWith("*.")) value = value.substring(2)
        if (value.endsWith(".")) value = value.dropLast(1)
        if (value.isEmpty() || value.length > MAX_LENGTH) return null

        // An IP literal in a hosts file is the address column, not a name to block.
        if (value.isIpLiteral()) return null

        var labelLength = 0
        for (i in value.indices) {
            val c = value[i]
            if (c == '.') {
                if (labelLength == 0) return null            // empty label: ".." or a leading dot
                if (value[i - 1] == '-') return null          // label may not end with a hyphen
                labelLength = 0
                continue
            }
            val ok = c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' ||
                c.code > 127 // unicode is accepted and folded by the caller's IDN handling
            if (!ok) return null
            if (labelLength == 0 && c == '-') return null     // label may not start with a hyphen
            if (++labelLength > MAX_LABEL_LENGTH) return null
        }
        if (labelLength == 0) return null
        if (value.last() == '-') return null
        // A single label is a host name like "localhost", never something worth filtering.
        if (!value.contains('.')) return null
        return value
    }

    /** True for values that are addresses rather than names. */
    fun String.isIpLiteral(): Boolean {
        if (contains(':')) return true // close enough for IPv6 in a hosts file
        val parts = split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            part.isNotEmpty() && part.length <= 3 && part.all { it in '0'..'9' }
        }
    }

    /** Every suffix of [host] that a rule could match, longest first. */
    fun suffixes(host: String): List<String> {
        val result = mutableListOf<String>()
        var from = 0
        while (true) {
            result.add(if (from == 0) host else host.substring(from))
            val dot = host.indexOf('.', from)
            if (dot < 0 || host.indexOf('.', dot + 1) < 0) return result
            from = dot + 1
        }
    }
}
