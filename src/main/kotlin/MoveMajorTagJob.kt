package dev.yuyuyuyuyu.updatecomposemultiplatform

private val releaseVersion = Regex("""v\d+\.\d+\.\d+""")

class MoveMajorTagJob(
    private val releaseTag: String,
    private val workspace: Workspace,
) {
    fun run() {
        check(releaseVersion.matches(releaseTag)) { "$releaseTag is not a version such as v1.2.3, so no major tag is moved." }
        val majorTag = releaseTag.substringBefore(".")
        val newestTag =
            workspace
                .git("ls-remote", "--refs", "--tags", "origin", "refs/tags/$majorTag.*")
                .lines()
                .map { it.substringAfterLast("/") }
                .filter(releaseVersion::matches)
                .maxByOrNull(::Version)
        check(newestTag == releaseTag) { "$releaseTag is older than $newestTag, so $majorTag is not moved." }
        workspace.git("push", "--quiet", "--force", "origin", "refs/tags/$releaseTag:refs/tags/$majorTag")
    }
}
