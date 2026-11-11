package ee.alfred.text

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal object TextDocumentStorage {
    const val MAX_DOCUMENT_BYTES = 4 * 1024 * 1024
    const val MAX_DOCUMENT_CHARACTERS = 1_500_000

    fun read(input: InputStream): String {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (bytes.size() > MAX_DOCUMENT_BYTES - count) {
                throw IOException("Document exceeds the 4 MB limit.")
            }
            bytes.write(buffer, 0, count)
        }

        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = try {
            decoder.decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
        } catch (error: CharacterCodingException) {
            throw IOException("Document is not valid UTF-8.", error)
        }
        if (text.length > MAX_DOCUMENT_CHARACTERS) {
            throw IOException("Document exceeds the 1,500,000 character limit.")
        }
        return text
    }

    fun write(output: OutputStream, text: String) {
        output.write(encode(text))
        output.flush()
    }

    fun readDraft(file: File): String? {
        if (!file.exists()) return null
        return FileInputStream(file).use(::read)
    }

    fun writeDraft(file: File, text: String) {
        val bytes = encode(text)
        val parent = file.parentFile
            ?: throw IOException("Draft storage directory is unavailable.")
        if (!parent.isDirectory && !parent.mkdirs()) {
            throw IOException("Could not create draft storage directory.")
        }

        val temporary = File(parent, "${file.name}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
        } finally {
            if (temporary.exists() && !temporary.delete()) {
                throw IOException("Could not clean up temporary draft file.")
            }
        }
    }

    fun encode(text: String): ByteArray {
        if (text.length > MAX_DOCUMENT_CHARACTERS) {
            throw IOException("Document exceeds the 1,500,000 character limit.")
        }
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > MAX_DOCUMENT_BYTES) {
            throw IOException("Document exceeds the 4 MB limit.")
        }
        return bytes
    }
}
