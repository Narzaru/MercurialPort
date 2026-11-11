package com.narzaru.mercurial.hg

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.narzaru.mercurial.changes.BranchSizeGuard
import com.narzaru.mercurial.model.ChangesetsEngine
import com.narzaru.mercurial.model.ChangesetsFragments
import java.nio.charset.Charset
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

object HgSettings {

    private const val ENCODING_KEY = "mercurial.fallbackEncoding"
    private const val WIDEN_KEY = "mercurial.changesetsWiden"
    private const val FRAGMENTS_KEY = "mercurial.changesetsFragments"
    private const val ENGINE_KEY = "mercurial.changesetsEngine"
    private const val IGNORE_EOL_KEY = "mercurial.ignoreEolChanges"
    private const val STATUS_IN_TABS_KEY = "mercurial.statusInTabs"
    private const val COMMAND_SERVER_KEY = "mercurial.commandServer"
    private const val COMMAND_SERVER_COUNT_KEY = "mercurial.commandServerCount"
    private const val LARGEST_BRANCH_KEY = "mercurial.largestBranchRevisions"

    private val flags = ArrayList<Flag>()

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

    var widenToFragments: Boolean by flag(WIDEN_KEY, true)

    var changesetsFragments: ChangesetsFragments
        get() = ChangesetsFragments.fromStored(properties()?.getValue(FRAGMENTS_KEY))
        set(value) = PropertiesComponent.getInstance()
            .setValue(FRAGMENTS_KEY, value.name, ChangesetsFragments.DEFAULT.name)

    var changesetsEngine: ChangesetsEngine
        get() = ChangesetsEngine.fromStored(properties()?.getValue(ENGINE_KEY))
        set(value) = PropertiesComponent.getInstance()
            .setValue(ENGINE_KEY, value.name, ChangesetsEngine.DEFAULT.name)

    val usesReviewEngine: Boolean get() = changesetsEngine.isUpsourceReview

    var ignoreEolChanges: Boolean by flag(IGNORE_EOL_KEY, true)

    var statusInTabs: Boolean by flag(STATUS_IN_TABS_KEY, true)

    var useCommandServer: Boolean by flag(COMMAND_SERVER_KEY, true)

    var commandServerCount: Int
        get() = boundCount(
            properties()?.getInt(COMMAND_SERVER_COUNT_KEY, HgServerPool.DEFAULT_SERVERS)
                ?: HgServerPool.DEFAULT_SERVERS
        )
        set(value) = PropertiesComponent.getInstance()
            .setValue(COMMAND_SERVER_COUNT_KEY, boundCount(value), HgServerPool.DEFAULT_SERVERS)

    var largestBranch: Int
        get() = (properties()?.getInt(LARGEST_BRANCH_KEY, BranchSizeGuard.DEFAULT_LIMIT) ?: BranchSizeGuard.DEFAULT_LIMIT)
            .coerceAtLeast(0)
        set(value) = PropertiesComponent.getInstance()
            .setValue(LARGEST_BRANCH_KEY, value.coerceAtLeast(0), BranchSizeGuard.DEFAULT_LIMIT)

    internal val flagDefaults: Map<String, Boolean> get() = flags.associate { it.key to it.default }

    fun branchLimitChoice(input: String): Int? = input.trim().toIntOrNull()?.takeIf { it >= 0 }

    fun boundCount(value: Int): Int =
        value.coerceIn(HgServerPool.MIN_SERVERS, HgServerPool.MAX_SERVERS)

    fun encodingChoice(input: String): String = input.trim().ifEmpty { systemDefault }

    fun nativeCharset(): Charset = Charset.forName(systemDefault)

    fun fallbackCharset(): Charset {
        val name = fallbackEncoding
        return if (isKnownCharset(name)) Charset.forName(name) else Charset.defaultCharset()
    }

    private fun properties(): PropertiesComponent? =
        if (ApplicationManager.getApplication() == null) null else PropertiesComponent.getInstance()

    private fun flag(key: String, default: Boolean): Flag = Flag(key, default).also { flags.add(it) }

    fun isKnownCharset(name: String): Boolean = try {
        Charset.isSupported(name)
    } catch (_: IllegalArgumentException) {
        false
    }

    private class Flag(val key: String, val default: Boolean) : ReadWriteProperty<Any?, Boolean> {

        override fun getValue(thisRef: Any?, property: KProperty<*>): Boolean =
            properties()?.getBoolean(key, default) ?: default

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) =
            PropertiesComponent.getInstance().setValue(key, value, default)
    }
}
