package com.narzaru.mercurial.hg

import com.intellij.ide.util.PropertiesComponent
import java.nio.charset.Charset

/**
 * Настройки плагина уровня приложения.
 *
 * Главная из них — кодировка, которой декодируется вывод `hg`, если он не является
 * корректным UTF-8: сообщения коммитов часто набраны в системной кодировке (cp1251),
 * а `Charset.defaultCharset()` в современных JDK — это UTF-8, из-за чего кириллица
 * превращалась в «вопросики».
 */
object HgSettings {

    private const val ENCODING_KEY = "mercurial.fallbackEncoding"
    private const val SHARED_DIFF_TAB_KEY = "mercurial.sharedDiffTab"

    /** Кодировки, которые предлагаем в настройках; список открытый — можно вписать своё имя. */
    val suggestedEncodings: List<String> = listOf(
        "windows-1251", "UTF-8", "IBM866", "KOI8-R", "windows-1252", "ISO-8859-1"
    )

    /** ANSI-кодировка ОС (на русской Windows — windows-1251), а не кодировка файлов JVM. */
    val systemDefault: String
        get() = System.getProperty("sun.jnu.encoding")
            ?.takeIf { runCatching { Charset.isSupported(it) }.getOrDefault(false) }
            ?: Charset.defaultCharset().name()

    var fallbackEncoding: String
        get() = PropertiesComponent.getInstance().getValue(ENCODING_KEY, systemDefault)
        set(value) = PropertiesComponent.getInstance().setValue(ENCODING_KEY, value, systemDefault)

    /**
     * Одна дифф-вкладка на весь плагин: иначе Hg Changes и Hg File History держат
     * каждая свою, и на экране оказывается два диффа одновременно.
     */
    var shareDiffTab: Boolean
        get() = PropertiesComponent.getInstance().getBoolean(SHARED_DIFF_TAB_KEY, true)
        set(value) = PropertiesComponent.getInstance().setValue(SHARED_DIFF_TAB_KEY, value, true)

    fun fallbackCharset(): Charset = runCatching { Charset.forName(fallbackEncoding) }
        .getOrElse { Charset.defaultCharset() }
}
