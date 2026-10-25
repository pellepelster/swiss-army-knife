package io.pelle.sak.krawall.discovery

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContainIgnoringCase
import io.pelle.sak.browser.assumeOpenRouterCredentials
import io.pelle.sak.browser.lightpandaBinary
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** discovers addresses on live websites, needs OPENROUTER_API_KEY and a lightpanda binary (see [lightpandaBinary]) */
class AddressDiscoveryIntegrationTest {
    @Test
    fun `discovers the address of the Knust in Hamburg`() {
        assumeOpenRouterCredentials()

        val address =
            BrowserDiscovery("https://knusthamburg.de/", lightpandaBinary()).use {
                it.discovery(AddressDiscovery)
            }
        println(address)

        address.city!! shouldContainIgnoringCase "Hamburg"
        address.street!! shouldContainIgnoringCase "Neuer Kamp"
        address.postalCode shouldBe "20357"
    }

    @Test
    fun `discovers and verifies the address of the Knust`() {
        assumeOpenRouterCredentials()

        val result =
            BrowserDiscovery("https://knusthamburg.de/", lightpandaBinary()).use {
                it.verifiedDiscovery(AddressDiscovery)
            }
        println("${result.value} from ${result.sources}")
        result.fields.forEach { println("%-14s %-36s %.3f".format(it.field, it.value, it.probability)) }

        result.verification.verified shouldBe true
    }

    // both are run by the same company, the imprints name its address (Schmuckstraße 15), not the one of the venue
    @ParameterizedTest
    @CsvSource(
        "https://grossefreiheit36.de/, Große Freiheit, 36, 22767",
        "https://www.docks.de/, Spielbudenpl, 19, 20359",
    )
    fun `discovers and verifies the address of a venue, not of its operator`(
        url: String,
        street: String,
        houseNumber: String,
        postalCode: String,
    ) = assertVerifiedAddress(url, street, houseNumber, postalCode)

    // the street is a prefix, so both "Barnerstr." and "Barnerstraße" match
    @ParameterizedTest
    @CsvSource(
        "https://www.elbphilharmonie.de/de/, Platz der Deutschen Einheit, 1, 20457",
        "https://www.elbphilharmonie.de/de/laeiszhalle, Dammtorwall, 46, 20355",
        "https://www.barclays-arena.de/, Sylvesterallee, 10, 22525",
        "https://www.sporthalle.hamburg/, Krochmannstr, 55, 22297",
        "https://www.stadtparkopenair.de/, Saarlandstr, 73, 22303",
        "https://kampnagel.de/, Jarrestr, 20, 22303",
        "https://markthalle-hamburg.de/, Klosterwall, 11, 20095",
        "https://fabrik.de/, Barnerstr, 36, 22765",
        "https://gruenspan.de/, Große Freiheit, 58, 22767",
        "https://www.mojo.de/, Reeperbahn, 1, 20359",
        "https://uebelundgefaehrlich.com/, Feldstr, 66, 20359",
        "https://knusthamburg.de/, Neuer Kamp, 30, 20357",
        "https://molotowclub.com/, Reeperbahn, 136, 20359",
        "https://www.logohamburg.de/, Grindelallee, 5, 20146",
        "https://indramusikclub.de/, Große Freiheit, 64, 22767",
        "https://www.hafenklang.com/, Große Elbstr, 84, 22767",
        "https://nochtspeicher.de/, Bernhard-Nocht-Str, 69a, 20359",
        "https://bahnhofpauli.de/, Spielbudenpl, 21-22, 20359",
        "https://www.haekken.de/, Spielbudenpl, 21-22, 20359",
        "https://golem.cc/, Große Elbstr, 14, 22767",
        "https://www.freundlichundkompetent.de/, Hamburger Str, 13, 22083",
        "https://headcrash.de/, Hamburger Berg, 13, 20359",
        "https://www.cotton-club.de/, Alter Steinweg, 10, 20459",
        "https://stellwerk-hamburg.de/, Hannoversche Str, 85, 21079",
        "https://lola-hh.de/, Lohbrügger Landstr, 8, 21031",
        "https://honigfabrik.de/, Industriestr, 125-131, 21107",
        "https://www.goldbekhaus.de/, Moorfuhrtweg, 9, 22301",
        "https://zinnschmelze.de/, Maurienstr, 19, 22305",
    )
    fun `discovers and verifies the address of a Hamburg venue`(
        url: String,
        street: String,
        houseNumber: String,
        postalCode: String,
    ) = assertVerifiedAddress(url, street, houseNumber, postalCode)

    private fun assertVerifiedAddress(
        url: String,
        street: String,
        houseNumber: String,
        postalCode: String,
    ) {
        assumeOpenRouterCredentials()

        val address = BrowserDiscovery(url, lightpandaBinary()).use { it.verifiedDiscovery(AddressDiscovery) }
        println("$url: ${address.value}")
        address.fields.forEach { println("  ${it.field} '${it.value}' ${it.probability}") }

        address.value.street!! shouldContainIgnoringCase street
        address.value.houseNumber shouldBe houseNumber
        address.value.postalCode shouldBe postalCode
        address.value.city!! shouldContainIgnoringCase "Hamburg"
        address.verification.verified shouldBe true
    }
}
