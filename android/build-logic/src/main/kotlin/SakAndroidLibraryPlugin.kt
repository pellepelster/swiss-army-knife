import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create

// the conventions of every library (core, testing); sakLibrary { compose() } switches compose on
class SakAndroidLibraryPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.pluginManager.apply("com.android.library")
        target.extensions.create<SakLibraryExtension>("sakLibrary", target)
        target.extensions.configure<LibraryExtension> {
            compileSdk {
                version = release(SakSdk.COMPILE) { minorApiLevel = SakSdk.COMPILE_MINOR }
            }
            defaultConfig {
                minSdk = SakSdk.MIN
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
            // the texts the tests read (english()): of core and, by its R class, of the module
            testOptions {
                unitTests.all { it.systemProperty("sak.app.package", namespace ?: "") }
            }
        }
        // android:test runs testGithubDebugUnitTest of every module; a library has no flavors
        target.tasks.register("testGithubDebugUnitTest") { dependsOn("testDebugUnitTest") }
    }
}

open class SakLibraryExtension(
    private val project: Project,
) {
    // the compiler plugin of compose and its build feature
    fun compose() {
        project.pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        project.extensions.configure<LibraryExtension> { buildFeatures.compose = true }
    }
}
