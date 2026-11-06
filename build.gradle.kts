plugins {
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.versionCatalogUpdate)
}

val kotlinScripts = files("scripts", "spec", "build.gradle.kts", "settings.gradle.kts")

detekt {
    source.setFrom(kotlinScripts)
}

ktlint {
    version = libs.versions.ktlintCli.get()
}

tasks.runKtlintCheckOverKotlinScripts {
    source("scripts", "spec")
}

tasks.runKtlintFormatOverKotlinScripts {
    source("scripts", "spec")
}

tasks.wrapper {
    retries = 3
}
