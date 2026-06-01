package com.example.blinkmonitor

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import java.util.concurrent.Executors
import kotlin.math.pow
import kotlin.math.sqrt
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.framework.image.BitmapImageBuilder

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: View
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var faceLandmarker: FaceLandmarker? = null

    private var overlayBitmap: Bitmap? = null
    private var overlayCanvas: Canvas? = null
    private val pointPaint = Paint().apply {
        color = Color.GREEN
        strokeWidth = 6f
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    private val halfClosedPaint = Paint().apply {
        color = Color.YELLOW
        strokeWidth = 6f
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    private val closedPaint = Paint().apply {
        color = Color.RED
        strokeWidth = 6f
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    private var inputBitmap: Bitmap? = null
    private var lastRotation: Int = 0

    // Детектор с новыми порогами
    private val blinkDetector = BlinkDetector(
        earBufferSize = 5,
        blinkHistorySize = 15,
        earOpenThreshold = 0.20f,
        earHalfClosedThreshold = 0.14f,
        earClosedThreshold = 0.11f,
        drowsyDurationMs = 2000,
        asleepDurationMs = 4000
    )
    
    // База данных
    private lateinit var drowsinessDatabase: DrowsinessDatabase
    private var currentPersonName = "Человек"
    private val availablePersons = mutableListOf("Человек", "Человечек", "Человечище")
    
    // Для регистрации событий
    private var lastEventTime = 0L
    private var isEventRegistered = false
    private var lastEventType: DrowsinessDatabase.EventType? = null
    
    private var faceLostCounter = 0
    private val FACE_LOST_THRESHOLD = 15

    private var fpsCounter = 0
    private var lastFpsLog = 0L

    // UI элементы
    private lateinit var earTextView: TextView
    private lateinit var personNameTextView: TextView
    private lateinit var eyeStateTextView: TextView
    private lateinit var eyeStateDetailTextView: TextView
    private lateinit var blinkMetricsTextView: TextView
    private lateinit var humanStateTextView: TextView
    private lateinit var drowsyTimerTextView: TextView
    private lateinit var frequencyGraphView: FrequencyGraphView
    private lateinit var sleepOverlay: View
    private lateinit var btnShowStats: Button
    private lateinit var btnSelectPerson: Button

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) initMediaPipe() else finish()
    }

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        overlayView = findViewById(R.id.overlayView)

        earTextView = findViewById(R.id.earTextView)
        personNameTextView = findViewById(R.id.personNameTextView)
        eyeStateTextView = findViewById(R.id.eyeStateTextView)
        eyeStateDetailTextView = findViewById(R.id.eyeStateDetailTextView)
        blinkMetricsTextView = findViewById(R.id.blinkMetricsTextView)
        humanStateTextView = findViewById(R.id.humanStateTextView)
        drowsyTimerTextView = findViewById(R.id.drowsyTimerTextView)
        frequencyGraphView = findViewById(R.id.frequencyGraphView)
        sleepOverlay = findViewById(R.id.sleepOverlay)
        btnShowStats = findViewById(R.id.btnShowStats)
        btnSelectPerson = findViewById(R.id.btnSelectPerson)
        
        sleepOverlay.visibility = View.GONE

        // Инициализация БД
        drowsinessDatabase = DrowsinessDatabase(this)
        
        // Настройка кнопок
        btnShowStats.setOnClickListener { showStatsDialog() }
        btnSelectPerson.setOnClickListener { showPersonSelector() }
        
        // Обновляем отображаемое имя
        personNameTextView.text = "Человек: $currentPersonName"

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            initMediaPipe()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun initMediaPipe() {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("face_landmarker.task")
                .build()

            val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setNumFaces(1)
                .build()

            faceLandmarker = FaceLandmarker.createFromOptions(this, options)
            Log.d("MediaPipe", "Модель инициализирована")
            startCamera()
        } catch (e: Exception) {
            Log.e("MediaPipe", "Инициализация модели провалилась", e)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(previewView.surfaceProvider)

            val resolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(320, 240),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .build()

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setResolutionSelector(resolutionSelector)
                .build()

            imageAnalyzer.setAnalyzer(cameraExecutor, ::processFrame)

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, imageAnalyzer
                )
            } catch (e: Exception) {
                Log.e("Camera", "Не удалось привязать камеру", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processFrame(imageProxy: ImageProxy) {
        val rotation = imageProxy.imageInfo.rotationDegrees
        val buffer = imageProxy.planes[0].buffer

        if (inputBitmap == null || inputBitmap!!.width != imageProxy.width || inputBitmap!!.height != imageProxy.height) {
            inputBitmap = createBitmap(imageProxy.width, imageProxy.height)
        }
        val bitmap = inputBitmap!!
        buffer.rewind()
        bitmap.copyPixelsFromBuffer(buffer)

        try {
            val imageProcessingOptions = ImageProcessingOptions.builder()
                .setRotationDegrees(rotation)
                .build()
            val mpImage = BitmapImageBuilder(bitmap).build()
            val results = faceLandmarker?.detect(mpImage, imageProcessingOptions)
            if (results != null && results.faceLandmarks().isNotEmpty()) {
                faceLostCounter = 0
                val landmarks = results.faceLandmarks()[0]
                if (landmarks.size >= 468) {
                    lastRotation = rotation
                    val ear = calculateEAR(landmarks, rotation)
                    val detectorResult = blinkDetector.processEAR(ear)
                    runOnUiThread {
                        updateUi(detectorResult)
                        drawLandmarks(landmarks, detectorResult.eyeState)
                    }
                    
                    // Регистрация событий
                    registerDrowsinessEvents(detectorResult)
                    
                    fpsCounter++
                    val now = System.currentTimeMillis()
                    if (now - lastFpsLog >= 1000) {
                        val fps = fpsCounter * 1000f / (now - lastFpsLog)
                        Log.d("PERF", "📊 FPS: %.1f | EAR: %.3f | State: %s".format(
                            fps,
                            detectorResult.smoothedEar,
                            detectorResult.eyeState.name
                        ))
                        fpsCounter = 0
                        lastFpsLog = now
                    }
                }
            } else {
                faceLostCounter++
                if (faceLostCounter >= FACE_LOST_THRESHOLD) {
                    blinkDetector.reset()
                    resetEventRegistration()
                    runOnUiThread {
                        resetUI()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MP", "Ошибка детекции", e)
        } finally {
            imageProxy.close()
        }
    }
    
    // Регистрация событий засыпания
    private fun registerDrowsinessEvents(result: BlinkDetector.DetectionResult) {
        val currentTime = System.currentTimeMillis()
        val isDrowsyOrAsleep = result.humanState != BlinkDetector.HumanState.AWAKE
        
        if (isDrowsyOrAsleep && !isEventRegistered) {
            lastEventTime = currentTime
            isEventRegistered = true
            lastEventType = when (result.eyeState) {
                BlinkDetector.EyeState.CLOSED -> DrowsinessDatabase.EventType.ASLEEP
                else -> DrowsinessDatabase.EventType.DROWSY
            }
            Log.d("DROWSY_DB", "Начало события: ${lastEventType}")
            
        } else if (!isDrowsyOrAsleep && isEventRegistered) {
            val duration = currentTime - lastEventTime
            
            if (duration >= 500) {
                val event = DrowsinessDatabase.DrowsinessEvent(
                    timestamp = lastEventTime,
                    durationMs = duration,
                    eventType = lastEventType ?: DrowsinessDatabase.EventType.DROWSY,
                    maxEyeBlink = 1f - result.smoothedEar / 0.3f,
                    wasAlertTriggered = duration >= 2000
                )
                
                drowsinessDatabase.registerEvent(currentPersonName, event)
                Log.d("DROWSY_DB", "Событие зарегистрировано: ${event.eventType}, длительность: ${duration}ms")
                
                runOnUiThread {
                    val message = when (event.eventType) {
                        DrowsinessDatabase.EventType.DROWSY -> "⚠️ Эпизод сонливости: ${duration / 1000} сек"
                        DrowsinessDatabase.EventType.ASLEEP -> "💤 Эпизод засыпания: ${duration / 1000} сек"
                        else -> ""
                    }
                    if (message.isNotEmpty()) {
                        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            
            resetEventRegistration()
        }
    }
    
    private fun resetEventRegistration() {
        isEventRegistered = false
        lastEventTime = 0L
        lastEventType = null
    }
    
    private fun showStatsDialog() {
        val stats = drowsinessDatabase.getPersonStats(currentPersonName)
        val todayStats = drowsinessDatabase.getTodayStats(currentPersonName)
        
        val message = buildString {
            appendLine("📊 Статистика для: $currentPersonName")
            appendLine("━━━━━━━━━━━━━━━━━━━━━")
            appendLine("📅 За сегодня:")
            appendLine("  • Сонливость: ${todayStats.drowsyCount} раз")
            appendLine("  • Засыпание: ${todayStats.asleepCount} раз")
            appendLine("  • Время в сонливости: ${todayStats.totalDrowsyDurationMs / 1000} сек")
            appendLine("  • Время в сне: ${todayStats.totalAsleepDurationMs / 1000} сек")
            appendLine("━━━━━━━━━━━━━━━━━━━━━")
            appendLine("📈 Всего:")
            appendLine("  • Всего сессий: ${stats?.totalSessions ?: 0}")
            appendLine("  • Эпизодов сонливости: ${stats?.totalDrowsyEvents ?: 0}")
            appendLine("  • Эпизодов засыпания: ${stats?.totalAsleepEvents ?: 0}")
            appendLine("  • Средний EAR: ${String.format("%.3f", stats?.averageEar ?: 0f)}")
            if (stats?.lastSeen != null && stats.lastSeen != 0L) {
                val lastSeen = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(stats.lastSeen))
                appendLine("  • Последний раз: $lastSeen")
            }
        }
        
        AlertDialog.Builder(this)
            .setTitle("Статистика бдительности")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .setNeutralButton("Экспорт") { _, _ ->
                exportStats()
            }
            .show()
    }
    
    private fun showPersonSelector() {
        val items = availablePersons.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Выберите профиль")
            .setItems(items) { _, which ->
                currentPersonName = availablePersons[which]
                personNameTextView.text = "Человек: $currentPersonName"
                Toast.makeText(this, "Выбран: $currentPersonName", Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton("Добавить") { _, _ ->
                showAddPersonDialog()
            }
            .show()
    }
    
    private fun showAddPersonDialog() {
        val input = android.widget.EditText(this)
        input.hint = "Введите имя"
        AlertDialog.Builder(this)
            .setTitle("Добавить новый профиль")
            .setView(input)
            .setPositiveButton("Добавить") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty() && !availablePersons.contains(newName)) {
                    availablePersons.add(newName)
                    drowsinessDatabase.addPerson(newName)
                    Toast.makeText(this, "Добавлен: $newName", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }
    
    private fun exportStats() {
        val json = drowsinessDatabase.exportToJson()
        AlertDialog.Builder(this)
            .setTitle("Экспорт данных")
            .setMessage("Скопируйте JSON для бэкапа:")
            .setPositiveButton("Копировать") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("drowsiness_stats", json)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Скопировано в буфер", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun calculateEAR(landmarks: List<NormalizedLandmark>, rotation: Int): Double {
        if (landmarks.size < 468) return 0.0

        val leftIndices = listOf(33, 160, 158, 133, 153, 144)
        val rightIndices = listOf(362, 385, 387, 263, 373, 380)

        fun dist(p1: NormalizedLandmark, p2: NormalizedLandmark) =
            sqrt((p1.x() - p2.x()).pow(2) + (p1.y() - p2.y()).pow(2))

        fun getEAR(indices: List<Int>): Double {
            val v1 = dist(landmarks[indices[1]], landmarks[indices[5]])
            val v2 = dist(landmarks[indices[2]], landmarks[indices[4]])
            val h = dist(landmarks[indices[0]], landmarks[indices[3]])
            return if (h > 0.001) (v1 + v2) / (2.0 * h) else 0.0
        }

        val leftEAR = getEAR(leftIndices)
        val rightEAR = getEAR(rightIndices)
        return (leftEAR + rightEAR) / 2.0
    }

    private fun drawLandmarks(landmarks: List<NormalizedLandmark>, eyeState: BlinkDetector.EyeState) {
        if (overlayView.width <= 0 || overlayView.height <= 0) return

        if (overlayBitmap == null || overlayBitmap!!.width != overlayView.width || overlayBitmap!!.height != overlayView.height) {
            overlayBitmap = createBitmap(overlayView.width, overlayView.height)
            overlayCanvas = Canvas(overlayBitmap!!)
        }

        val canvas = overlayCanvas!!
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        val baseModelWidth = 320f
        val baseModelHeight = 240f

        val viewWidth = overlayView.width.toFloat()
        val viewHeight = overlayView.height.toFloat()

        val isModelRotated = (lastRotation == 90 || lastRotation == 270)
        val (modelWidth, modelHeight) = if (isModelRotated) {
            Pair(baseModelHeight, baseModelWidth)
        } else {
            Pair(baseModelWidth, baseModelHeight)
        }

        val modelRatio = modelWidth / modelHeight
        val viewRatio = viewWidth / viewHeight

        val (scale, offsetX, offsetY) = if (viewRatio > modelRatio) {
            val s = viewHeight / modelHeight
            Triple(s, (viewWidth - modelWidth * s) / 2f, 0f)
        } else {
            val s = viewWidth / modelWidth
            Triple(s, 0f, (viewHeight - modelHeight * s) / 2f)
        }

        fun transformCords(pt: NormalizedLandmark): Pair<Float, Float> {
            var x = pt.x()
            val y = pt.y()
            x = 1f - x
            return when (lastRotation) {
                0 -> Pair(x, y)
                90 -> Pair(y, x)
                180 -> Pair(1f - x, y)
                270 -> Pair(1f - y, x)
                else -> Pair(x, y)
            }
        }

        val leftEyeIndices = listOf(33, 160, 158, 133, 153, 144)
        val rightEyeIndices = listOf(362, 385, 387, 263, 373, 380)
        
        val currentPaint = when (eyeState) {
            BlinkDetector.EyeState.OPEN -> pointPaint
            BlinkDetector.EyeState.HALF_CLOSED -> halfClosedPaint
            BlinkDetector.EyeState.CLOSED -> closedPaint
        }
        
        (leftEyeIndices + rightEyeIndices).forEach { idx ->
            val pt = landmarks[idx]
            val (normalizedX, normalizedY) = transformCords(pt)
            val x = normalizedX * modelWidth * scale + offsetX
            val y = normalizedY * modelHeight * scale + offsetY
            canvas.drawCircle(x, y, 6f, currentPaint)
        }

        overlayView.background = overlayBitmap!!.toDrawable(resources)
    }

    private fun clearLandmarks() {
        if (overlayBitmap != null && overlayView.width > 0 && overlayView.height > 0) {
            val canvas = Canvas(overlayBitmap!!)
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            overlayView.background = overlayBitmap!!.toDrawable(resources)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateUi(result: BlinkDetector.DetectionResult) {
        earTextView.text = "EAR: %.3f".format(result.smoothedEar)

        eyeStateTextView.text = when (result.eyeState) {
            BlinkDetector.EyeState.OPEN -> "👁️ Открыты"
            BlinkDetector.EyeState.HALF_CLOSED -> "😑 Полузакрыты"
            BlinkDetector.EyeState.CLOSED -> "😴 Закрыты"
        }
        
        val timeInState = result.timeInCurrentStateMs / 1000f
        eyeStateDetailTextView.text = when (result.eyeState) {
            BlinkDetector.EyeState.OPEN -> "👁️ Глаза открыты"
            BlinkDetector.EyeState.HALF_CLOSED -> "😑 Полузакрыты (${"%.1f".format(timeInState)} сек)"
            BlinkDetector.EyeState.CLOSED -> "😴 Закрыты (${"%.1f".format(timeInState)} сек)"
        }
        
        val avgDuration = result.avgBlinkDurationSec?.let { "%.2f с".format(it) } ?: "—"
        val frequency = result.blinkFrequencyPerMin?.let { "%.1f/мин".format(it) } ?: "—"
        blinkMetricsTextView.text = "Средняя длительность: $avgDuration | Частота: $frequency"
        
        when {
            result.eyeState == BlinkDetector.EyeState.HALF_CLOSED && !result.isAsleep -> {
                val remaining = (2000 - result.timeInCurrentStateMs).coerceAtLeast(0) / 1000f
                drowsyTimerTextView.text = "⏰ До сна: ${"%.1f".format(remaining)} сек"
                drowsyTimerTextView.visibility = View.VISIBLE
            }
            result.eyeState == BlinkDetector.EyeState.CLOSED && !result.isAsleep -> {
                val remaining = (4000 - result.timeInCurrentStateMs).coerceAtLeast(0) / 1000f
                drowsyTimerTextView.text = "💤 Засыпание через: ${"%.1f".format(remaining)} сек"
                drowsyTimerTextView.visibility = View.VISIBLE
            }
            else -> {
                drowsyTimerTextView.visibility = View.GONE
            }
        }

        humanStateTextView.text = when (result.humanState) {
            BlinkDetector.HumanState.AWAKE -> "✅ Бодрствует"
            BlinkDetector.HumanState.DROWSY -> "⚠️ СОНЛИВОСТЬ"
            BlinkDetector.HumanState.ASLEEP -> "😴 СОН"
        }

        humanStateTextView.setTextColor(
            when (result.humanState) {
                BlinkDetector.HumanState.AWAKE -> Color.GREEN
                BlinkDetector.HumanState.DROWSY -> Color.YELLOW
                BlinkDetector.HumanState.ASLEEP -> Color.RED
            }
        )

        frequencyGraphView.updateData(blinkDetector.getEarBuffer())
        sleepOverlay.visibility = if (result.isAsleep) View.VISIBLE else View.GONE
    }

    private fun resetUI() {
        earTextView.text = "EAR: —"
        eyeStateTextView.text = "👤 Лицо не обнаружено"
        eyeStateDetailTextView.text = "👤 Лицо не обнаружено"
        blinkMetricsTextView.text = "Средняя длительность: — | Частота: —"
        humanStateTextView.text = ""
        drowsyTimerTextView.visibility = View.GONE
        frequencyGraphView.reset()
        clearLandmarks()
    }

    override fun onDestroy() {
        super.onDestroy()
        faceLandmarker?.close()
        cameraExecutor.shutdown()
    }
}