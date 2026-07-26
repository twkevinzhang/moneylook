package tw.kevinzhang.moneylook.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import tw.kevinzhang.core.data.model.SourceDocument
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import android.util.Base64

@RunWith(RobolectricTestRunner::class)
class ArchivedSourceBodyReaderTest {
    private val reader = ArchivedSourceBodyReader()

    @Test
    fun `reads every byte through bounded offset chunks`() = runBlocking {
        val body = buildString {
            repeat(MAX_SOURCE_BODY_CHUNK_BYTES / 8 + 20) {
                append("row-")
                append(it.toString().padStart(8, '0'))
                append('\n')
            }
        }.toByteArray()
        val document = sourceDocument(body)

        val first = reader.readChunk(document, requestedOffset = 0)
        val second = reader.readChunk(document, requestedOffset = first.endOffsetExclusive)

        assertTrue(first.integrityVerified)
        assertTrue(second.integrityVerified)
        assertEquals(0L, first.startOffset)
        assertEquals(MAX_SOURCE_BODY_CHUNK_BYTES.toLong(), first.endOffsetExclusive)
        assertEquals(MAX_SOURCE_BODY_CHUNK_BYTES.toLong(), second.startOffset)
        assertEquals(body.size.toLong(), second.endOffsetExclusive)
        assertTrue(first.byteCount <= MAX_SOURCE_BODY_CHUNK_BYTES)
        assertTrue(second.byteCount <= MAX_SOURCE_BODY_CHUNK_BYTES)
        assertFalse(first.hasPrevious)
        assertTrue(first.hasNext)
        assertTrue(second.hasPrevious)
        assertFalse(second.hasNext)
        assertEquals(body.toString(Charsets.UTF_8), first.renderedBody + second.renderedBody)
        assertEquals(
            body.toList(),
            (Base64.decode(first.exactBytesBase64, Base64.NO_WRAP) +
                Base64.decode(second.exactBytesBase64, Base64.NO_WRAP)).toList(),
        )
    }

    @Test
    fun `exact base64 preserves a multibyte character split across chunk boundary`() = runBlocking {
        val prefix = ByteArray(MAX_SOURCE_BODY_CHUNK_BYTES - 1) { 'a'.code.toByte() }
        val multibyte = "界".toByteArray(Charsets.UTF_8)
        val suffix = "tail".toByteArray()
        val body = prefix + multibyte + suffix
        val document = sourceDocument(body)

        val first = reader.readChunk(document, requestedOffset = 0)
        val second = reader.readChunk(document, requestedOffset = first.endOffsetExclusive)
        val reconstructed = Base64.decode(first.exactBytesBase64, Base64.NO_WRAP) +
            Base64.decode(second.exactBytesBase64, Base64.NO_WRAP)

        assertEquals(body.toList(), reconstructed.toList())
        assertTrue(first.renderedBody.endsWith("\uFFFD"))
        assertTrue(second.renderedBody.startsWith("\uFFFD"))
    }

    @Test
    fun `clamps an oversized offset to the final chunk boundary`() = runBlocking {
        val body = ByteArray(MAX_SOURCE_BODY_CHUNK_BYTES + 12) { 'x'.code.toByte() }

        val chunk = reader.readChunk(sourceDocument(body), requestedOffset = Long.MAX_VALUE)

        assertEquals(MAX_SOURCE_BODY_CHUNK_BYTES.toLong(), chunk.startOffset)
        assertEquals(12L, chunk.byteCount)
        assertFalse(chunk.hasNext)
    }

    @Test
    fun `rejects an archive whose complete digest does not match`() = runBlocking {
        val body = "authenticated body".toByteArray()
        val document = sourceDocument(body).copy(bodySha256 = "0".repeat(64))

        val chunk = reader.readChunk(document, requestedOffset = 0)

        assertFalse(chunk.integrityVerified)
        assertEquals(0L, chunk.byteCount)
        assertTrue(requireNotNull(chunk.errorMessage).contains("SHA-256"))
    }

    private fun sourceDocument(body: ByteArray) = SourceDocument(
        id = "doc-1",
        runId = "run-1",
        extensionId = "ext-1",
        capturedAt = 100,
        stage = "transactions",
        transport = "native_http",
        method = "GET",
        url = "https://bank.invalid/transactions",
        statusCode = 200,
        responseHeadersJson = """{"Content-Type":["text/plain; charset=UTF-8"]}""",
        mediaKind = "text/plain",
        bodyEncoding = "text",
        representation = "exact_bytes",
        bodyByteCount = body.size.toLong(),
        bodySha256 = MessageDigest.getInstance("SHA-256").digest(body).toHex(),
        bodyGzip = gzip(body),
    )

    private fun gzip(body: ByteArray): ByteArray = ByteArrayOutputStream().also { output ->
        GZIPOutputStream(output).use { it.write(body) }
    }.toByteArray()

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
