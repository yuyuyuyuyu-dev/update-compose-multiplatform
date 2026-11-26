plugins {
    application
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.versionCatalogUpdate)
}

application {
    mainClass = "MainKt"
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.tomlj)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.platformLauncher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
}

tasks.test {
    filter.excludeTestsMatching("*Spec")
}

val spec =
    tasks.register<Test>("spec") {
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        testClassesDirs =
            sourceSets.test
                .get()
                .output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        filter.includeTestsMatching("*Spec")
    }

tasks.check {
    dependsOn(spec)
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
