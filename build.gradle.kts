plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.tomlj)
}

tasks.test {
    useJUnitPlatform()
    inputs.dir("scripts")
}
