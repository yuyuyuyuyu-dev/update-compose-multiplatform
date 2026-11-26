package dev.yuyuyuyuyu.updatecomposemultiplatform.tests

import dev.yuyuyuyuyu.updatecomposemultiplatform.support.FakeGitHub
import dev.yuyuyuyuyu.updatecomposemultiplatform.support.FakePullRequest
import dev.yuyuyuyuyu.updatecomposemultiplatform.support.RunResult
import dev.yuyuyuyuyu.updatecomposemultiplatform.support.TestWorld
import dev.yuyuyuyuyu.updatecomposemultiplatform.support.fakeGradlew
import dev.yuyuyuyuyu.updatecomposemultiplatform.support.git
import kotlin.test.assertEquals

fun TestWorld.gradlewThatAlsoWritesReadme(): String = fakeGradlew(gradleLog) + "echo \"Written by gradlew\" > README.md\n"

fun changelog(name: String): String =
    checkNotNull(TestWorld::class.java.getResource("/tests/changelog/$name")) { "No CHANGELOG named $name." }.readText()

fun RunResult.assertFailed(vararg errors: String) {
    assertEquals(1, exitCode, output)
    assertEquals(errors.map { "::error::$it" }, output.lines().filter { it.startsWith("::error::") }, output)
}

fun FakeGitHub.openPullRequest(
    head: String,
    title: String,
    body: String,
): FakePullRequest = FakePullRequest(pullRequests.size + 1, head, "main", title, body).also(pullRequests::add)

fun TestWorld.pushToMain(files: Map<String, String>) {
    val clone = origin.resolveSibling("main-${System.nanoTime()}")
    git(origin.parentFile, "clone", "--quiet", "--branch", "main", "file://${origin.path}", clone.path)
    files.forEach { (path, content) -> clone.resolve(path).apply { parentFile.mkdirs() }.writeText(content) }
    git(clone, "add", "--all")
    git(
        clone,
        "-c",
        "user.name=Octocat",
        "-c",
        "user.email=octocat@example.com",
        "commit",
        "--quiet",
        "--message",
        "Change the project",
    )
    git(clone, "push", "--quiet", "origin", "main")
}

fun TestWorld.editHandover(edit: (String) -> String) {
    handover.writeText(edit(handover.readText()))
}
