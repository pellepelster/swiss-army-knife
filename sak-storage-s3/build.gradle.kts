// the S3 storage of the deployment at a provider (Hetzner Object Storage): a bucket and its client, the
// check of its keys, the providers and their regions; the settings that choose them are the backend's
// (S3StorageManager)
plugins {
    id("sak.kotlin-conventions")
    `java-library`
}

dependencies {
    api(project(":sak-api"))
    // its client and requests are part of S3Bucket
    api(libs.aws.lightweight.client)

    // a local S3 api to check keys against
    testImplementation(project(":sak-s3"))
    testImplementation(project(":sak-storage-local"))
    testImplementation(libs.ktor.server.netty)
}
