package io.pelle.sak.model

import java.beans.Introspector
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.net.URL
import java.net.URLClassLoader
import java.sql.Connection
import java.util.Enumeration

// liquibase (about 1000 classes with snakeyaml, 10 MB of metaspace) is needed only for the
// migration at the start: a class loader and a thread of its own (liquibase keeps its scope in a
// ThreadLocal) let the next full gc unload it. The caches of java.beans are flushed, their soft
// references would keep it (the serial gc seldom clears them)
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

// child first for liquibase, snakeyaml and the migration, else the parent. Resources from the
// parent only, so liquibase finds its services and the changelog once
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
