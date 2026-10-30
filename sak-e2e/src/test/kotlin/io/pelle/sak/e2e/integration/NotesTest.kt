package io.pelle.sak.e2e.integration

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.pelle.sak.e2e.util.NotesPage
import io.pelle.sak.e2e.util.TestDeployments
import io.pelle.sak.e2e.util.TestEnvironment
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID

// the apps on both shared deployments, signed in with the password; each test works on notes of its
// own text, so the tests never see each other's notes
@Tag("integration")
@Order(2)
@ExtendWith(TestDeployments::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotesTest {
    private lateinit var environment: TestEnvironment

    @BeforeAll
    fun deployments(environment: TestEnvironment) {
        this.environment = environment
    }

    @ParameterizedTest
    @ValueSource(strings = ["single", "multi"])
    fun `a note is created, kept and deleted`(mode: String) {
        val deployment = environment.deployment(mode)
        val text = UUID.randomUUID().toString()
        environment.context(deployment).use { context ->
            val page = context.newPage()
            deployment.signIn(page)
            NotesPage.open(page)
            NotesPage.create(page, text)

            NotesPage.open(page)
            NotesPage.texts(page) shouldContain text

            NotesPage.delete(page, text)
            NotesPage.open(page)
            NotesPage.texts(page) shouldNotContain text
        }
    }
}
