package io.pelle.sak.backend

import io.pelle.sak.backend.settings.SettingService
import io.pelle.sak.model.SakDatabase
import java.time.Clock

// the settings of a database as the backend under test reads them
fun testSettings(
    database: SakDatabase,
    clock: Clock = Clock.systemUTC(),
) = SettingService(database, clock)
