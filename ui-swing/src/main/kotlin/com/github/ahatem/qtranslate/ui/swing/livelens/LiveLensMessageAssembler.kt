package com.github.ahatem.qtranslate.ui.swing.livelens

import java.awt.Point
import java.awt.Rectangle
import kotlin.math.abs

/**
 * Собирает отдельные accessibility-фрагменты в строки «никнейм + сообщение».
 *
 * Никнейм распознаётся только когда он стоит в той же строке, что и текст: так устроены
 * Stripchat и другие веб-чаты. Строка без никнейма не выбрасывается — в Telegram и подобных
 * мессенджерах имя стоит отдельной строкой, а перенос длинного сообщения вообще не содержит
 * имени. Такая строка либо продолжает предыдущее сообщение, либо становится отдельным.
 */
internal fun assembleLiveLensMessages(
    blocks: List<LiveLensTextBlock>,
    scanBounds: Rectangle
): List<LiveLensTextBlock> {
    if (blocks.isEmpty()) return emptyList()

    val rows = mutableListOf<MutableList<LiveLensTextBlock>>()
    blocks.sortedWith(compareBy<LiveLensTextBlock> { it.anchor.y }.thenBy { it.anchor.x })
        .forEach { block ->
            val row = rows
                .asReversed()
                .firstOrNull { candidate ->
                    abs(candidate.map { it.anchor.y }.average() - block.anchor.y) <= ROW_TOLERANCE_PX
                }
            if (row == null) rows += mutableListOf(block) else row += block
        }

    val maximumMessageGap = (scanBounds.width * MAXIMUM_MESSAGE_GAP_RATIO).toInt()
        .coerceAtLeast(MINIMUM_MESSAGE_GAP_PX)
    val messages = mutableListOf<LiveLensTextBlock>()

    rows.forEach { row ->
        val ordered = row
            .sortedBy { it.anchor.x }
            .filterNot { part -> isChatInterfaceLabel(part.text) }
        if (ordered.isEmpty()) return@forEach

        // Никнейм имеет смысл только рядом с текстом: одинокое слово в строке — это само
        // сообщение, а не подпись, и молча выбрасывать его нельзя.
        val speaker = ordered
            .firstOrNull { part -> isProbableNickname(part.text) }
            ?.takeIf { candidate -> ordered.any { part -> part !== candidate } }

        val messageParts = ordered
            .filterNot { part -> part === speaker }
            .filter { part ->
                speaker == null || abs(part.anchor.x - speaker.anchor.x) <= maximumMessageGap
            }
            .distinctBy { part -> part.text.lowercase() }
        if (messageParts.isEmpty()) return@forEach

        val message = messageParts.joinToString(" ") { it.text }.trim()
        if (message.length < 2 || message.equals(speaker?.text, ignoreCase = true)) return@forEach

        val anchor = Point(messageParts.first().anchor)
        val previous = messages.lastOrNull()
        if (speaker == null && previous != null && isWrappedContinuation(previous, anchor)) {
            messages[messages.lastIndex] = previous.copy(text = "${previous.text} $message")
        } else {
            messages += LiveLensTextBlock(message, anchor, speaker?.text)
        }
    }
    return messages
}

/**
 * Перенос длинного сообщения выглядит как строка без имени сразу под предыдущей и с тем же
 * левым краем. Отдельная реплика в чате отстоит заметно дальше, поэтому не склеивается.
 */
private fun isWrappedContinuation(previous: LiveLensTextBlock, anchor: Point): Boolean {
    val verticalGap = anchor.y - previous.anchor.y
    return verticalGap in 1..CONTINUATION_MAX_VERTICAL_GAP_PX &&
        abs(anchor.x - previous.anchor.x) <= CONTINUATION_MAX_HORIZONTAL_SHIFT_PX
}

internal fun isProbableNickname(text: String): Boolean {
    val normalized = text.trim().removePrefix("@").removeSuffix(":")
    return normalized.length in 2..32 &&
        normalized.any(Char::isLetter) &&
        normalized.all { character ->
            character.isLetterOrDigit() || character == '_' || character == '-' || character == '.'
        }
}

private const val ROW_TOLERANCE_PX = 18
private const val MAXIMUM_MESSAGE_GAP_RATIO = 0.42
private const val MINIMUM_MESSAGE_GAP_PX = 110
private const val CONTINUATION_MAX_VERTICAL_GAP_PX = 34
private const val CONTINUATION_MAX_HORIZONTAL_SHIFT_PX = 24

/** Счётчик рядом с вкладкой («Users 0») — та же служебная надпись, что и сама вкладка. */
private fun isChatInterfaceLabel(text: String): Boolean {
    val normalized = text.trim().lowercase()
    if (normalized in CHAT_INTERFACE_LABELS) return true
    return normalized.trimEnd { character -> character.isDigit() || character.isWhitespace() } in
        CHAT_INTERFACE_LABELS
}

private val CHAT_INTERFACE_LABELS = setOf(
    "public",
    "private",
    "users",
    "паблик",
    "приват",
    "пользователи"
)
