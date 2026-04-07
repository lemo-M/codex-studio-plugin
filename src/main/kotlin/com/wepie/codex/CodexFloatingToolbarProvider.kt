package com.wepie.codex

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.toolbar.floating.FloatingToolbarProvider

class CodexFloatingToolbarProvider : FloatingToolbarProvider {
    override val actionGroup: ActionGroup
        get() = ActionManager.getInstance().getAction("Codex.SelectionToolbarGroup") as ActionGroup

    override fun isApplicable(dataContext: DataContext): Boolean {
        val editor = CommonDataKeys.EDITOR.getData(dataContext) ?: return false
        return editor.selectionModel.hasSelection()
    }
}
