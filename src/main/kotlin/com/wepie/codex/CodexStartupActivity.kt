package com.wepie.codex

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class CodexStartupActivity : StartupActivity.DumbAware {
    companion object {
        private val warmedUp = AtomicBoolean(false)
    }

    override fun runActivity(project: Project) {
        if (!isMacOS) return
        if (!warmedUp.compareAndSet(false, true)) return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val process = ProcessBuilder(
                    "osascript", "-e",
                    "tell application \"System Events\" to return name of first process whose frontmost is true"
                ).start()
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                }
            } catch (_: Exception) {}
        }
    }
}