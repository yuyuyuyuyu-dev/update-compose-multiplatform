plugins {
    application
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.versionCatalogUpdate)
}

application {
    mainClass = "dev.yuyuyuyuyu.updatecomposemultiplatform.MainKt"
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.tomlj)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.platformLauncher)
}

tasks.test {
    useJUnitPlatform()
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
}

detekt {
    buildUponDefaultConfig = true
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    jvmTarget = "22"
    jdkHome.unsetConvention()
}

ktlint {
    version = libs.versions.ktlintCli.get()
}

tasks.wrapper {
    retries = 3
}
