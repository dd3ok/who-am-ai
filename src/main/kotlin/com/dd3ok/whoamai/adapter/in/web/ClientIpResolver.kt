package com.dd3ok.whoamai.adapter.`in`.web

import com.dd3ok.whoamai.common.config.ChatAccessProperties
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress

@Component
class ClientIpResolver(
    private val properties: ChatAccessProperties = ChatAccessProperties()
) {
    fun resolve(headers: HttpHeaders, remoteAddress: InetSocketAddress?): String {
        val peerAddress = remoteAddress?.address
        val fallbackIp = peerAddress?.let(::canonicalAddress)
            ?: parseIpLiteral(remoteAddress?.hostString)
            ?: "unknown"

        val trustForwardedHeaders = properties.trustForwardedHeadersFromAllRemotes ||
            (peerAddress != null && isTrustedProxyAddress(peerAddress))
        if (!trustForwardedHeaders) {
            return fallbackIp
        }

        for (headerName in SINGLE_IP_PROXY_HEADERS) {
            val headerValue = headers.getFirst(headerName)
            val parsed = parseIpLiteral(headerValue)
            if (parsed != null) {
                return parsed
            }
        }

        val forwardedIps = headers.get("X-Forwarded-For")
            .orEmpty()
            .flatMap { it.split(',') }
            .mapNotNull(::parseIpLiteral)
        if (forwardedIps.isNotEmpty()) {
            val hops = properties.trustedProxyHops.coerceAtLeast(1)
            val targetIndex = (forwardedIps.size - hops).coerceAtLeast(0)
            return forwardedIps[targetIndex]
        }

        return fallbackIp
    }

    internal fun parseIpLiteral(raw: String?): String? {
        val trimmed = raw?.trim()
            ?.removeSurrounding("[", "]")
            ?.substringBefore('%')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        val validFormat = isValidIpv4Literal(trimmed) || isValidIpv6Literal(trimmed)
        if (!validFormat) return null

        return runCatching { canonicalAddress(InetAddress.getByName(trimmed)) }.getOrNull()
    }

    private fun isValidIpv4Literal(value: String): Boolean {
        val parts = value.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            part.length in 1..3 && part.all(Char::isDigit) && part.toIntOrNull() in 0..255
        }
    }

    private fun isValidIpv6Literal(value: String): Boolean =
        value.contains(':') && IPV6_LITERAL_REGEX.matches(value)

    private fun canonicalAddress(address: InetAddress): String =
        address.hostAddress.substringBefore('%')

    private fun isTrustedProxyAddress(address: InetAddress): Boolean =
        address.isLoopbackAddress ||
            address.isSiteLocalAddress ||
            address.isLinkLocalAddress ||
            isCarrierGradeNat(address) ||
            isUniqueLocalIpv6(address)

    private fun isCarrierGradeNat(address: InetAddress): Boolean {
        if (address !is Inet4Address) return false
        val bytes = address.address
        val first = bytes[0].toInt() and 0xFF
        val second = bytes[1].toInt() and 0xFF
        return first == 100 && (second and 0xC0) == 64
    }

    private fun isUniqueLocalIpv6(address: InetAddress): Boolean {
        if (address !is Inet6Address) return false
        val first = address.address[0].toInt() and 0xFF
        return (first and 0xFE) == 0xFC
    }

    companion object {
        private val SINGLE_IP_PROXY_HEADERS = listOf("True-Client-IP", "CF-Connecting-IP")
        private val IPV6_LITERAL_REGEX = Regex("^[0-9a-fA-F:.]+$")
    }
}
