package com.wepie.codex

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import java.awt.datatransfer.StringSelection
import java.util.concurrent.TimeUnit

class SendContextAction : BaseCodexAction() {
    override fun perform(ref: String): Outcome {
        CopyPasteManager.getInstance().setContents(StringSelection(ref))

        return when (val result = CodexAutomation.switchAndPaste()) {
            CodexAutomation.Result.SUCCESS -> Outcome(
                title = "Send to Codex",
                message = "Switched to Codex and pasted:\n$ref",
                type = NotificationType.INFORMATION,
            )
            CodexAutomation.Result.UNSUPPORTED_OS -> Outcome(
                title = "Send to Codex",
                message = "Copied to clipboard (switch-and-paste requires macOS):\n$ref",
                type = NotificationType.INFORMATION,
            )
            is CodexAutomation.Result.FAILED -> Outcome(
                title = "Send to Codex",
                message = "Copied to clipboard. Switch-and-paste failed: ${result.reason}\n$ref",
                type = NotificationType.WARNING,
            )
        }
    }
}

abstract class BaseCodexAction : AnAction(), DumbAware {
    final override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = editor?.selectionModel?.hasSelection() == true
    }

    final override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return

        val selectionModel = editor.selectionModel
        if (!selectionModel.hasSelection()) return

        val document = editor.document
        val startOffset = selectionModel.selectionStart
        val endOffsetExclusive = selectionModel.selectionEnd
        val startLine = document.getLineNumber(startOffset) + 1
        val endLine = document.getLineNumber((endOffsetExclusive - 1).coerceAtLeast(startOffset)) + 1

        val basePath = project.basePath.orEmpty().trimEnd('/')
        val fullPath = file.path
        val relativePath = if (basePath.isNotEmpty() && fullPath.startsWith("$basePath/")) {
            fullPath.removePrefix("$basePath/")
        } else {
            fullPath
        }

        val ref = "@$relativePath#L$startLine-$endLine"
        val outcome = perform(ref)

        NotificationGroupManager.getInstance()
            .getNotificationGroup("Codex Selection Toolbar")
            .createNotification(outcome.title, outcome.message, outcome.type)
            .notify(project)
    }

    protected abstract fun perform(ref: String): Outcome
}

data class Outcome(
    val title: String,
    val message: String,
    val type: NotificationType,
)

private object CodexAutomation {
    sealed interface Result {
        data object SUCCESS : Result
        data object UNSUPPORTED_OS : Result
        data class FAILED(val reason: String) : Result
    }

    fun switchAndPaste(): Result {
        val osName = System.getProperty("os.name").orEmpty().lowercase()
        if (!osName.contains("mac")) return Result.UNSUPPORTED_OS

        val lines = listOf(
            "tell application \"System Events\"",
            "tell process \"Codex\"",
            "set codexWindows to every window",
            "if (count of codexWindows) is 0 then error \"Codex has no windows\"",
            "set targetWindow to item 1 of codexWindows",
            "if (count of codexWindows) > 1 then",
            "set minArea to 2147483647",
            "repeat with currentWindow in codexWindows",
            "set {w, h} to size of currentWindow",
            "set area to (w * h)",
            "if area < minArea then",
            "set minArea to area",
            "set targetWindow to currentWindow",
            "end if",
            "end repeat",
            "end if",
            "set windowsToRestore to {}",
            "repeat with currentWindow in codexWindows",
            "if currentWindow is not targetWindow then",
            "try",
            "if (value of attribute \"AXMinimized\" of currentWindow) is false then",
            "set value of attribute \"AXMinimized\" of currentWindow to true",
            "set end of windowsToRestore to currentWindow",
            "end if",
            "end try",
            "end if",
            "end repeat",
            "try",
            "set value of attribute \"AXMinimized\" of targetWindow to false",
            "end try",
            "perform action \"AXRaise\" of targetWindow",
            "set value of attribute \"AXMain\" of targetWindow to true",
            "try",
            "set value of attribute \"AXFocused\" of targetWindow to true",
            "end try",
            "set frontmost to true",
            "keystroke \"v\" using command down",
            "repeat with w in windowsToRestore",
            "try",
            "set value of attribute \"AXMinimized\" of w to false",
            "end try",
            "end repeat",
            "end tell",
            "end tell",
        )

        val result = runOsaScript(lines)
        if (result.exitCode == 0) return Result.SUCCESS

        val reason = when {
            result.stderr.contains("Not authorized", ignoreCase = true) ->
                "Accessibility permission is required for Android Studio to control keyboard input"
            result.stderr.contains("Codex has no windows", ignoreCase = true) ->
                "Codex is running but no window is available"
            result.stderr.isNotBlank() -> result.stderr
            else -> "unknown AppleScript error"
        }
        return Result.FAILED(reason)
    }

    private fun runOsaScript(lines: List<String>): OsaResult {
        val args = buildList {
            add("osascript")
            lines.forEach {
                add("-e")
                add(it)
            }
        }

        val process = try {
            ProcessBuilder(args).start()
        } catch (e: Exception) {
            return OsaResult(exitCode = -1, stderr = e.message ?: "failed to launch osascript")
        }

        val finished = process.waitFor(4, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return OsaResult(exitCode = -1, stderr = "timed out waiting for macOS automation")
        }

        val stderr = process.errorStream.bufferedReader().use { it.readText().trim() }
        return OsaResult(exitCode = process.exitValue(), stderr = stderr)
    }

    private data class OsaResult(
        val exitCode: Int,
        val stderr: String,
    )
}
