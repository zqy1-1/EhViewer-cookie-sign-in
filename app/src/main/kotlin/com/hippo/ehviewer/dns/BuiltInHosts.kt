package com.hippo.ehviewer.dns

import java.net.InetAddress

/**
 * Fallback address table for the hosts the app needs when the system resolver
 * is poisoned.
 *
 * These are the real addresses as returned by DoH (doh.pub / 1.12.12.12) and
 * they match the table EhViewer_CN_SXJ ships. They go stale as the CDN
 * rotates, and because this table is consulted before DNS-over-HTTPS a stale
 * entry is not healed automatically -- a dead address here fails the request
 * even when DoH would answer correctly. Refresh a host by resolving it over a
 * working DoH endpoint and checking the results accept a TLS handshake.
 */
object BuiltInHosts {
    private val table: Map<String, List<String>> = mapOf(
        // Cloudflare-fronted web hosts.
        "e-hentai.org" to listOf(
            "172.66.140.62",
            "172.66.132.196",
            "172.67.2.238",
        ),
        "repo.e-hentai.org" to listOf(
            "172.66.140.62",
            "172.66.132.196",
        ),
        "forums.e-hentai.org" to listOf(
            "172.66.132.196",
            "172.66.140.62",
        ),
        "exhentai.org" to listOf(
            "172.67.187.219",
            "104.21.56.202",
        ),
        // Origin servers. api. is required for the gallery metadata lookup
        // (fillGalleryListByApi) used by favorites and by restoring local
        // downloads, so leaving it out breaks those features.
        "api.e-hentai.org" to listOf(
            "95.211.79.44",
            "212.7.200.104",
            "37.48.81.204",
            "5.79.104.110",
        ),
        // Image host. The previous trio (89.39.106.43 / 109.236.85.28 /
        // 62.112.8.21) stopped completing a TLS handshake; these are the
        // addresses doh.pub answers with as of 2026-10-26 and each one was
        // checked to handshake on 443.
        "ehgt.org" to listOf(
            "62.112.8.152",
            "185.132.132.76",
            "185.132.132.79",
        ),
        "s.exhentai.org" to listOf(
            "178.175.132.22",
            "178.175.129.254",
            "178.175.128.254",
        ),
        "upld.e-hentai.org" to listOf(
            "89.149.221.236",
            "95.211.208.236",
        ),
        "upld.exhentai.org" to listOf(
            "178.175.132.22",
            "178.175.129.254",
            "178.175.128.254",
        ),
    )

    private val cache = HashMap<String, List<InetAddress>>()
    private val misses = HashSet<String>()

    /**
     * Returns the pinned addresses for [hostname], or null when the host is
     * not covered by the table or none of its entries parse. Results are
     * memoised, including misses, so a miss costs one set lookup.
     */
    fun lookup(hostname: String): List<InetAddress>? = synchronized(cache) {
        if (misses.contains(hostname)) return null
        cache[hostname]?.let { return it }

        val resolved = table[hostname].orEmpty().mapNotNull { literal ->
            runCatching { InetAddress.getByName(literal) }.getOrNull()
        }
        if (resolved.isEmpty()) {
            misses.add(hostname)
            return null
        }
        cache[hostname] = resolved
        return resolved
    }
}
