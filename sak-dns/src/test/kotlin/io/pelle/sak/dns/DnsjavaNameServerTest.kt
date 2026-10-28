package io.pelle.sak.dns

import io.pelle.sak.dns.model.AddressFamily
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.xbill.DNS.AAAARecord
import org.xbill.DNS.ARecord
import org.xbill.DNS.CNAMERecord
import org.xbill.DNS.DClass
import org.xbill.DNS.Flags
import org.xbill.DNS.Message
import org.xbill.DNS.Name
import org.xbill.DNS.Rcode
import org.xbill.DNS.Section
import org.xbill.DNS.Type
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.time.Duration
import kotlin.concurrent.thread

class DnsjavaNameServerTest {
    // a name server on a local udp port: site.example.org has an a and an aaaa record, www its
    // cname, broken.example.org fails, every other name does not exist
    private val socket = DatagramSocket(0, InetAddress.getLoopbackAddress())

    private val server =
        thread(isDaemon = true) {
            val buffer = ByteArray(512)
            while (!socket.isClosed) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: java.net.SocketException) {
                    break
                }
                val query = Message(packet.data.copyOf(packet.length))
                val question = query.question
                val response = Message(query.header.id).apply { header.setFlag(Flags.QR.toInt()); addRecord(question, Section.QUESTION) }
                val site = Name.fromString("site.example.org.")
                when (question.name.toString()) {
                    "site.example.org.", "www.example.org." -> {
                        if (question.name.toString() == "www.example.org.") {
                            response.addRecord(CNAMERecord(question.name, DClass.IN, 60, site), Section.ANSWER)
                        }
                        when (question.type) {
                            Type.A -> response.addRecord(ARecord(site, DClass.IN, 60, InetAddress.ofLiteral("203.0.113.1")), Section.ANSWER)
                            Type.AAAA -> response.addRecord(AAAARecord(site, DClass.IN, 60, InetAddress.ofLiteral("2001:db8::1")), Section.ANSWER)
                        }
                    }
                    "broken.example.org." -> response.header.rcode = Rcode.SERVFAIL
                    else -> response.header.rcode = Rcode.NXDOMAIN
                }
                val bytes = response.toWire()
                socket.send(DatagramPacket(bytes, bytes.size, packet.socketAddress))
            }
        }

    private val nameServer = DnsjavaNameServer("127.0.0.1", Duration.ofSeconds(2), socket.localPort)

    @AfterEach
    fun stop() {
        socket.close()
        server.join(1000)
    }

    @Test
    fun `reads the addresses of each family, through a cname as well`() {
        nameServer.addresses("site.example.org", AddressFamily.IPV4) shouldBe listOf(InetAddress.ofLiteral("203.0.113.1"))
        nameServer.addresses("site.example.org", AddressFamily.IPV6) shouldBe listOf(InetAddress.ofLiteral("2001:db8::1"))
        nameServer.addresses("www.example.org.", AddressFamily.IPV4) shouldBe listOf(InetAddress.ofLiteral("203.0.113.1"))
    }

    @Test
    fun `a name that does not exist has no addresses, a failing server throws`() {
        nameServer.addresses("unknown.example.org", AddressFamily.IPV4) shouldBe emptyList()
        shouldThrow<DnsLookupException> { nameServer.addresses("broken.example.org", AddressFamily.IPV4) }
    }

    @Test
    fun `a server that does not answer throws`() {
        val silent = DatagramSocket(0, InetAddress.getLoopbackAddress())
        try {
            val nobody = DnsjavaNameServer("127.0.0.1", Duration.ofMillis(300), silent.localPort)
            shouldThrow<DnsLookupException> { nobody.addresses("site.example.org", AddressFamily.IPV4) }
        } finally {
            silent.close()
        }
    }
}
