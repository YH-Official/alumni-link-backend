package moe.lolosia.gradle.util

import org.gradle.api.Project
import java.io.ByteArrayOutputStream
import java.io.File

data class GitInfo(val shortHash: String, val branchName: String)

fun getGitInfo(project: Project): GitInfo? {
    return try {
        val gitDir = executeGitCommand(project.rootProject.projectDir, "git", "rev-parse", "--git-dir")
        if (gitDir == null) return null

        val shortHash = executeGitCommand(project.rootProject.projectDir, "git", "rev-parse", "--short", "HEAD")
            ?: return null

        val branchName = executeGitCommand(project.rootProject.projectDir, "git", "rev-parse", "--abbrev-ref", "HEAD")
            ?: return null

        GitInfo(shortHash, branchName)
    } catch (_: Exception) {
        null
    }
}

private fun executeGitCommand(workingDir: File, vararg args: String): String? {
    return try {
        val processBuilder = ProcessBuilder(*args)
            .directory(workingDir)
            .redirectErrorStream(true)

        val process = processBuilder.start()
        val output = ByteArrayOutputStream()
        process.inputStream.copyTo(output)
        val exitCode = process.waitFor()

        if (exitCode != 0) return null
        output.toString("UTF-8").trim()
    } catch (_: Exception) {
        null
    }
}