package com.narzaru.mercurial.hg

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.narzaru.mercurial.model.ChangesetsFragments
import java.nio.charset.Charset

object HgSettings {

    private const val ENCODING_KEY = "mercurial.fallbackEncoding"
    private const val WIDEN_KEY = "mercurial.changesetsWiden"
    private const val FRAGMENTS_KEY = "mercurial.changesetsFragments"
    private const val IGNORE_EOL_KEY = "mercurial.ignoreEolChanges"
    private const val STATUS_IN_TABS_KEY = "mercurial.statusInTabs"
    private const val COMMAND_SERVER_KEY = "mercurial.commandServer"
    private const val COMMAND_SERVER_COUNT_KEY = "mercurial.commandServerCount"

    val suggestedEncodings: List<String> = listOf(
        "windows-1251", "UTF-8", "IBM866", "KOI8-R", "windows-1252", "ISO-8859-1"
    )

    val systemDefault: String
        get() = System.getProperty("sun.jnu.encoding")
            ?.takeIf { isKnownCharset(it) }
            ?: Charset.defaultCharset().name()

    var fallbackEncoding: String
        get() = properties()?.getValue(ENCODING_KEY, systemDefault) ?: systemDefault
        set(value) = PropertiesComponent.getInstance().setValue(ENCODING_KEY, value, systemDefault)

    var widenToFragments: Boolean
        get() = properties()?.getBoolean(WIDEN_KEY, true) ?: true
        set(value) = PropertiesComponent.getInstance().setValue(WIDEN_KEY, value, true)

    var changesetsFragments: ChangesetsFragments
        get() {
            val name = properties()?.getValue(FRAGMENTS_KEY)
            return ChangesetsFragments.entries.firstOrNull { it.name == name }
                ?: ChangesetsFragments.PLATFORM_TRIMMED
        }
        set(value) = PropertiesComponent.getInstance()
            .setValue(FRAGMENTS_KEY, value.name, ChangesetsFragments.PLATFORM_TRIMMED.name)

    var ignoreEolChanges: Boolean
        get() = properties()?.getBoolean(IGNORE_EOL_KEY, true) ?: true
        set(value) = PropertiesComponent.getInstance().setValue(IGNORE_EOL_KEY, value, true)

    var statusInTabs: Boolean
        get() = properties()?.getBoolean(STATUS_IN_TABS_KEY, true) ?: true
        set(value) = PropertiesComponent.getInstance().setValue(STATUS_IN_TABS_KEY, value, true)

    var useCommandServer: Boolean
        get() = properties()?.getBoolean(COMMAND_SERVER_KEY, true) ?: true
        set(value) = PropertiesComponent.getInstance().setValue(COMMAND_SERVER_KEY, value, true)

    var commandServerCount: Int
        get() = boundCount(
            properties()?.getInt(COMMAND_SERVER_COUNT_KEY, HgServerPool.DEFAULT_SERVERS)
                ?: HgServerPool.DEFAULT_SERVERS
        )
        set(value) = PropertiesComponent.getInstance()
            .setValue(COMMAND_SERVER_COUNT_KEY, boundCount(value), HgServerPool.DEFAULT_SERVERS)

    fun boundCount(value: Int): Int =
        value.coerceIn(HgServerPool.MIN_SERVERS, HgServerPool.MAX_SERVERS)

    fun nativeCharset(): Charset = Charset.forName(systemDefault)

    fun fallbackCharset(): Charset {
        val name = fallbackEncoding
        return if (isKnownCharset(name)) Charset.forName(name) else Charset.defaultCharset()
    }

    private fun properties(): PropertiesComponent? =
        if (ApplicationManager.getApplication() == null) null else PropertiesComponent.getInstance()

    private fun isKnownCharset(name: String): Boolean = try {
        Charset.isSupported(name)
    } catch (_: IllegalArgumentException) {
        false
    }
}
