package com.narzaru.mercurial.changes

object NavigationArming {

    val armingActionIds: Set<String> = setOf(
        "GotoDeclaration",
        "GotoDeclarationOnly",
        "GotoTypeDeclaration",
        "GotoImplementation",
        "GotoSuperMethod",
        "GotoRelated"
    )

    fun armsRedirect(actionId: String?, diffSourcePath: String?, redirectEnabled: Boolean): Boolean {
        if (!redirectEnabled) return false
        if (diffSourcePath == null) return false
        return actionId != null && armingActionIds.contains(actionId)
    }
}
