import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.GradleException
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

// an app of android/ (grocery, notes): the sdks, java 17, the version of SAK, the signing of a
// release and its build types; the module keeps its namespace, its applicationId and its texts
class SakAndroidAppPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.pluginManager.apply("com.android.application")
        val sak = SakVersion.of(System.getenv("VERSION"))
        // the keystore mise run android:release:build decodes out of the secrets (github:secrets:update)
        val keystore = System.getenv("ANDROID_KEYSTORE_FILE")
        target.extensions.configure<ApplicationExtension> {
            compileSdk {
                version = release(SakSdk.COMPILE) { minorApiLevel = SakSdk.COMPILE_MINOR }
            }
            defaultConfig {
                minSdk = SakSdk.MIN
                targetSdk = SakSdk.TARGET
                versionCode = sak.code
                versionName = sak.name
            }
            signingConfigs {
                if (keystore != null) {
                    create("release") {
                        storeFile = target.file(keystore)
                        storeType = "PKCS12"
                        storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                        keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                        keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
                    }
                }
            }
            buildTypes {
                // a package of its own, signed with the debug key: it is installed next to the release,
                // which android does not update with an app of another key
                debug {
                    applicationIdSuffix = ".debug"
                }
                // shrunk by r8, with the rules of android/proguard-rules.pro next to the ones the
                // libraries bring (kotlinx.serialization, ktor, okhttp)
                release {
                    isMinifyEnabled = true
                    isShrinkResources = true
                    proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), target.rootProject.file("proguard-rules.pro"))
                    signingConfig = signingConfigs.findByName("release")
                }
            }
            buildFeatures {
                compose = true
                buildConfig = true
            }
            // the tests read the texts of the app by its R class (testing, english()). The screenshots
            // (the classes *Screenshots, robolectric) run only with -Psak.screenshots=<dir>, which they
            // are written to (mise run android:screenshots), and then alone
            testOptions {
                unitTests.isIncludeAndroidResources = true
                val screenshots = target.findProperty("sak.screenshots") as String?
                unitTests.all {
                    it.systemProperty("sak.app.package", namespace ?: "")
                    if (screenshots == null) {
                        it.exclude("**/*Screenshots*")
                    } else {
                        it.filter.includeTestsMatching("*Screenshots")
                        it.systemProperty("sak.screenshots", screenshots)
                        it.systemProperty("roborazzi.test.record", "true")
                        it.outputs.upToDateWhen { false }
                        // robolectric reaches into the file descriptors of the jdk
                        it.jvmArgs("--add-exports", "java.base/jdk.internal.access=ALL-UNNAMED")
                    }
                }
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
        }
        // a release is signed: the apk of a version without the keystore fails, latest may stay unsigned
        val unsigned = !sak.latest && keystore == null
        target.tasks.configureEach {
            if (name == "packageRelease" && unsigned) {
                doFirst { throw GradleException("the release ${sak.name} needs ANDROID_KEYSTORE_FILE, a release apk is signed") }
            }
        }
    }
}
