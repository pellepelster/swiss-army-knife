import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.GradleException
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

// the conventions of every app; the module keeps its namespace, its applicationId and its own
// dependencies
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
                release {
                    isMinifyEnabled = true
                    isShrinkResources = true
                    proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), target.rootProject.file("proguard-rules.pro"))
                    signingConfig = signingConfigs.findByName("release")
                }
            }
            // github: the apk updates itself from the github release. play: google play forbids an app
            // to install itself, so no updater and no REQUEST_INSTALL_PACKAGES (play/AndroidManifest.xml)
            flavorDimensions += "distribution"
            productFlavors {
                create("github") {
                    dimension = "distribution"
                    buildConfigField("boolean", "SELF_UPDATE", "true")
                }
                create("play") {
                    dimension = "distribution"
                    buildConfigField("boolean", "SELF_UPDATE", "false")
                }
            }
            sourceSets.getByName("play").manifest.srcFile(target.rootProject.file("play/AndroidManifest.xml"))
            sourceSets.getByName("main").res.srcDir(target.rootProject.file("../sak-theme/images/android/icons"))
            sourceSets.getByName("main").res.srcDir(target.rootProject.file("../sak-theme/images/android/${target.name}"))
            buildFeatures {
                compose = true
                buildConfig = true
            }
            // the tests read the texts of the app by its R class (english()). The *Screenshots tests run
            // only with -Psak.screenshots=<dir>, the directory they write to, and then alone
            testOptions {
                unitTests.isIncludeAndroidResources = true
                val screenshots = target.findProperty("sak.screenshots") as String?
                unitTests.all {
                    it.systemProperty("sak.app.package", namespace ?: "")
                    // robolectric reaches into the file descriptors of the jdk
                    it.jvmArgs("--add-exports", "java.base/jdk.internal.access=ALL-UNNAMED")
                    if (screenshots == null) {
                        it.exclude("**/*Screenshots*")
                    } else {
                        it.filter.includeTestsMatching("*Screenshots")
                        it.systemProperty("sak.screenshots", screenshots)
                        it.systemProperty("roborazzi.test.record", "true")
                        it.outputs.upToDateWhen { false }
                    }
                }
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
        }
        // a release is never unsigned; latest may be
        val unsigned = !sak.latest && keystore == null
        val packaging = Regex("(package|bundle)[A-Za-z]*Release")
        target.tasks.configureEach {
            if (unsigned && packaging.matches(name)) {
                doFirst { throw GradleException("the release ${sak.name} needs ANDROID_KEYSTORE_FILE, a release apk or bundle is signed") }
            }
        }
    }
}
