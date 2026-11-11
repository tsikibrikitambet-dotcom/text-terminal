package ee.alfred.text

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TextDocumentStorageTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun readsAndWritesUtf8IncludingMultilineText() {
        val expected = "Hello, terminal!\nTere, maailm! 🌿\n"
        val output = ByteArrayOutputStream()

        TextDocumentStorage.write(output, expected)

        assertEquals(expected, TextDocumentStorage.read(ByteArrayInputStream(output.toByteArray())))
    }

    @Test
    fun acceptsDocumentAtByteLimit() {
        val threeByteCharacterCount = TextDocumentStorage.MAX_DOCUMENT_BYTES / 3
        val expected = "\u0800".repeat(threeByteCharacterCount) +
            "a".repeat(TextDocumentStorage.MAX_DOCUMENT_BYTES % 3)

        assertEquals(
            expected,
            TextDocumentStorage.read(ByteArrayInputStream(expected.toByteArray(StandardCharsets.UTF_8)))
        )
    }

    @Test
    fun rejectsDocumentOverByteLimit() {
        val oversized = "\u0800".repeat(TextDocumentStorage.MAX_DOCUMENT_BYTES / 3 + 1)

        assertThrows(IOException::class.java) {
            TextDocumentStorage.read(ByteArrayInputStream(oversized.toByteArray(StandardCharsets.UTF_8)))
        }
    }

    @Test
    fun rejectsDocumentOverCharacterLimit() {
        val oversized = "a".repeat(TextDocumentStorage.MAX_DOCUMENT_CHARACTERS + 1)

        assertThrows(IOException::class.java) {
            TextDocumentStorage.read(ByteArrayInputStream(oversized.toByteArray(StandardCharsets.UTF_8)))
        }
    }

    @Test
    fun rejectsMalformedUtf8() {
        assertThrows(IOException::class.java) {
            TextDocumentStorage.read(ByteArrayInputStream(byteArrayOf(0xC3.toByte(), 0x28)))
        }
    }

    @Test
    fun rejectsTextOverCharacterLimitBeforeWriting() {
        val output = ByteArrayOutputStream()
        val oversized = "a".repeat(TextDocumentStorage.MAX_DOCUMENT_CHARACTERS + 1)

        assertThrows(IOException::class.java) {
            TextDocumentStorage.write(output, oversized)
        }
        assertEquals(0, output.size())
    }

    @Test
    fun rejectsTextOverByteLimitBeforeWriting() {
        val output = ByteArrayOutputStream()
        val oversized = "\u0800".repeat(TextDocumentStorage.MAX_DOCUMENT_BYTES / 3 + 1)

        assertThrows(IOException::class.java) {
            TextDocumentStorage.write(output, oversized)
        }
        assertEquals(0, output.size())
    }

    @Test
    fun atomicallySavesAndRestoresDraft() {
        val draft = File(temporaryFolder.root, "draft.txt")
        val expected = "Unsaved draft\n".repeat(100)

        TextDocumentStorage.writeDraft(draft, expected)

        assertEquals(expected, TextDocumentStorage.readDraft(draft))
        TextDocumentStorage.writeDraft(draft, "Updated draft")
        assertEquals("Updated draft", TextDocumentStorage.readDraft(draft))
        assertEquals(listOf("draft.txt"), temporaryFolder.root.list()?.toList())
    }

    @Test
    fun failedDraftWriteLeavesPreviousDraftIntact() {
        val draft = File(temporaryFolder.root, "draft.txt")
        val original = "Original draft"
        TextDocumentStorage.writeDraft(draft, original)

        assertThrows(IOException::class.java) {
            TextDocumentStorage.writeDraft(
                draft,
                "x".repeat(TextDocumentStorage.MAX_DOCUMENT_CHARACTERS + 1)
            )
        }

        assertEquals(original, TextDocumentStorage.readDraft(draft))
    }

    @Test
    fun returnsNullWhenDraftDoesNotExist() {
        assertNull(TextDocumentStorage.readDraft(File(temporaryFolder.root, "missing.txt")))
    }
}
