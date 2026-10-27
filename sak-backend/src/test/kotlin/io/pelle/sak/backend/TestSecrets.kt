package io.pelle.sak.backend

import io.pelle.sak.backend.secrets.SecretService
import io.pelle.sak.model.SakDatabase
import java.time.Clock

// the secret keys of the tests, the configs the tests write have them too
val TEST_SECRET_KEYS = mapOf(1 to "Wgm/RM7IrCn+PskWL3305NjrfmyPDGtCTl1U8+btbYk=")

// the secrets of a database as the backend under test reads them
fun testSecrets(
    database: SakDatabase,
    clock: Clock = Clock.systemUTC(),
) = SecretService(database, TEST_SECRET_KEYS, "example.org", clock)
