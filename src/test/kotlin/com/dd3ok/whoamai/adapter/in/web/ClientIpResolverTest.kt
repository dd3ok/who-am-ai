package com.dd3ok.whoamai.adapter.`in`.web

import com.dd3ok.whoamai.common.config.ChatAccessProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import java.net.InetAddress
import java.net.InetSocketAddress

class ClientIpResolverTest {

    private val resolver = ClientIpResolver(ChatAccessProperties(trustedProxyHops = 1))

    @Test
    fun `extracts True-Client-IP or CF-Connecting-IP when behind trusted internal or CGNAT proxy`() {
        for (proxyIp in listOf("127.0.0.1", "10.0.1.24", "172.16.4.2", "192.168.1.5", "100.64.12.8")) {
            val headers = HttpHeaders().apply {
                add("True-Client-IP", "203.0.113.10")
                add("X-Forwarded-For", "198.51.100.1, 203.0.113.10")
            }
            val remote = InetSocketAddress(InetAddress.getByName(proxyIp), 443)
            assertEquals("203.0.113.10", resolver.resolve(headers, remote), proxyIp)
        }
    }

    @Test
    fun `uses rightmost trusted hops from X-Forwarded-For to ignore client-spoofed left entries`() {
        val headers = HttpHeaders().apply {
            add("X-Forwarded-For", "8.8.8.8, 198.51.100.42")
        }
        val remote = InetSocketAddress(InetAddress.getByName("10.0.0.2"), 8080)
        assertEquals("198.51.100.42", resolver.resolve(headers, remote))

        val twoHopResolver = ClientIpResolver(ChatAccessProperties(trustedProxyHops = 2))
        val multiHopHeaders = HttpHeaders().apply {
            add("X-Forwarded-For", "8.8.8.8, 198.51.100.42, 172.70.10.2")
        }
        assertEquals("198.51.100.42", twoHopResolver.resolve(multiHopHeaders, remote))
    }

    @Test
    fun `ignores spoofed forwarding headers from direct public peers`() {
        val headers = HttpHeaders().apply {
            add("True-Client-IP", "8.8.8.8")
            add("CF-Connecting-IP", "1.1.1.1")
            add("X-Forwarded-For", "9.9.9.9")
        }
        val publicPeer = InetSocketAddress(InetAddress.getByName("203.0.113.99"), 51234)
        assertEquals("203.0.113.99", resolver.resolve(headers, publicPeer))
    }

    @Test
    fun `rejects non-numeric hostnames in forwarding headers without falling back to DNS`() {
        val headers = HttpHeaders().apply {
            add("True-Client-IP", "spoofed.invalid.example")
            add("X-Forwarded-For", "also.invalid.example, [2001:db8::1%eth0]")
        }
        val proxy = InetSocketAddress(InetAddress.getByName("10.0.0.5"), 8080)
        assertEquals("2001:db8:0:0:0:0:0:1", resolver.resolve(headers, proxy))
    }

    @Test
    fun `falls back to X-Forwarded-For when single-ip edge headers are disabled`() {
        val xffOnlyResolver = ClientIpResolver(
            ChatAccessProperties(trustedProxyHops = 1, trustSingleIpProxyHeaders = false)
        )
        val headers = HttpHeaders().apply {
            add("True-Client-IP", "8.8.8.8")
            add("CF-Connecting-IP", "1.1.1.1")
            add("X-Forwarded-For", "198.51.100.77")
        }
        val proxy = InetSocketAddress(InetAddress.getByName("10.0.0.5"), 8080)
        assertEquals("198.51.100.77", xffOnlyResolver.resolve(headers, proxy))
    }
}
