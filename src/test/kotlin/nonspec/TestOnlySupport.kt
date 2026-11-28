package dev.yuyuyuyuyu.updatecomposemultiplatform.nonspec

import dev.yuyuyuyuyu.updatecomposemultiplatform.GitHub
import dev.yuyuyuyuyu.updatecomposemultiplatform.HANDOVER_FILE
import dev.yuyuyuyuyu.updatecomposemultiplatform.RunnerPaths
import dev.yuyuyuyuyu.updatecomposemultiplatform.spec.support.FakeGitHub
import dev.yuyuyuyuyu.updatecomposemultiplatform.spec.support.FakeRepository
import dev.yuyuyuyuyu.updatecomposemultiplatform.spec.support.git
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

fun changelogNamed(name: String): String =
    checkNotNull(EditableChangelog::class.java.getResource("/nonspec/changelog/$name")) { "No CHANGELOG named $name." }.readText()

fun RunnerPaths.release(tag: String) {
    event.writeText(JsonObject(mapOf("release" to JsonObject(mapOf("tag_name" to JsonPrimitive(tag))))).toString())
}

fun RunnerPaths.editHandover(edit: (String) -> String) {
    val handover = File(temp, HANDOVER_FILE)
    handover.writeText(edit(handover.readText()))
}

fun FakeGitHub.openPullRequest(
    head: String,
    title: String,
    body: String,
) {
    GitHub(endpoint = endpoint, token = "fake-app-token").createPullRequest(branch = head, base = "main", title = title, body = body)
}

fun FakeRepository.pushToMain(files: Map<String, String>) {
    val clone = checkout()
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
