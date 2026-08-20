package com.alad1nks.jaiqal.config

import java.net.InetAddress

class TrustedProxyCidr private constructor(
    private val network: ByteArray,
    private val prefixLength: Int,
    private val notation: String,
) {
    fun containsLiteral(address: String): Boolean {
        val candidate = parseIpLiteral(address) ?: return false
        if (candidate.size != network.size) return false

        val completeBytes = prefixLength / 8
        for (index in 0 until completeBytes) {
            if (candidate[index] != network[index]) return false
        }

        val remainingBits = prefixLength % 8
        if (remainingBits == 0) return true
        val mask = (0xff shl (8 - remainingBits)) and 0xff
        return (candidate[completeBytes].toInt() and mask) ==
            (network[completeBytes].toInt() and mask)
    }

    override fun toString(): String = notation

    companion object {
        fun parse(value: String): TrustedProxyCidr {
            require(value == value.trim() && value.count { it == '/' } == 1) {
                "TRUSTED_PROXY_CIDRS entries must be IP address CIDRs"
            }
            val (addressText, prefixText) = value.split('/', limit = 2)
            val address = parseIpLiteral(addressText)
                ?: throw IllegalArgumentException("TRUSTED_PROXY_CIDRS entries must use literal IPv4 or IPv6 addresses")
            val prefix = prefixText.toIntOrNull()
                ?: throw IllegalArgumentException("TRUSTED_PROXY_CIDRS prefix must be an integer")
            val maximumPrefix = address.size * 8
            require(prefix in 0..maximumPrefix) {
                "TRUSTED_PROXY_CIDRS prefix must be between 0 and $maximumPrefix"
            }
            require(prefix > 0) {
                "TRUSTED_PROXY_CIDRS must not contain an all-addresses network"
            }

            val normalized = address.copyOf()
            val completeBytes = prefix / 8
            val remainingBits = prefix % 8
            if (remainingBits > 0) {
                val mask = (0xff shl (8 - remainingBits)) and 0xff
                normalized[completeBytes] = (normalized[completeBytes].toInt() and mask).toByte()
            }
            for (index in (completeBytes + if (remainingBits > 0) 1 else 0) until normalized.size) {
                normalized[index] = 0
            }
            require(normalized.contentEquals(address)) {
                "TRUSTED_PROXY_CIDRS entries must use the canonical network address"
            }

            return TrustedProxyCidr(normalized, prefix, value)
        }

        private fun parseIpLiteral(value: String): ByteArray? {
            if (value.isEmpty()) return null
            if (value.count { it == '.' } == 3 && value.none { it == ':' }) {
                val parts = value.split('.')
                if (parts.size != 4) return null
                return parts.map { part ->
                    if (part.isEmpty() || part.any { !it.isDigit() }) return null
                    part.toIntOrNull()?.takeIf { it in 0..255 }?.toByte() ?: return null
                }.toByteArray()
            }

            if (!value.contains(':') || value.any { it !in "0123456789abcdefABCDEF:" }) return null
            return runCatching { InetAddress.getByName(value).address }
                .getOrNull()
                ?.takeIf { it.size == 16 }
        }
    }
}
