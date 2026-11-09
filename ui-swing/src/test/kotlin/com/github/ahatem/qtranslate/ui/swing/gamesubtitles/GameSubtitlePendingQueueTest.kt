package com.github.ahatem.qtranslate.ui.swing.gamesubtitles

import kotlin.test.Test
import kotlin.test.assertEquals

class GameSubtitlePendingQueueTest {
    @Test
    fun `промежуточные версии одной реплики схлопываются до последней`() {
        val queue = GameSubtitlePendingQueue()

        queue.offer(PendingGameSubtitle("Подожди", replaceLatest = false))
        queue.offer(PendingGameSubtitle("Подожди меня", replaceLatest = true))
        queue.offer(PendingGameSubtitle("Подожди меня здесь", replaceLatest = true))

        assertEquals(
            listOf("Подожди меня здесь"),
            queue.snapshot().map(PendingGameSubtitle::sourceText)
        )
    }

    @Test
    fun `независимые реплики сохраняют исходный порядок`() {
        val queue = GameSubtitlePendingQueue()

        queue.offer(PendingGameSubtitle("Первая", replaceLatest = false))
        queue.offer(PendingGameSubtitle("Вторая", replaceLatest = false))
        queue.offer(PendingGameSubtitle("Третья", replaceLatest = false))

        assertEquals(
            listOf("Первая", "Вторая", "Третья"),
            queue.snapshot().map(PendingGameSubtitle::sourceText)
        )
    }

    @Test
    fun `переполнение очереди оставляет самые свежие реплики`() {
        val queue = GameSubtitlePendingQueue(maxSize = 2)

        queue.offer(PendingGameSubtitle("Первая", replaceLatest = false))
        queue.offer(PendingGameSubtitle("Вторая", replaceLatest = false))
        queue.offer(PendingGameSubtitle("Третья", replaceLatest = false))

        assertEquals(
            listOf("Вторая", "Третья"),
            queue.snapshot().map(PendingGameSubtitle::sourceText)
        )
    }
}
