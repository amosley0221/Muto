package dev.muto.core.filter

import dev.muto.core.filter.DomainNames.isIpLiteral

/**
 * Reads the list formats people actually publish.
 *
 * Between them, Steven Black, AdGuard, EasyList and the various "just a list of domains" files
 * cover hosts-file syntax, plain domain lines and a small corner of Adblock Plus syntax. Anything
 * ABP can express that DNS cannot - path matching, element hiding, request-type options - is
 * skipped rather than approximated, because a rule that silently means something different from
 * what its author wrote is worse than a rule that is ignored.
 */
object BlocklistParser {

    /** Names that appear in the hosts-file boilerplate and must never become filter entries. */
    private val HOSTS_BOILERPLATE = setOf(
        "localhost",
        "localhost.localdomain",
        "local",
        "broadcasthost",
        "ip6-localhost",
        "ip6-loopback",
        "ip6-localnet",
        "ip6-mcastprefix",
        "ip6-allnodes",
        "ip6-allrouters",
        "ip6-allhosts",
    )

    /** Domains collected from one source, split by whether the rule blocks or exempts. */
    data class Result(
        val blocked: List<String>,
        val allowed: List<String>,
        val linesRead: Int,
        val linesSkipped: Int,
    )

    fun parse(lines: Sequence<String>): Result {
        val blocked = ArrayList<String>(4096)
        val allowed = ArrayList<String>(64)
        var read = 0
        var skipped = 0

        for (rawLine in lines) {
            val line = stripComment(rawLine).trim()
            // Blank lines and comments are not "read" for reporting purposes - a source whose
            // header is half licence text should not look like it was mostly skipped.
            if (line.isEmpty()) continue
            read++

            val entries = parseLine(line)
            if (entries == null) {
                skipped++
                continue
            }
            var added = false
            for (entry in entries) {
                if (entry.allow) allowed.add(entry.domain) else blocked.add(entry.domain)
                added = true
            }
            if (!added) skipped++
        }
        return Result(blocked, allowed, read, skipped)
    }

    fun parse(text: String): Result = parse(text.lineSequence())

    private data class Entry(val domain: String, val allow: Boolean)

    /** Returns the domains a single line contributes, or null if the line is not usable. */
    private fun parseLine(line: String): List<Entry>? = when {
        line.startsWith("@@") -> parseAdblockRule(line.substring(2), allow = true)?.let(::listOf)
        line.startsWith("||") -> parseAdblockRule(line, allow = false)?.let(::listOf)
        // Element-hiding and scriptlet rules are a browser concern; DNS cannot act on them.
        line.contains("##") || line.contains("#@#") || line.contains("#?#") -> null
        else -> parseHostsOrDomainLine(line)
    }

    /**
     * Handles the `||domain^` family. Options after `$` are only honoured when they do not change
     * what the rule matches; `$third-party`, `$image` and friends make the rule narrower than DNS
     * can express, so those are dropped.
     */
    private fun parseAdblockRule(rule: String, allow: Boolean): Entry? {
        var body = rule
        if (body.startsWith("||")) body = body.substring(2) else if (allow) {
            // An exception written as "@@example.com" without anchors still reads as a domain.
            if (body.startsWith("||")) body = body.substring(2)
        } else {
            return null
        }

        val optionsAt = body.indexOf('$')
        if (optionsAt >= 0) {
            val options = body.substring(optionsAt + 1)
            body = body.substring(0, optionsAt)
            if (!optionsAreDnsSafe(options)) return null
        }

        // "^" is a separator anchor; at the end of a domain rule it adds nothing for DNS.
        body = body.trimEnd('^', '|')

        // A path, a scheme, or a wildcard in the middle means this is not a whole-domain rule.
        if (body.contains('/') || body.contains('*') || body.contains('?') || body.contains(':')) return null

        val domain = DomainNames.normalize(body) ?: return null
        return Entry(domain, allow)
    }

    /**
     * ABP options that leave the rule equivalent to "block this domain". `important` and `all` do
     * not narrow the match, and `document`/`popup` are close enough for a DNS filter. Anything
     * else - request types, party rules, `domain=` scoping - would make the rule conditional.
     */
    private fun optionsAreDnsSafe(options: String): Boolean =
        options.split(',').all { it.trim().lowercase() in DNS_SAFE_OPTIONS }

    private val DNS_SAFE_OPTIONS = setOf("", "all", "important", "document", "doc", "popup")

    /**
     * Handles both `0.0.0.0 ads.example.com` and a bare `ads.example.com`. Hosts files sometimes
     * map several names on one line, so every column after the address is taken.
     */
    private fun parseHostsOrDomainLine(line: String): List<Entry>? {
        val fields = line.split(' ', '\t').filter { it.isNotEmpty() }
        if (fields.isEmpty()) return null

        val names = if (fields.size > 1 && fields[0].isIpLiteral()) {
            // A hosts line whose address column is a real address is a redirect someone wants to
            // keep - "10.0.0.5 intranet.example.com" - not a block. Only the sinkholes mean block.
            if (!isSinkhole(fields[0])) return null
            fields.drop(1)
        } else {
            fields
        }
        val entries = names.mapNotNull { name ->
            if (name.lowercase() in HOSTS_BOILERPLATE) return@mapNotNull null
            DomainNames.normalize(name)?.let { Entry(it, allow = false) }
        }
        return entries.ifEmpty { null }
    }

    /**
     * The addresses a hosts file uses to mean "send this nowhere". Anything else in the address
     * column is a genuine redirect and leaves the line alone.
     */
    private fun isSinkhole(field: String): Boolean = field in SINKHOLE_ADDRESSES

    private val SINKHOLE_ADDRESSES = setOf("0.0.0.0", "127.0.0.1", "::", "::0", "::1")

    private fun stripComment(line: String): String {
        var end = line.length
        for (i in line.indices) {
            val c = line[i]
            if (c == '#' || c == '!') {
                // "##" is an element-hiding separator, not a comment; leave it for parseLine.
                if (c == '#' && i + 1 < line.length && (line[i + 1] == '#' || line[i + 1] == '@' || line[i + 1] == '?')) {
                    return line
                }
                end = i
                break
            }
        }
        return line.substring(0, end)
    }
}
