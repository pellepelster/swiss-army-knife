package io.pelle.sak.dns

import org.xbill.DNS.AAAARecord
import org.xbill.DNS.ARecord
import org.xbill.DNS.DClass
import org.xbill.DNS.Message
import org.xbill.DNS.Name
import org.xbill.DNS.Rcode
import org.xbill.DNS.Record
import org.xbill.DNS.Section
import org.xbill.DNS.SimpleResolver
import org.xbill.DNS.TextParseException
import org.xbill.DNS.Type
import java.io.IOException
import java.net.InetAddress
import java.time.Duration

enum class AddressFamily(
    // the type of its records, a or aaaa
    internal val type: Int,
) {
    IPV4(Type.A),
    IPV6(Type.AAAA),
}

class DnsLookupException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

// a name server that is asked directly, e.g. a public resolver
interface NameServer {
    // how it is named in messages, e.g. its address
    val name: String

    // the addresses of the family the name has, empty when it has none or does not exist; throws
    // DnsLookupException when the server does not answer or fails
    fun addresses(
        domain: String,
        family: AddressFamily,
    ): List<InetAddress>
}

// asks the name server at the address, without a cache: every check sees what the server answers
// now. A cname is followed by the server, its answer holds the addresses at the end of the chain
class DnsjavaNameServer(
    address: String,
    timeout: Duration = DEFAULT_TIMEOUT,
    // another one than 53 only for a name server in a test
    port: Int = 53,
) : NameServer {
    override val name = address

    private val resolver =
        SimpleResolver(address).apply {
            this.timeout = timeout
            this.port = port
        }

    override fun addresses(
        domain: String,
        family: AddressFamily,
    ): List<InetAddress> {
        val query =
            try {
                Message.newQuery(Record.newRecord(Name.fromString(domain.trimEnd('.') + "."), family.type, DClass.IN))
            } catch (e: TextParseException) {
                throw DnsLookupException("'$domain' is no domain", e)
            }
        val response =
            try {
                resolver.send(query)
            } catch (e: IOException) {
                throw DnsLookupException("$name did not answer for $domain", e)
            }
        return when (response.rcode) {
            Rcode.NOERROR ->
                response.getSection(Section.ANSWER).mapNotNull {
                    when (it) {
                        is ARecord -> it.address
                        is AAAARecord -> it.address
                        else -> null
                    }
                }
            Rcode.NXDOMAIN -> emptyList()
            else -> throw DnsLookupException("$name answered ${Rcode.string(response.rcode)} for $domain")
        }
    }

    companion object {
        val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(3)
    }
}
