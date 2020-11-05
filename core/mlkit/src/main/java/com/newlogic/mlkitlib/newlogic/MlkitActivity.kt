package com.newlogic.mlkitlib.newlogic

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.util.Size
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.View.*
import android.view.Window
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.material.snackbar.Snackbar
import com.google.gson.Gson
import com.google.mlkit.vision.barcode.Barcode.FORMAT_CODE_39
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.googlecode.tesseract.android.TessBaseAPI
import com.newlogic.mlkitlib.R
import com.newlogic.mlkitlib.databinding.ActivityMrzBinding
import com.newlogic.mlkitlib.innovatrics.barcode.BarcodeResult
import com.newlogic.mlkitlib.innovatrics.mrz.MRZResult
import com.newlogic.mlkitlib.innovatrics.mrz.MRZResult.Companion.formatMrtdTd1MrzResult
import com.newlogic.mlkitlib.innovatrics.mrz.MRZResult.Companion.formatMrzResult
import com.newlogic.mlkitlib.newlogic.config.*
import com.newlogic.mlkitlib.newlogic.config.ImageResultType.BASE_64
import com.newlogic.mlkitlib.newlogic.config.Modes.BARCODE
import com.newlogic.mlkitlib.newlogic.config.MrzFormat.MRTD_TD1
import com.newlogic.mlkitlib.newlogic.extension.cacheImageToLocal
import com.newlogic.mlkitlib.newlogic.extension.encodeBase64
import com.newlogic.mlkitlib.newlogic.extension.getConnectionType
import com.newlogic.mlkitlib.newlogic.extension.toBitmap
import com.newlogic.mlkitlib.newlogic.platform.AnalyzerType
import com.newlogic.mlkitlib.newlogic.platform.MRZCleaner
import com.newlogic.mlkitlib.newlogic.platform.SmartScannerAnalyzer
import com.newlogic.mlkitlib.newlogic.utils.CameraUtils.isLedFlashAvailable
import com.newlogic.mlkitlib.newlogic.utils.FileUtils.copyAssets
import java.io.File
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlin.math.abs

class MLKitActivity : AppCompatActivity(), OnClickListener {

    companion object {
        val TAG: String = MLKitActivity::class.java.simpleName
        const val MLKIT_RESULT = "MLKIT_RESULT"
        const val CONFIG = "CONFIG"
        const val MODE = "MODE"
        const val MRZ_FORMAT = "MRZ_FORMAT"
        const val BARCODE_OPTIONS = "BARCODE_OPTIONS"

        fun defaultBarcodeOptions() = BarcodeOptions.default
        fun defaultConfig() = Config.default
    }

    private val REQUEST_CODE_PERMISSIONS = 10
    private val REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    )
    private val clickThreshold = 5

    private var x = 0f
    private var y = 0f
    private var config: Config? = null
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var startScanTime: Long = 0
    private var mode: String? = null
    private var barcodeFormats: List<Int> = listOf()
    private var barcodeOptions: List<String> = listOf()
    private var mrzFormat: String? = null
    private var flashButton: View? = null
    private var closeButton: View? = null
    private var isMLKitUsable = true

    private lateinit var binding : ActivityMrzBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var context: Context
    private lateinit var outputDirectory: File
    private lateinit var tessBaseAPI: TessBaseAPI

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        binding = ActivityMrzBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)
        // get application context
        context = applicationContext
        // assign layout ids
        flashButton = findViewById(R.id.flash_button)
        closeButton = findViewById(R.id.close_button)
        // hide actionbar
        supportActionBar?.hide()
        supportActionBar?.setDisplayShowTitleEnabled(false)
        actionBar?.hide()
        actionBar?.setDisplayShowTitleEnabled(false)
        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
        // mode to use
        mode = intent.getStringExtra(MODE)
        // mrz format to use
        mrzFormat = intent.getStringExtra(MRZ_FORMAT) ?: MrzFormat.MRP.value
        // barcode format to use
        barcodeOptions = intent.getStringArrayListExtra(BARCODE_OPTIONS) ?: defaultBarcodeOptions()
        barcodeFormats = barcodeOptions.map { BarcodeOptions.valueOf(it).value }
        // setup config for reader
        config = intent.getParcelableExtra(CONFIG)
        setupConfiguration(config ?: defaultConfig())
        // assign click listeners
        closeButton?.setOnClickListener(this)
        flashButton?.setOnClickListener(this)
        // directory output paths
        outputDirectory = getOutputDirectory()
        cameraExecutor = Executors.newSingleThreadExecutor()
        isMLKitUsable = checkGooglePlayServices()
        Log.d(TAG, "isMLKitUsable: $isMLKitUsable")
        // Initialize Tesseract
        if (mode == Modes.MRZ.value && !isMLKitUsable) {
            val extDirPath: String = getExternalFilesDir(null)!!.absolutePath
            Log.d(TAG, "path: $extDirPath")
            copyAssets(this, "tessdata", extDirPath)
            tessBaseAPI = TessBaseAPI()
            tessBaseAPI.init(extDirPath, "ocrb_int", TessBaseAPI.OEM_DEFAULT)
            tessBaseAPI.pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK
        }
    }

    private fun setupConfiguration(config: Config) {
        // flash
        flashButton?.visibility = if (isLedFlashAvailable(this)) VISIBLE else GONE
        // capture text label
        binding.captureLabelText.text = config.label
        // font to use
        binding.captureLabelText.typeface =
            if (config.font == Fonts.NOTO_SANS_ARABIC.value) ResourcesCompat.getFont(
                this,
                R.font.notosansarabic_bold
            )
            else ResourcesCompat.getFont(this, R.font.sourcesanspro_bold)
        // Background reader
        try {
            config.background?.let {
                if (it.isNotEmpty()) {
                    val color = Color.parseColor(config.background)
                    binding.coordinatorLayout.setBackgroundColor(color)
                }
            } ?: run {
                binding.coordinatorLayout.setBackgroundColor(ContextCompat.getColor(this, R.color.transparent_grey))
            }

        } catch (iae: IllegalArgumentException) {
            // This color string is not valid
            binding.coordinatorLayout.setBackgroundColor(ContextCompat.getColor(this, R.color.transparent_grey))
        }
        // barcode view layout
        if (mode == BARCODE.value) {
            val layoutParams = binding.viewLayout.layoutParams as ConstraintLayout.LayoutParams
            when {
                isPdf417(barcodeOptions) -> {
                    layoutParams.dimensionRatio = "9:21"
                    binding.viewLayout.layoutParams = layoutParams
                }
                isQrCodeOnly(barcodeOptions)  -> {
                    layoutParams.marginStart = 72 // Approx. 36dp
                    layoutParams.marginEnd = 72 // Approx. 36dp
                    binding.viewLayout.layoutParams = layoutParams
                }
                else -> {
                    layoutParams.marginStart = 64 // Approx. 30dp
                    layoutParams.marginEnd = 64 // Approx. 30dp
                    binding.viewLayout.layoutParams = layoutParams
                }
            }
        }
        // branding
        binding.brandingImage.visibility = config.branding?.let { if (it) VISIBLE else GONE } ?: run { GONE }
        // manual capture
        binding.manualCapture.visibility = config.isManualCapture?.let { if (it) VISIBLE else GONE } ?: run { GONE }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                val snackBar: Snackbar = Snackbar.make(
                    binding.coordinatorLayout,
                    R.string.required_perms_not_given, Snackbar.LENGTH_INDEFINITE
                )
                snackBar.setAction(R.string.settings) { openSettingsApp() }
                snackBar.show()
            }
        }
    }

    private fun openSettingsApp() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        val uri = Uri.fromParts("package", packageName, null)
        intent.data = uri
        startActivity(intent)
    }

    private fun startCamera() {
        this.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener( {
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            // Preview
            preview = Preview.Builder().build()
            var size = Size(480, 640)
            if (isPdf417(barcodeOptions)) size = Size(1080, 1920)
            imageAnalyzer = ImageAnalysis.Builder()
                .setTargetResolution(size)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, getMrzAnalyzer())
                }
            // Create configuration object for the image capture use case
            imageCapture = ImageCapture.Builder()
                .setTargetResolution(size)
                .setTargetRotation(Surface.ROTATION_0)
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            binding.manualCapture.setOnClickListener {
                val imageFile = File(getImagePath())
                val outputFileOptions = ImageCapture.OutputFileOptions.Builder(imageFile).build()
                imageCapture?.takePicture(outputFileOptions, ContextCompat.getMainExecutor(
                    baseContext), object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        val data = Intent()
                        val gson = Gson()
                        val bf = imageFile.path.toBitmap()
                        bf.cacheImageToLocal(imageFile.path,90)
                        val imageString = if (config?.imageResultType == BASE_64.value) bf.encodeBase64() else imageFile.path
                        val result = gson.toJson(MRZResult.getImageOnly(imageString))
                        data.putExtra(MLKIT_RESULT, result)
                        setResult(Activity.RESULT_OK, data)
                        finish()
                    }
                    override fun onError(exception: ImageCaptureException) {
                        exception.printStackTrace()
                    }
                })
            }
            // Select back camera
            val cameraSelector = CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).build()
            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()
                // Bind use cases to camera
                camera = cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalyzer,
                    imageCapture
                )
                preview?.setSurfaceProvider(binding.viewFinder.createSurfaceProvider())
                Log.d(
                    TAG,
                    "Measured size: ${binding.viewFinder.width}x${binding.viewFinder.height}"
                )
                startScanTime = System.currentTimeMillis()
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun isPdf417(options: List<String>) = options.any { it == BarcodeOptions.PDF_417.label }
    private fun isQrCodeOnly(options: List<String>) = options.size <= 1 && options.any { it == BarcodeOptions.QR_CODE.label }

    @SuppressLint("UnsafeExperimentalUsageError")
    private fun getMrzAnalyzer(): SmartScannerAnalyzer {
        var barcodeBusy = false
        var mrzBusy = false

        return SmartScannerAnalyzer { imageProxy ->
            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                val rot = imageProxy.imageInfo.rotationDegrees
                val bf = mediaImage.toBitmap(rot)
                val cropped = if (rot == 90 || rot == 270) Bitmap.createBitmap(
                    bf,
                    bf.width / 2,
                    0,
                    bf.width / 2,
                    bf.height
                )
                else Bitmap.createBitmap(bf, 0, bf.height / 2, bf.width, bf.height / 2)
                Log.d(TAG, "Bitmap: (${mediaImage.width}, ${mediaImage.height}) Cropped: (${cropped.width}, ${cropped.height}), Rotation: ${imageProxy.imageInfo.rotationDegrees}")

                //barcode, qr and pdf417
                if (!barcodeBusy &&  (mode == BARCODE.value)) {
                    barcodeBusy = true
                    Log.d("$TAG/MLKit", "barcode: mode is $mode")
                    val start = System.currentTimeMillis()
                    var barcodeFormat = FORMAT_CODE_39 // Most common barcode format
                    barcodeFormats.forEach {
                        barcodeFormat = it or barcodeFormat // bitwise different barcode format options
                    }
                    val options = BarcodeScannerOptions.Builder().setBarcodeFormats(barcodeFormat).build()
                    val image = InputImage.fromBitmap(bf, imageProxy.imageInfo.rotationDegrees)
                    val scanner = BarcodeScanning.getClient(options)
                    Log.d("$TAG/MLKit", "barcode: process")
                    scanner.process(image)
                        .addOnSuccessListener { barcodes ->
                            val timeRequired = System.currentTimeMillis() - start
                            val rawValue: String
                            val cornersString: String
                            Log.d("$TAG/MLKit", "barcode: success: $timeRequired ms")
                            if (barcodes.isNotEmpty()) {
                                //                                val bounds = barcode.boundingBox
                                val corners = barcodes[0].cornerPoints
                                val builder = StringBuilder()
                                if (corners != null) {
                                    for (corner in corners) {
                                        builder.append("${corner.x},${corner.y} ")
                                    }
                                }
                                cornersString = builder.toString()
                                rawValue = barcodes[0].rawValue!!
                                //val valueType = barcode.valueType
                                val imageCachePathFile = getImagePath()
                                bf.cacheImageToLocal(
                                    imageCachePathFile,
                                    imageProxy.imageInfo.rotationDegrees
                                )
                                val gson = Gson()
                                val jsonString = gson.toJson(
                                    BarcodeResult(
                                        imageCachePathFile,
                                        cornersString,
                                        rawValue
                                    )
                                )
                                getAnalyzerResult(AnalyzerType.BARCODE, jsonString)
                            } else {
                                Log.d("$TAG/MLKit", "barcode: nothing detected")
                            }
                            barcodeBusy = false
                        }
                        .addOnFailureListener { e ->
                            Log.d("$TAG/MLKit", "barcode: failure: ${e.message}")
                            barcodeBusy = false
                        }
                }
                //MRZ
                if (!mrzBusy &&  (mode == Modes.MRZ.value)) {
                    val mlStartTime = System.currentTimeMillis()
                    if (isMLKitUsable) {
                        mrzBusy = true
                        val image = InputImage.fromBitmap(
                            cropped,
                            imageProxy.imageInfo.rotationDegrees
                        )
                        // Pass image to an ML Kit Vision API
                        val recognizer = TextRecognition.getClient()
                        Log.d("$TAG/MLKit", "TextRecognition: process")
                        val start = System.currentTimeMillis()
                        recognizer.process(image)
                            .addOnSuccessListener { visionText ->
                                binding.modelText.visibility = INVISIBLE
                                val timeRequired = System.currentTimeMillis() - start
                                Log.d("$TAG/MLKit", "TextRecognition: success: $timeRequired ms")
                                var rawFullRead = ""
                                val blocks = visionText.textBlocks
                                for (i in blocks.indices) {
                                    val lines = blocks[i].lines
                                    for (j in lines.indices) {
                                        if (lines[j].text.contains('<')) {
                                            rawFullRead += lines[j].text + "\n"
                                        }
                                    }
                                }
                                binding.rectimage.isSelected = rawFullRead != ""
                                try {
                                    Log.d(
                                        "$TAG/MLKit",
                                        "Before cleaner: [${
                                            URLEncoder.encode(rawFullRead, "UTF-8")
                                                .replace("%3C", "<").replace("%0A", "↩")
                                        }]"
                                    )
                                    val mrz = MRZCleaner.clean(rawFullRead)
                                    Log.d(
                                        "$TAG/MLKit",
                                        "After cleaner = [${
                                            URLEncoder.encode(mrz, "UTF-8")
                                                .replace("%3C", "<").replace("%0A", "↩")
                                        }]"
                                    )
                                    val imagePathFile = getImagePath()
                                    bf.cacheImageToLocal(
                                        imagePathFile,
                                        imageProxy.imageInfo.rotationDegrees
                                    )
                                    val imageString = if (config?.imageResultType == BASE_64.value) bf.encodeBase64(
                                        imageProxy.imageInfo.rotationDegrees
                                    ) else imagePathFile
                                    val mrzResult: MRZResult = when (mrzFormat) {
                                        MRTD_TD1.value -> formatMrtdTd1MrzResult(
                                            MRZCleaner.parseAndCleanMrtdTd1(
                                                mrz
                                            ), imageString
                                        )
                                        else -> formatMrzResult(
                                            MRZCleaner.parseAndClean(mrz),
                                            imageString
                                        )
                                    }
                                    // record to json
                                    val gson = Gson()
                                    val jsonString = gson.toJson(mrzResult)
                                    getAnalyzerResult(AnalyzerType.MRZ, jsonString)
                                } catch (e: Exception) { // MrzParseException, IllegalArgumentException
                                    Log.d("$TAG/MLKit", e.toString())
                                }
                                getAnalyzerStat(
                                    mlStartTime,
                                    System.currentTimeMillis()
                                )
                                mrzBusy = false
                            }
                            .addOnFailureListener { e ->
                                Log.d("$TAG/MLKit", "TextRecognition: failure: ${e.message}")
                                if (getConnectionType() == 0) {
                                    binding.modelText.text = context.getString(R.string.connection_text)
                                } else {
                                    binding.modelText.text = context.getString(R.string.model_text)
                                }
                                binding.modelText.visibility = VISIBLE
                                getAnalyzerStat(
                                    mlStartTime,
                                    System.currentTimeMillis()
                                )
                                mrzBusy = false
                            }
                    } else {
                        thread(start = true) {
                            mrzBusy = true
                            // Tesseract
                            val matrix = Matrix()
                            matrix.postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
                            val rotatedBitmap = Bitmap.createBitmap(
                                cropped,
                                0,
                                0,
                                cropped.width,
                                cropped.height,
                                matrix,
                                true
                            )
                            tessBaseAPI.setImage(rotatedBitmap)
                            val tessResult = tessBaseAPI.utF8Text
                            try {
                                Log.d(
                                    "$TAG/Tesseract",
                                    "Before cleaner: [${
                                        URLEncoder.encode(tessResult, "UTF-8")
                                            .replace("%3C", "<").replace("%0A", "↩")
                                    }]"
                                )
                                val mrz = MRZCleaner.clean(tessResult)
                                Log.d(
                                    "$TAG/Tesseract",
                                    "After cleaner = [${
                                        URLEncoder.encode(mrz, "UTF-8")
                                            .replace("%3C", "<")
                                            .replace("%0A", "↩")
                                    }]"
                                )
                                val imagePathFile = getImagePath()
                                bf.cacheImageToLocal(
                                    imagePathFile,
                                    imageProxy.imageInfo.rotationDegrees
                                )
                                val imageString = if (config?.imageResultType == BASE_64.value) bf.encodeBase64(
                                    imageProxy.imageInfo.rotationDegrees
                                ) else imagePathFile
                                val record = MRZCleaner.parseAndClean(mrz)
                                val gson = Gson()
                                val jsonString = gson.toJson(
                                    MRZResult(
                                        imageString,
                                        record.code.toString(),
                                        record.code1.toShort(),
                                        record.code2.toShort(),
                                        record.dateOfBirth.toString().replace(Regex("[{}]"), ""),
                                        record.documentNumber.toString(),
                                        record.expirationDate.toString().replace(Regex("[{}]"), ""),
                                        record.format.toString(),
                                        record.givenNames,
                                        record.issuingCountry,
                                        record.nationality,
                                        record.sex.toString(),
                                        record.surname,
                                        record.toMrz()
                                    )
                                )
                                getAnalyzerResult(AnalyzerType.MRZ, jsonString)
                            } catch (e: Exception) { // MrzParseException, IllegalArgumentException
                                Log.d("$TAG/Tesseract", e.toString())
                            }
                            getAnalyzerStat(
                                mlStartTime,
                                System.currentTimeMillis()
                            )
                            mrzBusy = false
                        }
                    }
                }
            }
            imageProxy.close()
        }
    }

    private fun getImagePath(): String {
        val date = Calendar.getInstance().time
        val formatter = SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT)
        val currentDateTime = formatter.format(date)
        return "${context.cacheDir}/Scanner-$currentDateTime.jpg"
    }

    private fun getAnalyzerResult(analyzerType: AnalyzerType, result: String) {
        val data = Intent()
        runOnUiThread {
            when (analyzerType) {
                AnalyzerType.MRZ -> {
                    Log.d(TAG, "Success from MLKit")
                    Log.d(TAG, "value: $result")
                    binding.mlkitText.text = result
                }
                AnalyzerType.BARCODE -> {
                    Log.d(TAG, "Success from BARCODE")
                    Log.d(TAG, "value: $result")
                }
                AnalyzerType.TESSERACT -> {
                    Log.d(TAG, "Success from TESSERACT")
                    Log.d(TAG, "value: $result")
                    binding.mlkitText.text = result
                }
            }
            data.putExtra(MLKIT_RESULT, result)
            setResult(Activity.RESULT_OK, data)
            finish()
        }
    }

    private fun getAnalyzerStat(startTime: Long, endTime: Long) {
        runOnUiThread {
            val analyzerTime = endTime - startTime
            binding.mlkitText.text = "Frame processing time: $analyzerTime ms"
            val scanTime = ((System.currentTimeMillis().toDouble() - startScanTime.toDouble()) / 1000)
            binding.mlkitText.text = "Total scan time: $scanTime s"
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun getOutputDirectory(): File {
        val mediaDir = externalMediaDirs.firstOrNull()?.let { File(
            it,
            resources.getString(R.string.app_name)
        ).apply { mkdirs() } }
        return if (mediaDir != null && mediaDir.exists()) mediaDir else filesDir
    }

    private fun checkGooglePlayServices(showErrorDialog: Boolean = false): Boolean {
        val availability = GoogleApiAvailability.getInstance()
        val resultCode = availability.isGooglePlayServicesAvailable(this)
        if (resultCode != ConnectionResult.SUCCESS) {
            val dialog = availability.getErrorDialog(this, resultCode, 0)
            if (showErrorDialog) dialog.show()
            return false
        }
        return true
    }

    override fun onClick(view: View) {
        val id = view.id
        Log.d(TAG, "view: $id")
        if (id == R.id.close_button) {
            onBackPressed()
        } else if (id == R.id.flash_button) {
            flashButton?.let {
                if (it.isSelected) {
                    it.isSelected = false
                    camera?.cameraControl?.enableTorch(false)
                } else {
                    it.isSelected = true
                    camera?.cameraControl?.enableTorch(true)
                }
            }
        }
    }

    private fun isAClick(
        startX: Float,
        endX: Float,
        startY: Float,
        endY: Float
    ): Boolean {
        val differenceX = abs(startX - endX)
        val differenceY = abs(startY - endY)
        return !(differenceX > clickThreshold || differenceY > clickThreshold)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val minDistance = 600
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                y = event.y
                x = event.x
            }
            MotionEvent.ACTION_UP -> {
                if (isAClick(x, event.x, y, event.y)) {
                    val factory: MeteringPointFactory = SurfaceOrientedMeteringPointFactory(
                        binding.viewFinder.width.toFloat(), binding.viewFinder.height.toFloat()
                    )
                    val autoFocusPoint = factory.createPoint(event.x, event.y)
                    try {
                        camera!!.cameraControl.startFocusAndMetering(
                            FocusMeteringAction.Builder(
                                autoFocusPoint,
                                FocusMeteringAction.FLAG_AF
                            ).apply {
                                //focus only when the user tap the preview
                                disableAutoCancel()
                            }.build()
                        )
                    } catch (e: CameraInfoUnavailableException) {
                        Log.d("ERROR", "cannot access camera", e)
                    }
                } else {
                    val deltaY = event.y - y
                    if (deltaY < -minDistance) {
                        binding.debugLayout.visibility = VISIBLE
                    } else if (deltaY > minDistance) {
                        binding.debugLayout.visibility = INVISIBLE
                    }
                }
            }
        }
        return super.onTouchEvent(event)
    }
}