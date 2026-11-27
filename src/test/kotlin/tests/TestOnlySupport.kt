package dev.yuyuyuyuyu.updatecomposemultiplatform.tests

import dev.yuyuyuyuyu.updatecomposemultiplatform.HANDOVER_FILE
import dev.yuyuyuyuyu.updatecomposemultiplatform.support.FakeGitHub
import dev.yuyuyuyuyu.updatecomposemultiplatform.support.FakePullRequest
import dev.yuyuyuyuyu.updatecomposemultiplatform.support.FakeRepository
import dev.yuyuyuyuyu.updatecomposemultiplatform.support.FakeRunner
import dev.yuyuyuyuyu.updatecomposemultiplatform.support.fakeGradlew
import dev.yuyuyuyuyu.updatecomposemultiplatform.support.git
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

fun changelogNamed(name: String): String =
    checkNotNull(FakeRunner::class.java.getResource("/tests/changelog/$name")) { "No CHANGELOG named $name." }.readText()

fun FakeRunner.errors(): List<String> = output.lines().filter { it.startsWith("::error::") }.map { it.removePrefix("::error::") }

fun FakeRunner.release(tag: String) {
    event.writeText(JsonObject(mapOf("release" to JsonObject(mapOf("tag_name" to JsonPrimitive(tag))))).toString())
}

fun FakeRunner.editHandover(edit: (String) -> String) {
    val handover = File(temp, HANDOVER_FILE)
    handover.writeText(edit(handover.readText()))
}

fun FakeGitHub.openPullRequest(
    head: String,
    title: String,
    body: String,
): FakePullRequest = FakePullRequest(pullRequests.size + 1, head, "main", title, body).also(pullRequests::add)

fun FakeRepository.gradlewThatAlsoWritesReadme(): String = fakeGradlew(gradleLog) + "echo \"Written by gradlew\" > README.md\n"

fun FakeRepository.pushToMain(files: Map<String, String>) {
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
