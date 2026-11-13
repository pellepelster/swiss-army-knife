import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

// the conventions of every library (core, testing)
class SakAndroidLibraryPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.pluginManager.apply("com.android.library")
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
        }
        // android:test runs testGithubDebugUnitTest of every module; a library has no flavors
        target.tasks.register("testGithubDebugUnitTest") { dependsOn("testDebugUnitTest") }
    }
}
