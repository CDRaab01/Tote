package com.tote.ui.books

import android.content.Context
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * One barcode read, or null if the person backed out.
 *
 * A seam, not an abstraction for its own sake: the real implementation is Google's code
 * scanner, which lives in Play Services and cannot run under Robolectric at all — so every
 * test of the scanning session drives a scripted fake through this interface, and the GMS
 * class below stays exactly as thin as something untestable has to be.
 */
fun interface BookBarcodeScanner {
    suspend fun scan(): String?
}

/**
 * The Play Services code scanner: full-screen scanning UI provided by GMS, no camera
 * permission needed by the app, no preview surface to own — which matters here because Tote
 * has never had one (capture is a TakePicture intent).
 *
 * EAN-13 only. Every retail barcode a shelf can offer is EAN-13; restricting the formats is
 * what keeps the scanner from cheerfully reading the QR code on a tote's own index card
 * mid-session.
 */
@Singleton
class GmsBookBarcodeScanner @Inject constructor(
    @ApplicationContext private val context: Context,
) : BookBarcodeScanner {

    private val scanner by lazy {
        GmsBarcodeScanning.getClient(
            context,
            GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_EAN_13)
                .build(),
        )
    }

    override suspend fun scan(): String? = suspendCancellableCoroutine { cont ->
        scanner.startScan()
            .addOnSuccessListener { barcode -> cont.resume(barcode.rawValue) }
            // Cancellation (back press, swipe away) and failure both end the read quietly:
            // the session loop treats null as "the person is done scanning", which is also
            // the honest reading of a scanner that could not deliver.
            .addOnCanceledListener { cont.resume(null) }
            .addOnFailureListener { cont.resume(null) }
    }

    companion object {
        /** Fire-and-forget hint to fetch the GMS module before the first real scan. */
        fun warmUp(context: Context) {
            runCatching {
                ModuleInstall.getClient(context)
                    .installModules(
                        com.google.android.gms.common.moduleinstall.ModuleInstallRequest
                            .newBuilder()
                            .addApi(GmsBarcodeScanning.getClient(context))
                            .build()
                    )
            }
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BookScannerModule {
    @Binds
    abstract fun bindScanner(impl: GmsBookBarcodeScanner): BookBarcodeScanner
}
