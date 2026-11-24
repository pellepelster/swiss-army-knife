// the grocery app on top of core; the sdks, the version, the signing, the build types, compose, core
// and testing come from sak.android.app (build-logic)
plugins {
    id("sak.android.app")
}

android {
    namespace = "io.pelle.sak.grocery"

    defaultConfig {
        // also the scheme of the signin link (sak-frontend/src/app/signin/signin.ts)
        applicationId = "io.pelle.sak.grocery"
    }
}
