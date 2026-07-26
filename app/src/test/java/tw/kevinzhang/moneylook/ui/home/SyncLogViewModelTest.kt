package tw.kevinzhang.moneylook.ui.home

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import tw.kevinzhang.core.data.db.IngestionProvenanceDao
import tw.kevinzhang.core.data.db.IngestionRunSummary
import tw.kevinzhang.core.data.db.SyncDiagnosticDao
import tw.kevinzhang.core.data.model.IngestionClassificationStatus
import tw.kevinzhang.core.data.model.IngestionRun
import tw.kevinzhang.core.data.model.IngestionStatus
import tw.kevinzhang.core.data.model.IngestionTrigger
import tw.kevinzhang.core.data.model.SourceDocument
import tw.kevinzhang.moneylook.sync.ArchivedSourceBodyReader
import tw.kevinzhang.moneylook.sync.MAX_SOURCE_BODY_CHUNK_BYTES
import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SyncLogViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loads next and previous authenticated body chunks by byte offset`() = runTest(dispatcher) {
        val body = ByteArray(MAX_SOURCE_BODY_CHUNK_BYTES + 7) { index ->
            ('a'.code + index % 26).toByte()
        }
        val document = sourceDocument(body)
        val viewModel = SyncLogViewModel(
            diagnosticDao = fakeDiagnosticDao(),
            provenanceDao = fakeProvenanceDao(document),
            bodyReader = ArchivedSourceBodyReader().apply { ioDispatcher = dispatcher },
        )

        viewModel.loadBody(document.id)
        val first = awaitChunk(viewModel, expectedStart = 0)
        assertEquals(MAX_SOURCE_BODY_CHUNK_BYTES.toLong(), first.endOffsetExclusive)
        assertTrue(first.hasNext)

        viewModel.loadNextBodyChunk(document.id)
        val second = awaitChunk(viewModel, expectedStart = MAX_SOURCE_BODY_CHUNK_BYTES.toLong())
        assertEquals(7L, second.byteCount)
        assertFalse(second.hasNext)

        viewModel.loadPreviousBodyChunk(document.id)
        val previous = awaitChunk(viewModel, expectedStart = 0)
        assertEquals(first.renderedBody, previous.renderedBody)
    }

    @Test
    fun `pages lightweight run summaries and lazy loads only selected full detail`() = runTest(dispatcher) {
        val summaries = (24 downTo 0).map(::runSummary)
        val fullDetail = runDetail(24)
        var fullDetailReads = 0
        val provenanceDao: IngestionProvenanceDao = proxy { methodName, arguments ->
            when (methodName) {
                "getRunSummaries" -> {
                    val limit = arguments!![1] as Int
                    val offset = arguments[2] as Int
                    summaries.drop(offset).take(limit)
                }
                "getIngestionRun" -> {
                    fullDetailReads += 1
                    fullDetail
                }
                "getSourceDocumentsForRun" -> emptyList<Any>()
                else -> error("Unexpected DAO call: $methodName")
            }
        }
        val viewModel = SyncLogViewModel(
            diagnosticDao = fakeDiagnosticDao(),
            provenanceDao = provenanceDao,
            bodyReader = ArchivedSourceBodyReader().apply { ioDispatcher = dispatcher },
        )

        viewModel.loadRuns("ext-1")
        val firstPage = withTimeout(5_000) {
            viewModel.runs.filter { it.size == 20 }.first()
        }
        assertEquals((24 downTo 5).map { "run-$it" }, firstPage.map { it.id })
        assertTrue(viewModel.hasMoreRuns.value)
        assertEquals(0, fullDetailReads)
        assertEquals(null, viewModel.selectedRunDetail.value)

        viewModel.loadMoreRuns()
        val allRuns = withTimeout(5_000) {
            viewModel.runs.filter { it.size == 25 }.first()
        }
        assertEquals("run-0", allRuns.last().id)
        assertFalse(viewModel.hasMoreRuns.value)
        assertEquals(0, fullDetailReads)

        viewModel.selectRun("run-24")
        val selected = withTimeout(5_000) {
            viewModel.selectedRunDetail.filter { it?.id == "run-24" }.first()
        }
        assertEquals(1, fullDetailReads)
        assertTrue(requireNotNull(requireNotNull(selected).failureStack).length > 100_000)
    }

    private suspend fun awaitChunk(
        viewModel: SyncLogViewModel,
        expectedStart: Long,
    ) = withTimeout(5_000) {
        viewModel.bodyChunks
            .filter { it["doc-1"]?.startOffset == expectedStart }
            .first()
            .getValue("doc-1")
    }

    private fun fakeDiagnosticDao(): SyncDiagnosticDao = proxy { methodName, _ ->
        when (methodName) {
            "observeByExtension" -> emptyFlow<Any>()
            else -> Unit
        }
    }

    private fun fakeProvenanceDao(document: SourceDocument): IngestionProvenanceDao =
        proxy { methodName, arguments ->
            when (methodName) {
                "getSourceDocument" -> if (arguments?.firstOrNull() == document.id) document else null
                else -> error("Unexpected DAO call: $methodName")
            }
        }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> proxy(
        crossinline call: (String, Array<out Any?>?) -> Any?,
    ): T = Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java),
    ) { _, method, args -> call(method.name, args) } as T

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
        bodyGzip = ByteArrayOutputStream().also { output ->
            GZIPOutputStream(output).use { it.write(body) }
        }.toByteArray(),
    )

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun runSummary(index: Int) = IngestionRunSummary(
        id = "run-$index",
        startedAt = index.toLong(),
        completedAt = index.toLong(),
        extensionId = "ext-1",
        extensionVersion = 3,
        trigger = "USER_SYNC",
        status = "FAILED",
        classificationStatus = "FAILED",
        accountCount = 0,
        transferCount = 0,
        failureOrigin = "SCRIPT",
        failureCode = "CODE-$index",
        failureScriptFrame = "line $index",
    )

    private fun runDetail(index: Int) = IngestionRun(
        id = "run-$index",
        startedAt = index.toLong(),
        completedAt = index.toLong(),
        extensionId = "ext-1",
        extensionVersion = 3,
        artifactRevision = null,
        artifactSha256 = null,
        trigger = IngestionTrigger.USER_SYNC,
        status = IngestionStatus.FAILED,
        classificationStatus = IngestionClassificationStatus.FAILED,
        accountCount = 0,
        transferCount = 0,
        sourceFingerprint = "fingerprint",
        fingerprintKeyVersion = 1,
        failureOrigin = "SCRIPT",
        failureCode = "CODE-$index",
        failureMessage = "message-$index",
        failureStack = "stack-$index-" + "s".repeat(100_000),
        failureDiagnosticJson = """{"index":$index}""",
        failureScriptFrame = "line $index",
    )
}
