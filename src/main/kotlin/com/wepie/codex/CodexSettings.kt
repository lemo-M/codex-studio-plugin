package com.wepie.codex

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.xmlb.XmlSerializerUtil
import javax.swing.JComponent
import javax.swing.JPanel
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets

const val DEFAULT_SHORTCUT = "cmd+m"

@State(name = "CodexSettings", storages = [Storage("codex-settings.xml")])
class CodexSettingsState : PersistentStateComponent<CodexSettingsState> {
    var shortcut: String = DEFAULT_SHORTCUT

    override fun getState(): CodexSettingsState = this

    override fun loadState(state: CodexSettingsState) {
        XmlSerializerUtil.copyBean(state, this)
    }

    companion object {
        val instance: CodexSettingsState
            get() = ApplicationManager.getApplication().getService(CodexSettingsState::class.java)
    }
}

/**
 * Parsed keyboard shortcut for AppleScript.
 * Input: "cmd+m", "cmd+shift+k", etc. Last token is the key, rest are modifiers.
 */
data class ParsedShortcut(val key: String, val modifiers: List<String>) {
    val appleScriptUsing: String
        get() = when (modifiers.size) {
            0    -> ""
            1    -> " using ${modifiers[0]}"
            else -> " using {${modifiers.joinToString(", ")}}"
        }

    val displayName: String
        get() = (modifiers.map { it.removeSuffix(" down").replaceFirstChar { c -> c.uppercase() } } + listOf(key.uppercase()))
            .joinToString("+")

    companion object {
        private val MODIFIER_MAP = mapOf(
            "cmd"     to "command down",
            "command" to "command down",
            "shift"   to "shift down",
            "opt"     to "option down",
            "option"  to "option down",
            "alt"     to "option down",
            "ctrl"    to "control down",
            "control" to "control down",
        )

        fun parse(raw: String): ParsedShortcut? {
            val parts = raw.trim().lowercase().split("+").map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.isEmpty()) return null
            val key = parts.last()
            if (key.length != 1) return null
            val modifiers = parts.dropLast(1).mapNotNull { MODIFIER_MAP[it] }.distinct()
            return ParsedShortcut(key = key, modifiers = modifiers)
        }
    }
}

class CodexSettingsConfigurable : Configurable {
    private var shortcutField: JBTextField? = null

    override fun getDisplayName(): String = "Send to Codex"

    override fun createComponent(): JComponent {
        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints()

        gbc.anchor = GridBagConstraints.WEST
        gbc.insets = Insets(4, 0, 4, 8)
        gbc.gridx = 0
        gbc.gridy = 0
        gbc.fill = GridBagConstraints.NONE
        gbc.weightx = 0.0
        panel.add(JBLabel("Codex global shortcut:"), gbc)

        gbc.gridx = 1
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.weightx = 1.0
        val field = JBTextField(16)
        field.emptyText.text = "e.g. cmd+m  or  cmd+shift+k"
        shortcutField = field
        panel.add(field, gbc)

        gbc.gridx = 0
        gbc.gridy = 1
        gbc.gridwidth = 2
        gbc.insets = Insets(2, 0, 0, 0)
        gbc.fill = GridBagConstraints.NONE
        gbc.weightx = 0.0
        panel.add(JBLabel("<html><small>" +
            "填入你在 Codex 里设置的全局快捷键（Codex → Settings → General → Popout Window hotkey）。<br>" +
            "插件会模拟这个快捷键来唤起 Codex 窗口。<br>" +
            "修饰键写法：cmd &nbsp;shift &nbsp;opt &nbsp;ctrl，用 + 连接，最后接按键字母。" +
            "</small></html>"), gbc)

        gbc.gridy = 2
        gbc.fill = GridBagConstraints.BOTH
        gbc.weighty = 1.0
        panel.add(JPanel(), gbc)

        return panel
    }

    override fun isModified(): Boolean =
        shortcutField?.text?.trim() != CodexSettingsState.instance.shortcut

    override fun apply() {
        val value = shortcutField?.text?.trim()?.takeIf { it.isNotBlank() } ?: DEFAULT_SHORTCUT
        CodexSettingsState.instance.shortcut = value
    }

    override fun reset() {
        shortcutField?.text = CodexSettingsState.instance.shortcut
    }

    override fun disposeUIResources() {
        shortcutField = null
    }
}