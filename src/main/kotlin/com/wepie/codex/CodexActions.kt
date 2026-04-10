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
        val keyCode = shortcut.keyCode
        if (keyCode < 0) return Result.FAILED("Unsupported key: '${shortcut.key}'")

        // JXA script using CGEvent — immune to physical modifier key contamination.
        // 1. Poll until all physical modifier keys are released (max 500ms)
        // 2. Send the Codex global shortcut via CGEvent (exact flags, not affected by held keys)
        // 3. Poll until Codex becomes frontmost (max 3s)
        // 4. Send Cmd+V via CGEvent to paste
        val script = """
            ObjC.import('CoreGraphics');
            var se = Application('System Events');
            if (!se.processes.whose({name: 'Codex'}).length)
                throw new Error('Codex is not running');
            for (var i = 0; i < 50; i++) {
                if (($.CGEventSourceFlagsState(0) & 0x1E0000) === 0) break;
                delay(0.01);
            }
            var src = $.CGEventSourceCreate(-1);
            function sendKey(kc, flags) {
                var d = $.CGEventCreateKeyboardEvent(src, kc, true);
                $.CGEventSetFlags(d, flags);
                $.CGEventPost(0, d);
                var u = $.CGEventCreateKeyboardEvent(src, kc, false);
                $.CGEventSetFlags(u, flags);
                $.CGEventPost(0, u);
            }
            sendKey($keyCode, ${shortcut.cgEventFlags});
            var codex = se.processes.whose({name: 'Codex'})[0];
            for (var i = 0; i < 60; i++) {
                if (codex.frontmost()) break;
                delay(0.05);
                if (i === 59) throw new Error('Codex did not become active');
            }
            sendKey(0x09, 0x100000);
        """.trimIndent()

        val result = runJxa(script)
        if (result.exitCode == 0) return Result.SUCCESS

        val reason = when {
            result.stderr.contains("Not authorized", ignoreCase = true) ->
                "Accessibility permission is required for Android Studio to control keyboard input"
            result.stderr.contains("Codex is not running", ignoreCase = true) ->
                "Codex is not running"
            result.stderr.contains("did not become active", ignoreCase = true) ->
                "Codex did not respond to the global shortcut (${shortcut.displayName})"
            result.stderr.isNotBlank() -> result.stderr
            else -> "unknown error"
        }
        return Result.FAILED(reason)
    }

    private fun runJxa(script: String): OsaResult {
        val process = try {
            ProcessBuilder("osascript", "-l", "JavaScript")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
        } catch (e: Exception) {
            return OsaResult(exitCode = -1, stderr = e.message ?: "failed to launch osascript")
        }
        process.outputStream.bufferedWriter().use { it.write(script) }

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