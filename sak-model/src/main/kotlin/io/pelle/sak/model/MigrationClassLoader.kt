package io.pelle.sak.model

import java.beans.Introspector
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.net.URL
import java.net.URLClassLoader
import java.sql.Connection
import java.util.Enumeration

// liquibase (approximately 1000 classes with snakeyaml, 10 MB of metaspace) is needed only while
// the schema is migrated at the start. It is loaded by a class loader of its own, on a thread of
// its own (liquibase keeps its scope in a ThreadLocal), so both are gone after the migration and
// the next full gc unloads its classes. The caches of java.beans would keep them (soft references,
// which the serial gc seldom clears), they are flushed
internal fun migrateIsolated(
    connection: Connection,
    changelog: String,
) {
    val application = SakDatabase::class.java.classLoader
    MigrationClassLoader(classpath(), application).use { loader ->
        var failure: Throwable? = null
        val thread =
            Thread({
                try {
                    loader
                        .loadClass(MIGRATION)
                        .getMethod("migrate", Connection::class.java, String::class.java, ClassLoader::class.java)
                        .invoke(null, connection, changelog, application)
                } catch (e: InvocationTargetException) {
                    failure = e.targetException
                } catch (e: Throwable) {
                    failure = e
                }
            }, "liquibase-migration")
        thread.contextClassLoader = loader
        thread.start()
        thread.join()
        Introspector.flushCaches()
        failure?.let { throw IllegalStateException("the database could not be migrated", it) }
    }
}

// the file of migrate() in LiquibaseMigration.kt, named as a string: a class literal would load it
// with the class loader of the application
private const val MIGRATION = "io.pelle.sak.model.LiquibaseMigrationKt"

private fun classpath(): Array<URL> =
    System
        .getProperty("java.class.path")
        .split(File.pathSeparator)
        .filter { it.isNotEmpty() }
        .map { File(it).toURI().toURL() }
        .toTypedArray()

// loads liquibase, snakeyaml and the migration itself before asking its parent, everything else
// from the parent. Resources come from the parent only, liquibase finds its services and the
// changelog once
private class MigrationClassLoader(
    urls: Array<URL>,
    parent: ClassLoader,
) : URLClassLoader("liquibase-migration", urls, parent) {
    override fun loadClass(
        name: String,
        resolve: Boolean,
    ): Class<*> {
        if (name != MIGRATION && OWN.none { name.startsWith(it) }) return super.loadClass(name, resolve)
        synchronized(getClassLoadingLock(name)) {
            val loaded = findLoadedClass(name) ?: findClass(name)
            if (resolve) resolveClass(loaded)
            return loaded
        }
    }

    override fun getResource(name: String): URL? = parent.getResource(name)

    override fun getResources(name: String): Enumeration<URL> = parent.getResources(name)

    companion object {
        private val OWN = listOf("liquibase.", "org.yaml.snakeyaml.")
    }
}
