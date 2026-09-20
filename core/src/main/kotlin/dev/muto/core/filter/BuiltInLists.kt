package dev.muto.core.filter

/**
 * The block lists Muto ships with. Nothing here is downloaded until the user subscribes, and the
 * defaults are deliberately conservative: two well-maintained lists that between them cover most
 * advertising and telemetry without the breakage that comes from stacking everything at once.
 */
object BuiltInLists {

    enum class Category {
        /** Advertising and the tracking that funds it. */
        ADS,

        /** Analytics and telemetry endpoints. */
        TRACKING,

        /** Known malware, phishing and scam hosts. */
        SECURITY,

        /**
         * Hosts an app uses to reach a hard-coded DNS-over-HTTPS resolver. Blocking these pushes
         * the app back onto the system resolver, where Muto can see it - see docs/HOW-IT-WORKS.md.
         */
        BYPASS_PREVENTION,
    }

    data class Source(
        val id: String,
        val title: String,
        val description: String,
        val url: String,
        val category: Category,
        val enabledByDefault: Boolean,
        /** Rough entry count, shown before a first download so the size is not a surprise. */
        val approximateEntries: Int,
    )

    val ALL: List<Source> = listOf(
        Source(
            id = "stevenblack-unified",
            title = "StevenBlack unified hosts",
            description = "The widely used baseline: advertising and tracker hosts, amalgamated " +
                "from several upstream lists. A good first subscription.",
            url = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
            category = Category.ADS,
            enabledByDefault = true,
            approximateEntries = 160_000,
        ),
        Source(
            id = "adguard-dns",
            title = "AdGuard DNS filter",
            description = "AdGuard's own list, tuned for DNS filtering rather than browsers. " +
                "Catches a lot of in-app advertising that hosts files miss.",
            url = "https://adguardteam.github.io/HostlistsRegistry/assets/filter_1.txt",
            category = Category.ADS,
            enabledByDefault = true,
            approximateEntries = 55_000,
        ),
        Source(
            id = "adaway",
            title = "AdAway mobile hosts",
            description = "Small, focused list of ad servers used by mobile apps. Very low " +
                "breakage risk.",
            url = "https://adaway.org/hosts.txt",
            category = Category.ADS,
            enabledByDefault = false,
            approximateEntries = 7_000,
        ),
        Source(
            id = "yoyo-adservers",
            title = "Peter Lowe's ad and tracking servers",
            description = "Long-running hand-curated list of ad and tracking servers.",
            url = "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=0&mimetype=plaintext",
            category = Category.ADS,
            enabledByDefault = false,
            approximateEntries = 3_500,
        ),
        Source(
            id = "easyprivacy",
            title = "EasyPrivacy",
            description = "Tracking and analytics endpoints. Written for browsers, so only the " +
                "whole-domain rules in it apply here.",
            url = "https://easylist.to/easylist/easyprivacy.txt",
            category = Category.TRACKING,
            enabledByDefault = false,
            approximateEntries = 20_000,
        ),
        Source(
            id = "hagezi-multi-pro",
            title = "HaGeZi Multi PRO",
            description = "Aggressive combined ad, tracking and telemetry list. Blocks more, and " +
                "breaks more - expect to add allow rules.",
            url = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/hosts/pro.txt",
            category = Category.TRACKING,
            enabledByDefault = false,
            approximateEntries = 200_000,
        ),
        Source(
            id = "urlhaus-malicious",
            title = "URLhaus malicious hosts",
            description = "Hosts distributing malware, from abuse.ch. Worth having on even if " +
                "you do not care about ads.",
            url = "https://urlhaus.abuse.ch/downloads/hostfile/",
            category = Category.SECURITY,
            enabledByDefault = false,
            approximateEntries = 5_000,
        ),
        Source(
            id = "hagezi-doh-bypass",
            title = "Encrypted-DNS bypass blocking",
            description = "Blocks the hosts apps use to reach their own DNS-over-HTTPS resolvers. " +
                "Without this, an app can route around Muto entirely - but it will also stop " +
                "private DNS working in other apps you may want it in.",
            url = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/hosts/doh.txt",
            category = Category.BYPASS_PREVENTION,
            enabledByDefault = false,
            approximateEntries = 1_000,
        ),
    )

    val DEFAULT_ENABLED: List<Source> get() = ALL.filter { it.enabledByDefault }

    fun byId(id: String): Source? = ALL.firstOrNull { it.id == id }
}

/** Upstream resolvers offered in settings. "System" means whatever the active network hands out. */
object UpstreamResolvers {

    data class Resolver(
        val id: String,
        val title: String,
        val description: String,
        val ipv4: List<String>,
        val ipv6: List<String>,
    )

    const val SYSTEM_ID = "system"

    val ALL: List<Resolver> = listOf(
        Resolver(
            id = SYSTEM_ID,
            title = "System default",
            description = "Use whatever resolver the current network provides. Follows you " +
                "between Wi-Fi and mobile data.",
            ipv4 = emptyList(),
            ipv6 = emptyList(),
        ),
        Resolver(
            id = "cloudflare",
            title = "Cloudflare",
            description = "1.1.1.1. Fast, does not log queries to disk.",
            ipv4 = listOf("1.1.1.1", "1.0.0.1"),
            ipv6 = listOf("2606:4700:4700::1111", "2606:4700:4700::1001"),
        ),
        Resolver(
            id = "quad9",
            title = "Quad9",
            description = "9.9.9.9. Filters known-malicious domains upstream as well.",
            ipv4 = listOf("9.9.9.9", "149.112.112.112"),
            ipv6 = listOf("2620:fe::fe", "2620:fe::9"),
        ),
        Resolver(
            id = "google",
            title = "Google Public DNS",
            description = "8.8.8.8. Widely reachable, useful when a network blocks the others.",
            ipv4 = listOf("8.8.8.8", "8.8.4.4"),
            ipv6 = listOf("2001:4860:4860::8888", "2001:4860:4860::8844"),
        ),
    )

    fun byId(id: String): Resolver? = ALL.firstOrNull { it.id == id }
}
