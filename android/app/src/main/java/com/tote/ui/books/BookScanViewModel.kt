package com.tote.ui.books

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tote.data.CatalogRepository
import com.tote.data.local.CachedTote
import com.tote.data.remote.ApiService
import com.tote.data.remote.ScanIsbnRequest
import com.tote.util.ApiErrors
import com.tote.util.FeedbackBus
import com.tote.util.isBookEan13
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One scanned book's place in the session. */
enum class BookRowStatus { LOOKING_UP, FILED, NOT_FOUND, FAILED }

data class BookRow(
    /** Client-minted, and the idempotency key: Retry re-sends THIS id, so a lost response can
     *  never file the book twice. */
    val captureId: String,
    val isbn: String,
    val status: BookRowStatus,
    val title: String? = null,
    val author: String? = null,
    /** Set once filed — what lets the row show the cover the server just stored. */
    val itemId: String? = null,
    val hasCover: Boolean = false,
    val error: String? = null,
)

data class BookScanState(
    val toteId: String? = null,
    val toteCode: String? = null,
    /** Newest first — the row you just scanned is the one you are watching. */
    val rows: List<BookRow> = emptyList(),
    val scanning: Boolean = false,
)

/**
 * The shelf-scanning session: scan, scan, scan — each book files itself while the scanner is
 * already back up for the next one.
 *
 * The loop is one-shot scans auto-relaunched, not a continuous camera session: the GMS scanner
 * is modal and returns one code, and relaunching it immediately gives the same rhythm while the
 * previous book's network call runs behind the modal. Backing out of the scanner ends the loop
 * and leaves the session list on screen.
 *
 * Two guards live HERE, before any network:
 * - a non-Bookland EAN-13 (the soup can next to the shelf) is announced and dropped;
 * - an ISBN already scanned this session is announced and skipped — two copies of one book is
 *   what the quantity field is for, and silently filing twice is the storage-catalogue sin.
 */
@HiltViewModel
class BookScanViewModel @Inject constructor(
    private val api: ApiService,
    private val repo: CatalogRepository,
    private val feedback: FeedbackBus,
    private val scanner: BookBarcodeScanner,
) : ViewModel() {

    private val _state = MutableStateFlow(BookScanState())
    val state: StateFlow<BookScanState> = _state.asStateFlow()

    /** The destination picker's options — the same cached bins capture uses. */
    val bins: StateFlow<List<CachedTote>> = repo.cachedTotes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setDestination(toteId: String?, toteCode: String?) {
        _state.value = _state.value.copy(toteId = toteId, toteCode = toteCode)
    }

    /** Start (or resume) the scan loop. Runs until the person backs out of the scanner. */
    fun startScanning() {
        if (_state.value.scanning) return
        _state.value = _state.value.copy(scanning = true)
        viewModelScope.launch {
            try {
                while (true) {
                    val code = scanner.scan() ?: break
                    onBarcode(code)
                }
            } finally {
                _state.value = _state.value.copy(scanning = false)
            }
        }
    }

    /** One barcode through the guards and, if it survives them, off to the server. */
    fun onBarcode(code: String) {
        val isbn = code.trim().replace("-", "").replace(" ", "")
        if (!isBookEan13(isbn)) {
            feedback.say("That's a product barcode, not a book")
            return
        }
        val already = _state.value.rows.firstOrNull { it.isbn == isbn }
        if (already != null) {
            val name = already.title ?: "that one"
            feedback.say("Already scanned — $name")
            return
        }
        val row = BookRow(
            captureId = UUID.randomUUID().toString(),
            isbn = isbn,
            status = BookRowStatus.LOOKING_UP,
        )
        _state.value = _state.value.copy(rows = listOf(row) + _state.value.rows)
        lookUp(row)
    }

    /** Re-send a FAILED row. Same captureId, so a retry can never file the book twice. */
    fun retry(captureId: String) {
        val row = _state.value.rows.firstOrNull { it.captureId == captureId } ?: return
        if (row.status != BookRowStatus.FAILED) return
        update(row.captureId) { it.copy(status = BookRowStatus.LOOKING_UP, error = null) }
        lookUp(row)
    }

    private fun lookUp(row: BookRow) {
        val toteId = _state.value.toteId
        viewModelScope.launch {
            runCatching {
                api.scanIsbn(ScanIsbnRequest(isbn = row.isbn, toteId = toteId, captureId = row.captureId))
            }.onSuccess { response ->
                val item = response.item
                if (response.found) {
                    update(row.captureId) {
                        it.copy(
                            status = BookRowStatus.FILED,
                            title = item.name,
                            author = item.description,
                            itemId = item.id,
                            hasCover = item.photoCount > 0,
                        )
                    }
                    // Quiet, non-forced: the write already returned its own row, and concurrent
                    // refreshes collapse — this just keeps bin counts honest for the next tab.
                    runCatching { repo.refresh() }
                } else {
                    update(row.captureId) {
                        it.copy(status = BookRowStatus.NOT_FOUND, itemId = item.id)
                    }
                }
            }.onFailure { e ->
                update(row.captureId) {
                    it.copy(
                        status = BookRowStatus.FAILED,
                        error = ApiErrors.detail(e)
                            ?: ApiErrors.message(e, "Couldn't look that one up."),
                    )
                }
            }
        }
    }

    private fun update(captureId: String, transform: (BookRow) -> BookRow) {
        _state.value = _state.value.copy(
            rows = _state.value.rows.map { if (it.captureId == captureId) transform(it) else it }
        )
    }
}
