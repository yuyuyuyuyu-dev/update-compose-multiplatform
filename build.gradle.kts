import org.jmailen.gradle.kotlinter.tasks.FormatTask
import org.jmailen.gradle.kotlinter.tasks.LintTask

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kotlinter)
}

dependencies {
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.tomlj)
}

val kotlinScripts = files("scripts", "build.gradle.kts", "settings.gradle.kts")

detekt {
    source.from(kotlinScripts)
}

val lintKotlinScripts =
    tasks.register<LintTask>("lintKotlinScripts") {
        source(kotlinScripts)
    }

val formatKotlinScripts =
    tasks.register<FormatTask>("formatKotlinScripts") {
        source(kotlinScripts)
    }

tasks.named { it == "lintKotlin" }.configureEach {
    dependsOn(lintKotlinScripts)
}

tasks.named { it == "formatKotlin" }.configureEach {
    dependsOn(formatKotlinScripts)
}

tasks.test {
    useJUnitPlatform()
    inputs.dir("scripts")
}
