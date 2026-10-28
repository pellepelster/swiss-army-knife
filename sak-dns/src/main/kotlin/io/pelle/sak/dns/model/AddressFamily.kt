package io.pelle.sak.dns.model

import org.xbill.DNS.Type

enum class AddressFamily(
    // the type of its records, a or aaaa
    internal val type: Int,
) {
    IPV4(Type.A),
    IPV6(Type.AAAA),
}
