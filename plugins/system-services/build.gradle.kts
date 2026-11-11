plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":api"))
    implementation(libs.jna)
    implementation(libs.kotlinxCoroutines)

    testImplementation(kotlin("test"))
    testImplementation(testFixtures(project(":plugins:common")))
}

tasks.shadowJar {
    archiveBaseName.set("system-services-plugin")
    archiveClassifier.set("")
    archiveVersion.set("")
}
