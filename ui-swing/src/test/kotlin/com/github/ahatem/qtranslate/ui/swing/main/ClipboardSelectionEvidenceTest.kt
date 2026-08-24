package com.github.ahatem.qtranslate.ui.swing.main

import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import kotlin.test.Test
import kotlin.test.assertEquals

class ClipboardSelectionEvidenceTest {
    @Test
    fun `обычный текст без HTML не считается опубликованным DOM`() {
        assertEquals(
            ClipboardSelectionEvidence(hasHtml = false, hasEditableMarkup = false),
            inspectClipboardSelectionEvidence(transferable(plainText = "Hello"))
        )
    }

    @Test
    fun `HTML опубликованной страницы не сохраняет содержимое и даёт read only признак`() {
        assertEquals(
            ClipboardSelectionEvidence(hasHtml = true, hasEditableMarkup = false),
            inspectClipboardSelectionEvidence(
                transferable(
                    plainText = "Published text",
                    html = "<html><body><!--StartFragment--><p>Published text</p><!--EndFragment--></body></html>"
                )
            )
        )
    }

    @Test
    fun `сохранённый contenteditable маркер оставляет HTML полем ввода`() {
        assertEquals(
            ClipboardSelectionEvidence(hasHtml = true, hasEditableMarkup = true),
            inspectClipboardSelectionEvidence(
                transferable(
                    plainText = "Draft",
                    html = "<div contenteditable=\"true\">Draft</div>"
                )
            )
        )
    }

    @Test
    fun `сохранённые textarea и role textbox распознаются без учёта регистра`() {
        val textarea = inspectClipboardSelectionEvidence(
            transferable(plainText = "Draft", html = "<TEXTAREA>Draft</TEXTAREA>")
        )
        val roleTextbox = inspectClipboardSelectionEvidence(
            transferable(plainText = "Draft", html = "<div role='textbox'>Draft</div>")
        )

        assertEquals(true, textarea?.hasEditableMarkup)
        assertEquals(true, roleTextbox?.hasEditableMarkup)
    }

    private fun transferable(
        plainText: String,
        html: String? = null
    ): Transferable {
        val values = linkedMapOf<DataFlavor, String>(DataFlavor.stringFlavor to plainText)
        if (html != null) values[HTML_FLAVOR] = html

        return object : Transferable {
            override fun getTransferDataFlavors(): Array<DataFlavor> = values.keys.toTypedArray()

            override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor in values

            override fun getTransferData(flavor: DataFlavor): Any =
                values[flavor] ?: throw UnsupportedOperationException(flavor.humanPresentableName)
        }
    }

    private companion object {
        val HTML_FLAVOR = DataFlavor("text/html;class=java.lang.String;charset=UTF-8")
    }
}
