package com.tote.data

import com.tote.data.local.CaptureQueueDao
import com.tote.data.local.CaptureQueueEntity
import com.tote.data.remote.ApiService
import com.tote.data.remote.DraftDto
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import retrofit2.HttpException
import retrofit2.Response

/**
 * Drain semantics.
 *
 * Three outcomes, not two, and the third is the one that matters most here: `/items/scan` is
 * synchronous and slow, so a client-side timeout is not evidence the upload failed. Retrying it
 * automatically would catalogue the same object twice, and a duplicate in a storage catalog is
 * indistinguishable from two real ornament boxes.
 */
class CaptureQueueRepositoryTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var dao: FakeDao
    private lateinit var api: ApiService

    @Before
    fun setUp() {
        dao = FakeDao()
        api = mock()
    }

    private fun repo() = CaptureQueueRepository(dao, api)

    private var counter = 0

    private fun photoFiles(n: Int = 2): List<File> {
        val dir = tmp.newFolder("capture_${counter++}")
        return (0 until n).map { i ->
            File(dir, "photo_$i.jpg").apply { writeBytes(ByteArray(64) { it.toByte() }) }
        }
    }

    private fun draft() = DraftDto(id = "draft-1", name = "Red storage box", photoCount = 2)

    @Test
    fun `a successful drain deletes the row and the local photos`() = runTest {
        api.stub { onBlocking { scanItem(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()) } doAnswer { draft() } }
        val files = photoFiles()

        val repository = repo()
        repository.enqueue(files)

        assertTrue(repository.drain().allClear)
        assertEquals(0, dao.rows.value.size)
        // The server owns the photos now; a second copy on the phone is an invisible photo
        // library nobody manages.
        assertTrue(files.none { it.exists() })
    }

    @Test
    fun `offline keeps the row pending and asks WorkManager to come back`() = runTest {
        api.stub { onBlocking { scanItem(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()) } doAnswer { throw IOException("offline") } }
        val repository = repo()
        repository.enqueue(photoFiles())

        assertFalse(repository.drain().allClear)
        val row = dao.rows.value.single()
        assertEquals(CaptureQueueEntity.STATE_PENDING, row.state)
        assertEquals(1, row.attempts)
    }

    @Test
    fun `a rejection marks the row failed, and the drain keeps going`() = runTest {
        var calls = 0
        api.stub {
            onBlocking { scanItem(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()) } doAnswer {
                calls++
                if (calls == 1) throw httpError(422) else draft()
            }
        }
        val repository = repo()
        repository.enqueue(photoFiles())
        repository.enqueue(photoFiles())

        // A poison row must not abort the rest of the queue — one bad capture cannot hold a
        // bin's worth of good ones hostage.
        assertTrue(repository.drain().allClear)
        assertEquals(1, dao.rows.value.size)
        assertEquals(CaptureQueueEntity.STATE_FAILED, dao.rows.value.single().state)
        // The stored message is now the server's own sentence when it gave one, or this
        // honest fallback when the body was unreadable (as this mock's empty body is).
        assertEquals("The server rejected it (HTTP 422)", dao.rows.value.single().lastError)
    }

    @Test
    fun `a timeout is uncertain, not failed, and never retried automatically`() = runTest {
        api.stub {
            onBlocking { scanItem(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()) } doAnswer {
                throw SocketTimeoutException("timeout")
            }
        }
        val repository = repo()
        val files = photoFiles()
        repository.enqueue(files)

        // Reported CLEAR: WorkManager must not retry, because the server may well have processed
        // it and a second upload would file the same object twice.
        assertTrue(repository.drain().allClear)
        assertEquals(CaptureQueueEntity.STATE_UNCERTAIN, dao.rows.value.single().state)
        // And the photos survive, because the user may still need to retry by hand.
        assertTrue(files.all { it.exists() })
    }

    @Test
    fun `a subsequent drain leaves uncertain and failed rows alone`() = runTest {
        var calls = 0
        api.stub {
            onBlocking { scanItem(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()) } doAnswer {
                calls++
                throw SocketTimeoutException("timeout")
            }
        }
        val repository = repo()
        repository.enqueue(photoFiles())
        repository.drain()
        assertEquals(1, calls)

        repository.drain()
        assertEquals(1, calls, "an uncertain row must not be picked up again on its own")
    }

    @Test
    fun `a row stranded mid-upload by process death is released`() = runTest {
        api.stub { onBlocking { scanItem(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()) } doAnswer { draft() } }
        val repository = repo()
        val id = repository.enqueue(photoFiles())
        // What the process leaves behind if it dies during the ~35 s upload.
        dao.setState(id, CaptureQueueEntity.STATE_UPLOADING, 0, null)

        assertTrue(repository.drain().allClear)
        assertEquals(0, dao.rows.value.size)
    }

    @Test
    fun `retry puts a decided row back in line`() = runTest {
        api.stub { onBlocking { scanItem(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()) } doAnswer { draft() } }
        val repository = repo()
        val id = repository.enqueue(photoFiles())
        dao.setState(id, CaptureQueueEntity.STATE_FAILED, 1, "HTTP 500")

        repository.retry(id)
        val row = dao.rows.value.single()
        assertEquals(CaptureQueueEntity.STATE_PENDING, row.state)
        assertNull(row.lastError)
    }

    @Test
    fun `discard removes the row and the photos`() = runTest {
        val repository = repo()
        val files = photoFiles()
        val id = repository.enqueue(files)

        repository.discard(id)
        assertEquals(0, dao.rows.value.size)
        assertTrue(files.none { it.exists() })
    }

    @Test
    fun `what the person named it rides along, and a blank is stored as nothing`() = runTest {
        val repository = repo()
        repository.enqueue(photoFiles(), name = "  Sleepsuit  ", categoryId = "cat-1", describe = true)
        repository.enqueue(photoFiles(), name = "   ")

        val named = dao.rows.value.first()
        assertEquals("Sleepsuit", named.name)
        assertEquals("cat-1", named.categoryId)
        assertTrue(named.describe)

        // Blank is not an answer. Stored as "", the server would read it as a name the person
        // gave and file an item called nothing at all — with identification skipped, so nobody
        // and nothing would ever have named it.
        assertNull(dao.rows.value[1].name)
    }

    @Test
    fun `the destination bin rides along with the capture`() = runTest {
        api.stub { onBlocking { scanItem(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()) } doAnswer { draft() } }
        val repository = repo()
        repository.enqueue(photoFiles(), toteId = "tote-1", toteCode = "A14")

        val row = dao.rows.value.single()
        assertEquals("tote-1", row.toteId)
        // Denormalised so the queue can name the bin while offline.
        assertEquals("A14", row.toteCode)
    }

    // ── Bounded drains ───────────────────────────────────────

    @Test
    fun `a drain stops at the batch bound and reports that more is waiting`() = runTest {
        // The whole point of the bound: a run that stops short must be distinguishable from a run
        // that finished, because the caller banks one as success and backs off on the other.
        var calls = 0
        api.stub {
            onBlocking { scanItem(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()) } doAnswer {
                calls++
                draft()
            }
        }
        val repository = repo()
        repeat(3) { repository.enqueue(photoFiles()) }

        val result = repository.drain(maxItems = 2)

        assertEquals(2, calls, "the bound must actually stop the loop")
        assertTrue(result.morePending)
        // Nothing failed, so there is nothing here for WorkManager to back off against — which is
        // exactly the distinction the 2026-08-23 spiral collapsed.
        assertTrue(result.allClear)
        assertEquals(1, dao.rows.value.size)
        assertEquals(CaptureQueueEntity.STATE_PENDING, dao.rows.value.single().state)
    }

    @Test
    fun `the batch that empties the queue reports nothing more pending`() = runTest {
        api.stub { onBlocking { scanItem(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()) } doAnswer { draft() } }
        val repository = repo()
        repeat(2) { repository.enqueue(photoFiles()) }

        val result = repository.drain(maxItems = 2)

        assertTrue(result.allClear)
        assertFalse(result.morePending, "an exactly-full batch that cleared the queue is finished")
        assertEquals(0, dao.rows.value.size)
    }

    @Test
    fun `a run always attempts at least one row, however small the time budget`() = runTest {
        // Guards against a livelock: if the budget were checked in a way that could reject the
        // first row, the caller would re-enqueue for ever and the queue would never move.
        api.stub { onBlocking { scanItem(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()) } doAnswer { draft() } }
        val repository = repo()
        repeat(2) { repository.enqueue(photoFiles()) }

        val result = repository.drain(budgetMs = 1L)

        assertEquals(1, dao.rows.value.size, "exactly one row should have gone")
        assertTrue(result.morePending)
    }

    @Test
    fun `a capture whose photos have vanished fails instead of jamming the queue`() = runTest {
        // FileNotFoundException is an IOException, so without the guard this row would be marked
        // pending for ever AND hold allClear false, backing the whole queue off behind something
        // that can never succeed. Head-of-line poison wearing an outage's clothes.
        api.stub { onBlocking { scanItem(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()) } doAnswer { draft() } }
        val repository = repo()
        val doomed = photoFiles()
        repository.enqueue(doomed)
        repository.enqueue(photoFiles())
        doomed.forEach { it.delete() }

        val result = repository.drain()

        // Reported clear: there is nothing here a retry could fix, so nothing to back off for.
        assertTrue(result.allClear)
        assertFalse(result.morePending)
        val stuck = dao.rows.value.single()
        assertEquals(CaptureQueueEntity.STATE_FAILED, stuck.state)
        assertEquals("The photos for this capture are no longer on the phone.", stuck.lastError)
    }

    private fun httpError(code: Int) = HttpException(
        Response.error<Any>(
            "rejected".toResponseBody(),
            okhttp3.Response.Builder()
                .request(Request.Builder().url("http://test/items/scan").build())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("Rejected")
                .build(),
        )
    )

    // ── Replay safety ────────────────────────────────────────────────

    /** Reads back the `capture_id` part the repository sent. */
    private fun capturedKey(invocation: org.mockito.invocation.InvocationOnMock): String? {
        val body = invocation.arguments[2] as okhttp3.RequestBody?
        return body?.let { okio.Buffer().also { buffer -> it.writeTo(buffer) }.readUtf8() }
    }

    @Test
    fun `every attempt carries the row id as the capture key`() = runTest {
        // The key is what lets the server recognise a re-send as the SAME photograph. It must be
        // the row id: a freshly generated UUID per attempt would be a key that never matches,
        // which is exactly as bad as having none — and would look like it was working.
        val keys = mutableListOf<String?>()
        api.stub {
            onBlocking { scanItem(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()) } doAnswer {
                keys += capturedKey(it)
                throw IOException("connection reset after the server committed")
            }
        }

        val repository = repo()
        val id = repository.enqueue(photoFiles())

        repository.drain()
        repository.drain()

        assertEquals(listOf<String?>(id, id), keys)
    }

    @Test
    fun `a stranded row is re-sent under its original key`() = runTest {
        // The exact production sequence, 2026-08-16: the upload landed, the connection died
        // before the response arrived, the process was gone by the next drain, and
        // releaseStranded put the row back as pending. One photograph became four drafts. The
        // re-send is fine now — but only because it carries the key the first attempt used.
        val keys = mutableListOf<String?>()
        api.stub {
            onBlocking { scanItem(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()) } doAnswer {
                keys += capturedKey(it)
                draft()
            }
        }

        val repository = repo()
        val id = repository.enqueue(photoFiles())
        dao.setState(id, CaptureQueueEntity.STATE_UPLOADING, 0, null)

        assertTrue(repository.drain().allClear)
        assertEquals(listOf<String?>(id), keys)
    }

    /**
     * A hand-written fake rather than a mock: the drain's correctness is about the *sequence* of
     * state transitions, and a fake that actually holds the rows is the only thing that shows a
     * released-then-re-listed row behaving the way a real DAO would.
     */
    private class FakeDao : CaptureQueueDao {
        val rows = MutableStateFlow<List<CaptureQueueEntity>>(emptyList())

        override suspend fun upsert(entity: CaptureQueueEntity) {
            rows.value = rows.value.filterNot { it.id == entity.id } + entity
        }

        override fun observeAll(): Flow<List<CaptureQueueEntity>> = rows

        override suspend fun listUploadable(state: String): List<CaptureQueueEntity> =
            rows.value.filter { it.state == state }.sortedBy { it.createdAtMs }

        override suspend fun byId(id: String): CaptureQueueEntity? =
            rows.value.firstOrNull { it.id == id }

        override suspend fun setState(id: String, state: String, attempts: Int, lastError: String?) {
            rows.value = rows.value.map {
                if (it.id == id) it.copy(state = state, attempts = attempts, lastError = lastError)
                else it
            }
        }

        override suspend fun delete(id: String) {
            rows.value = rows.value.filterNot { it.id == id }
        }

        override fun observeCount(): Flow<Int> = rows.map { it.size }

        override suspend fun releaseStranded(pending: String, uploading: String) {
            rows.value = rows.value.map {
                if (it.state == uploading) it.copy(state = pending) else it
            }
        }
    }
}
