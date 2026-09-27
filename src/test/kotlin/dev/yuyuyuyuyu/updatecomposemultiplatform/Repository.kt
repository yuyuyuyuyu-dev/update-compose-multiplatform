package dev.yuyuyuyuyu.updatecomposemultiplatform

import java.io.File
import java.nio.file.Files

class Repository(
    val directory: File,
    private val git: Git,
) {
    init {
        git.run(directory.parentFile, "init", "--quiet", "--bare", "--initial-branch=main", directory.path)
    }

    fun push(
        path: String,
        content: String,
    ): String {
        val clone = Files.createTempDirectory(directory.parentFile.toPath(), "clone").toFile()
        git.run(clone, "clone", "--quiet", directory.path, ".")
        clone.resolve(path).apply { parentFile.mkdirs() }.writeText(content)
        git.run(clone, "add", path)
        git.run(clone, "config", "user.name", "Octocat")
        git.run(clone, "config", "user.email", "octocat@example.com")
        git.run(clone, "commit", "--quiet", "--message", "Add $path")
        git.run(clone, "push", "--quiet", "origin", "HEAD:main")
        return git.run(clone, "rev-parse", "HEAD").trim()
    }

    fun file(
        branch: String,
        path: String,
    ): String = git.run(directory, "show", "$branch:$path")
}
