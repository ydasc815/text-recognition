package com.aditya.text_recognition

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.MutableLiveData
import com.aditya.text_recognition.databinding.ActivityCameraBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import kotlinx.android.synthetic.main.activity_camera.*
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.collections.ArrayList

class CameraActivity : AppCompatActivity(), TextRecognizerListener {
    private lateinit var binding: ActivityCameraBinding
    private var imageCapture: ImageCapture? = null
    private lateinit var outputDirectory: File
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var listener: TextRecognizerListener
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DataBindingUtil.setContentView(this, R.layout.activity_camera)

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        binding.cameraCaptureButton.setOnClickListener { takePhoto() }

        outputDirectory = getOutputDirectory()

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults:
        IntArray) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?:return
        val photoFile = File(outputDirectory, SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis()) + ".jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
        imageCapture.takePicture(
            outputOptions, ContextCompat.getMainExecutor(this), object: ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val savedUri = Uri.fromFile(photoFile)
                    val image: InputImage
                    try {
                        image = InputImage.fromFilePath(this@CameraActivity, savedUri)
                        val recognizer = TextRecognition.getClient()
                        val result = recognizer.process(image)
                            .addOnSuccessListener {
                                listener.text(it.text)
                                Log.d(TAG, "text: ${it.text}")
                            }
                            .addOnFailureListener {
                                Log.d(TAG, "Text recognition failed with exception: $it")
                            }
                        val labeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
                        labeler.process(image)
                            .addOnSuccessListener {
                                val list : MutableList<String> = ArrayList()
                                for(i in 0 until it.size) {
                                    list.add(it[i].text)
                                }
                                listener.text("Object list : $list")
                            }
                            .addOnFailureListener {
                                Log.d(TAG, "Object detection failed with exception: $it")
                            }
                    } catch(e: IOException) {
                        e.printStackTrace()
                    }
                    val msg = "Photo Captured ! Saved at location : $savedUri"
                    Toast.makeText(this@CameraActivity, msg, Toast.LENGTH_SHORT).show()
                    Log.d(TAG, msg)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exception.message}")
                    Toast.makeText(this@CameraActivity, "Photo capture failed: ${exception.message}", Toast.LENGTH_SHORT).show()
                }

            }
        )
    }

    @SuppressLint("ClickableViewAccessibility", "RestrictedApi", "UnsafeOptInUsageError")
    private fun startCamera() {
        listener = this
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener(Runnable{
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                }
            imageCapture = ImageCapture.Builder().build()
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
            val camera = cameraProvider.bindToLifecycle(
                this@CameraActivity, cameraSelector, viewFinder.viewPort //this is a PreviewView
            )
            binding.viewFinder.afterMeasured {
                binding.viewFinder.setOnTouchListener { _, event ->
                    return@setOnTouchListener when (event.action) {
                        MotionEvent.ACTION_DOWN -> { true }
                        MotionEvent.ACTION_UP -> {
                            val factory: MeteringPointFactory = SurfaceOrientedMeteringPointFactory(
                                binding.viewFinder.width.toFloat(), binding.viewFinder.height.toFloat()
                            )
                            val autoFocusPoint = factory.createPoint(event.x, event.y)
                            try {
                                camera.cameraControl.startFocusAndMetering(
                                    FocusMeteringAction.Builder(
                                        autoFocusPoint,
                                        FocusMeteringAction.FLAG_AF
                                    ).apply {
                                        disableAutoCancel()
                                    }.build()
                                )
                            } catch (e: CameraInfoUnavailableException) {
                                Log.d("ERROR", "cannot access camera", e)
                            }
                            true
                        }
                        else -> false
                    }
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private fun getOutputDirectory(): File {
        val mediaDir = externalMediaDirs.firstOrNull()?.let {
            File(it, resources.getString(R.string.app_name)).apply { mkdirs() } }
        return if (mediaDir != null && mediaDir.exists())
            mediaDir else filesDir
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "CameraXBasic"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

    override fun text(str: String) {
        Toast.makeText(this, str, Toast.LENGTH_LONG).show()
    }

    private inline fun View.afterMeasured(crossinline block: () -> Unit) {
        viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (measuredWidth > 0 && measuredHeight > 0) {
                    viewTreeObserver.removeOnGlobalLayoutListener(this)
                    block()
                }
            }
        })
    }
}