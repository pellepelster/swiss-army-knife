package io.pelle.sak.dns.model

enum class DnsState {
    // the records of each family are all addresses of the server
    OK,

    // there are no records of a family the server has addresses of
    MISSING,

    // a record is no address of the server, e.g. an aaaa record although the server has no ipv6
    MISMATCH,

    // the name server did not answer
    ERROR,
}
