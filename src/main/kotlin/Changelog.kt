package dev.yuyuyuyuyu.updatecomposemultiplatform

private val gradlePluginRow = Regex("""`org\.jetbrains\.compose` version `([^`]+)`""")
private val libraryRow = Regex("""\|\s*([^|`]*?)\s*\|\s*`([^`:\s]+:[^`:\s]+):([^`\s]+)`\s*\|.*""")
private val componentsHeading = Regex("""##\s+Components\s*""")
private val secondLevelHeading = Regex("""##\s.*""")
private val releaseHeading = Regex("""#+\s+(\d+\.\d+\.\d+\S*)\s+\(.*""")

class LibraryGroup(
    val name: String,
    val coordinates: String,
)

class Components(
    private val release: Version,
    private val lines: List<String>,
) {
    fun version(
        kind: String,
        groups: List<LibraryGroup>,
    ): Version {
        val text =
            if (kind == COMPOSE_NAME) {
                lines.firstNotNullOfOrNull { gradlePluginRow.find(it) }?.groupValues?.get(1)
            } else {
                val coordinates = groups.first { it.name == kind }.coordinates
                libraryRows(lines).firstOrNull { it.coordinates == coordinates }?.version
            }
        return text?.let(::Version)
            ?: error("The Compose Multiplatform CHANGELOG section for $release has no $kind version that can be read.")
    }
}

class Changelog(
    text: String,
) {
    private val lines = text.lines()

    fun releases(): List<Version> {
        val releases = lines.mapNotNull(::headingVersion)
        check(releases.any { it.isStable }) { "The Compose Multiplatform CHANGELOG lists no stable release." }
        return releases
    }

    fun components(release: Version): Components {
        val heading = lines.indexOfFirst { headingVersion(it) == release }
        check(heading >= 0) { "The Compose Multiplatform CHANGELOG has no section for $release." }
        val section = lines.drop(heading + 1).takeWhile { !releaseHeading.matches(it) }
        val components =
            section
                .dropWhile { !componentsHeading.matches(it) }
                .drop(1)
                .takeWhile { !secondLevelHeading.matches(it) }
        return Components(release, components)
    }

    fun libraryGroups(): List<LibraryGroup> =
        libraryRows(lines)
            .map { LibraryGroup(it.name, it.coordinates) }
            .distinctBy { it.coordinates }

    private fun headingVersion(line: String): Version? =
        releaseHeading
            .matchEntire(line)
            ?.groupValues
            ?.get(1)
            ?.let(::Version)
}

private class LibraryRow(
    val name: String,
    val coordinates: String,
    val version: String,
)

private fun libraryRows(lines: List<String>): List<LibraryRow> =
    lines.mapNotNull { line ->
        libraryRow.matchEntire(line)?.destructured?.let { (name, coordinates, version) ->
            LibraryRow(name, coordinates, version)
        }
    }
