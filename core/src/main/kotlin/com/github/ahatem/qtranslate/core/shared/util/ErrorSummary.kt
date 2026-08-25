package com.github.ahatem.qtranslate.core.shared.util

import com.github.ahatem.qtranslate.api.plugin.ServiceError

/** Максимальная длина сообщения об ошибке, при которой строка состояния остаётся читаемой. */
private const val SUMMARY_MAX_LENGTH = 120

/**
 * Первая строка ошибки, сокращённая для строки состояния.
 *
 * Сервис может вернуть stack trace, HTML-страницу или JSON. Для пользователя достаточно первой
 * строки, а ограничение длины не даёт сообщению заполнить всю панель. Полный текст и исходный
 * throwable намеренно не пишутся в production-лог: URL исключения может содержать пользовательский
 * текст в query-параметрах.
 */
fun ServiceError.shortSummary(): String =
    message.lineSequence().firstOrNull()?.take(SUMMARY_MAX_LENGTH) ?: UNKNOWN_ERROR

/** Такое же сокращение для исключения, у которого сообщение может отсутствовать. */
fun Throwable.shortSummary(): String =
    message?.lineSequence()?.firstOrNull()?.take(SUMMARY_MAX_LENGTH) ?: UNKNOWN_ERROR

private const val UNKNOWN_ERROR = "Unknown error"
