package id.my.femasaf.secondlink

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class CameraHelper(private val context: Context) {
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    fun shutdown() {
        cameraExecutor.shutdown()
    }

    suspend fun takePhoto(isFront: Boolean, lifecycleOwner: LifecycleOwner): File? = suspendCoroutine { continuation ->
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = try {
                cameraProviderFuture.get()
            } catch (e: Exception) {
                Log.e("CameraHelper", "Failed to get camera provider", e)
                continuation.resume(null)
                return@addListener
            }

            val imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val cameraSelector = if (isFront) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }

            try {
                // Ensure everything is unbound before starting
                cameraProvider.unbindAll()
                
                // Bind use case to lifecycle
                cameraProvider.bindToLifecycle(
                    lifecycleOwner, cameraSelector, imageCapture
                )

                val photoFile = File(
                    context.cacheDir,
                    "temp_photo_${System.currentTimeMillis()}.jpg"
                )

                val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

                imageCapture.takePicture(
                    outputOptions,
                    cameraExecutor,
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onError(exc: ImageCaptureException) {
                            Log.e("CameraHelper", "Photo capture failed: ${exc.message}", exc)
                            // Release camera immediately on main thread
                            mainExecutor.execute {
                                cameraProvider.unbindAll()
                                continuation.resume(null)
                            }
                        }

                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            Log.d("CameraHelper", "Photo capture succeeded: ${photoFile.absolutePath}")
                            // Release camera immediately on main thread
                            mainExecutor.execute {
                                cameraProvider.unbindAll()
                                continuation.resume(photoFile)
                            }
                        }
                    }
                )
            } catch (exc: Exception) {
                Log.e("CameraHelper", "Use case binding failed", exc)
                cameraProvider.unbindAll()
                continuation.resume(null)
            }
        }, mainExecutor)
    }
}
