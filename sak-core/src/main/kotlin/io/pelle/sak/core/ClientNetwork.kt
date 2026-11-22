package io.pelle.sak.core

import java.net.InetAddress
import java.net.UnknownHostException

// the client as the limits count it: an IPv6 address by its /64, which one host has whole, an IPv4
// address by itself
fun clientNetwork(address: String): String {
    // only a literal with a colon is parsed, which never asks the dns
    if (':' !in address) return address
    val bytes =
        try {
            InetAddress.getByName(address).address
        } catch (_: UnknownHostException) {
            return address
        }
    // an IPv4 address written as IPv6 (::ffff:192.0.2.1)
    if (bytes.size == 4) return InetAddress.getByAddress(bytes).hostAddress
    return bytes.copyOf(8).toList().chunked(2).joinToString(":") { (high, low) -> "%x".format(((high.toInt() and 0xFF) shl 8) or (low.toInt() and 0xFF)) } + "::/64"
}
