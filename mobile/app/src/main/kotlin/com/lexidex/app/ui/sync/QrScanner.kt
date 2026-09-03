package com.lexidex.app.ui.sync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.lexidex.app.data.pairing.QrDecoder
import com.lexidex.app.ui.theme.LexidexSpacing
import java.util.concurrent.Executors

/** True cuando el telefono tiene camara trasera; sin ella no se ofrece escanear. */
fun deviceCanScan(context: Context): Boolean =
    context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

/**
 * Escanea el codigo de emparejamiento con la camara.
 *
 * Es un atajo, no un camino nuevo: lo que entra es exactamente el mismo texto que se pega a mano,
 * y el emparejamiento lo valida igual. Por eso pegar sigue estando, y por eso este dialogo puede
 * fallar -sin permiso, sin camara- sin dejar al usuario sin forma de emparejar.
 *
 * [onScanned] se llama **una sola vez**: la camara entrega cuadros sin parar y el mismo QR se
 * decodifica muchas veces por segundo, asi que sin esa guarda se dispararian varios
 * emparejamientos con el mismo token, que es de un solo uso.
 */
@Composable
fun QrScannerDialog(onScanned: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var denied by remember { mutableStateOf(false) }

    val request = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { allowed ->
        granted = allowed
        denied = !allowed
    }

    LaunchedEffect(Unit) {
        if (!granted) request.launch(Manifest.permission.CAMERA)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Escanear el codigo") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(LexidexSpacing.compact)) {
                when {
                    granted -> {
                        Text(
                            "Apunta a la pantalla de la computadora, al codigo que muestra Lexidex.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        CameraPreview(onScanned = onScanned)
                    }

                    denied -> Text(
                        "Sin permiso de camara no se puede escanear. Podes pegar el codigo a mano, " +
                            "que hace exactamente lo mismo.",
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    else -> Text(
                        "Pidiendo permiso para usar la camara...",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}

/**
 * La vista de la camara y el analisis de sus cuadros.
 *
 * El analisis corre en su propio hilo -`KEEP_ONLY_LATEST` descarta lo que se acumule- para que
 * decodificar un QR denso no trabe la vista previa. `DisposableEffect` desata la camara al cerrar
 * el dialogo: dejarla atada seguiria consumiendo bateria con el dialogo cerrado.
 */
@Composable
private fun CameraPreview(onScanned: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnScanned by rememberUpdatedState(onScanned)
    val decoder = remember { QrDecoder() }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val previewView = remember { PreviewView(context) }
    // Una vez y no mas: el token del emparejamiento es de un solo uso.
    val alreadyScanned = remember { java.util.concurrent.atomic.AtomicBoolean(false) }

    AndroidView(
        factory = { previewView },
        modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f).padding(top = LexidexSpacing.tight),
    )

    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        val listener = Runnable {
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(executor, decodeFrames(decoder, alreadyScanned) { text ->
                    currentOnScanned(text)
                }) }

            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            }
        }
        future.addListener(listener, ContextCompat.getMainExecutor(context))

        onDispose {
            runCatching { future.get().unbindAll() }
            executor.shutdown()
        }
    }
}

private fun decodeFrames(
    decoder: QrDecoder,
    alreadyScanned: java.util.concurrent.atomic.AtomicBoolean,
    onScanned: (String) -> Unit,
) = ImageAnalysis.Analyzer { image: ImageProxy ->
    try {
        if (alreadyScanned.get()) return@Analyzer
        val plane = image.planes.firstOrNull() ?: return@Analyzer
        val buffer = plane.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        val text = decoder.decode(bytes, image.width, image.height, plane.rowStride)
        if (text != null && alreadyScanned.compareAndSet(false, true)) {
            onScanned(text)
        }
    } finally {
        // Sin cerrar el cuadro la camara deja de entregar mas, y la vista previa se congela.
        image.close()
    }
}
