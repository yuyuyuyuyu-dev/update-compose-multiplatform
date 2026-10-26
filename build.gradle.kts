plugins {
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.versionCatalogUpdate)
}

val kotlinScripts = files("scripts", "test", "build.gradle.kts", "settings.gradle.kts")

detekt {
    source.setFrom(kotlinScripts)
}

ktlint {
    version = libs.versions.ktlintCli.get()
}

tasks.runKtlintCheckOverKotlinScripts {
    source("scripts", "test")
}

tasks.runKtlintFormatOverKotlinScripts {
    source("scripts", "test")
}

tasks.wrapper {
    retries = 3
}
