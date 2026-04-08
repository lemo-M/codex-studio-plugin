package com.wepie.codex

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import java.awt.datatransfer.StringSelection
import java.util.concurrent.TimeUnit

internal val isMacOS: Boolean =
    System.getProperty("os.name").orEmpty().lowercase().contains("mac")

class SendContextAction : BaseCodexAction() {
    override fun perform(ref: String): Outcome? {
        CopyPasteManager.getInstance().setContents(StringSelection(ref))

        return when (val result = CodexAutomation.switchAndPaste()) {
            CodexAutomation.Result.SUCCESS -> null
            CodexAutomation.Result.UNSUPPORTED_OS -> Outcome(
                title = "Send to Codex",
                message = "已复制到剪贴板（自动切换功能仅支持 macOS）",
                type = NotificationType.INFORMATION,
            )
            is CodexAutomation.Result.FAILED -> Outcome(
                title = "Send to Codex",
                message = "Switch-and-paste failed: ${result.reason}",
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

        val lineRange = if (startLine == endLine) "L$startLine" else "L$startLine-$endLine"
        val ref = "@$relativePath#$lineRange"

        ApplicationManager.getApplication().executeOnPooledThread {
            val outcome = perform(ref)
            if (outcome != null) {
                NotificationGroupManager.getInstance()
                    .getNotificationGroup("Codex Selection Toolbar")
                    .createNotification(outcome.title, outcome.message, outcome.type)
                    .notify(project)
            }
        }
    }

    protected abstract fun perform(ref: String): Outcome?
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
        if (!isMacOS) return Result.UNSUPPORTED_OS

        val shortcut = CodexSettingsState.instance.shortcut
            .let { ParsedShortcut.parse(it) }
            ?: ParsedShortcut.parse(DEFAULT_SHORTCUT)!!
        val lines = listOf(
            "tell application \"System Events\"",
            "if not (exists process \"Codex\") then error \"Codex is not running\"",
            "keystroke \"${shortcut.key}\"${shortcut.appleScriptUsing}",
            "set attempts to 0",
            "repeat while (frontmost of process \"Codex\") is false",
            "delay 0.05",
            "set attempts to attempts + 1",
            "if attempts > 60 then error \"Codex did not become active\"",
            "end repeat",
            "keystroke \"v\" using command down",
            "end tell",
        )

        val result = runOsaScript(lines)
        if (result.exitCode == 0) return Result.SUCCESS

        val reason = when {
            result.stderr.contains("Not authorized", ignoreCase = true) ->
                "Accessibility permission is required for Android Studio to control keyboard input"
            result.stderr.contains("Codex is not running", ignoreCase = true) ->
                "Codex is not running"
            result.stderr.contains("did not become active", ignoreCase = true) ->
                "Codex did not respond to the global shortcut (${shortcut.displayName})"
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
            ProcessBuilder(args).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        } catch (e: Exception) {
            return OsaResult(exitCode = -1, stderr = e.message ?: "failed to launch osascript")
        }

        val finished = process.waitFor(6, TimeUnit.SECONDS)
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