plugins {
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.versionCatalogUpdate)
}

val kotlinScripts = files("scripts", "spec-test-resources", "build.gradle.kts", "settings.gradle.kts")

detekt {
    source.setFrom(kotlinScripts)
}

ktlint {
    version = libs.versions.ktlintCli.get()
}

tasks.runKtlintCheckOverKotlinScripts {
    source("scripts", "spec-test-resources")
}

tasks.runKtlintFormatOverKotlinScripts {
    source("scripts", "spec-test-resources")
}

tasks.wrapper {
    retries = 3
}
