import org.jmailen.gradle.kotlinter.tasks.FormatTask
import org.jmailen.gradle.kotlinter.tasks.LintTask

plugins {
    alias(libs.plugins.detekt)
    alias(libs.plugins.kotlinter)
}

val kotlinScripts = files("scripts", "test", "build.gradle.kts", "settings.gradle.kts")

detekt {
    source.setFrom(kotlinScripts)
}

tasks.register<LintTask>("lintKotlin") {
    source(kotlinScripts)
}

tasks.register<FormatTask>("formatKotlin") {
    source(kotlinScripts)
}

tasks.wrapper {
    retries = 3
}
