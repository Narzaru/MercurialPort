package com.narzaru.mercurial.history

object HistoryDateText {

    fun day(date: String): String = date.trim().substringBefore(' ')

    fun full(date: String): String? = date.trim().ifEmpty { null }
}
