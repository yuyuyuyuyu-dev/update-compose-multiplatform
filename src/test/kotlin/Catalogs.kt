fun catalog(
    compose: String = "1.10.1",
    ktor: String,
    okio: String? = null,
): String =
    buildString {
        appendLine("[versions]")
        appendLine("composeMultiplatform = \"$compose\"")
        appendLine("ktor = \"$ktor\"")
        appendLine()
        appendLine("[libraries]")
        appendLine("ktor-client-core = { module = \"io.ktor:ktor-client-core\", version.ref = \"ktor\" }")
        okio?.let { appendLine("okio = \"com.squareup.okio:okio:$it\"") }
        appendLine()
        appendLine("[plugins]")
        appendLine("composeMultiplatform = { id = \"org.jetbrains.compose\", version.ref = \"composeMultiplatform\" }")
    }

fun everyLibraryGroup(
    compose: String,
    material3: String,
    adaptive: String,
    lifecycle: String,
    navigation: String,
): String =
    """
    [versions]
    composeMultiplatform = "$compose"
    material3 = "$material3"
    compose-multiplatform-adaptive = "$adaptive"
    androidx-lifecycle = "$lifecycle"
    androidx-navigation = "$navigation"

    [libraries]
    compose-runtime = { module = "org.jetbrains.compose.runtime:runtime", version.ref = "composeMultiplatform" }
    compose-ui = { module = "org.jetbrains.compose.ui:ui", version.ref = "composeMultiplatform" }
    compose-material3 = { module = "org.jetbrains.compose.material3:material3", version.ref = "material3" }
    compose-material3-adaptive = { module = "org.jetbrains.compose.material3.adaptive:adaptive", version.ref = "compose-multiplatform-adaptive" }
    androidx-lifecycle-viewmodelCompose = { module = "org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "androidx-lifecycle" }
    androidx-navigation-compose = { module = "org.jetbrains.androidx.navigation:navigation-compose", version.ref = "androidx-navigation" }

    [plugins]
    composeMultiplatform = { id = "org.jetbrains.compose", version.ref = "composeMultiplatform" }
    """.trimIndent() + "\n"
