package com.example.tahakkum

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private var recognizer: SpeechRecognizer? = null
    private var gestureRecognizer: GestureRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var listening by mutableStateOf(false)
    private var cameraOn by mutableStateOf(false)
    private var status by mutableStateOf("جاهز. ابدأ بالصوت أو الكاميرا.")
    private var lastCommand by mutableStateOf("—")
    private var gestureStatus by mutableStateOf("التعرف على الإشارات متوقف")
    private var previewView: PreviewView? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val lastGestureAt = AtomicLong(0L)
    private var lastGesture = ""
    private val speechHandler = Handler(Looper.getMainLooper())
    private var continuousListening = false
    private val restartListening = Runnable { if (continuousListening && !isFinishing) startListening() }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == true) { continuousListening = true; startListening() }
        if (grants[Manifest.permission.CAMERA] == true) cameraOn = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("تَحكُّم", style = MaterialTheme.typography.headlineLarge)
                        Text("مساعد عربي بالصوت وإشارات اليد")
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("الحالة: $status")
                                Text("آخر أمر: $lastCommand")
                                Button(onClick = { continuousListening = true; requestMic() }, modifier = Modifier.fillMaxWidth()) {
                                    Text(if (listening) "الاستماع جارٍ…" else "🎙 ابدأ الأوامر الصوتية")
                                }
                                OutlinedButton(onClick = { continuousListening = false; stopListening(); status = "تم إيقاف الاستماع" }, modifier = Modifier.fillMaxWidth()) {
                                    Text("إيقاف الاستماع")
                                }
                            }
                        }
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("التحكم بحركة اليد", style = MaterialTheme.typography.titleMedium)
                                Text(gestureStatus)
                                Button(onClick = {
                                    if (cameraOn) {
                                        cameraOn = false
                                        stopCamera()
                                        gestureStatus = "الكاميرا متوقفة"
                                    } else requestCamera()
                                }, modifier = Modifier.fillMaxWidth()) {
                                    Text(if (cameraOn) "إيقاف الكاميرا" else "تشغيل الكاميرا والتعرف على اليد")
                                }
                                if (cameraOn) {
                                    AndroidView(
                                        factory = { context ->
                                            PreviewView(context).also {
                                                previewView = it
                                                startCamera(it)
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth().height(260.dp)
                                    )
                                }
                            }
                        }
                        Text("الأوامر الصوتية", style = MaterialTheme.typography.titleMedium)
                        Text("افتح واتساب أو يوتيوب أو أي تطبيق مثبت • ارفع/اخفض الصوت • افتح الإعدادات • شغّل/أوقف الفيديو • التالي/السابق • اتصل")
                        Text("إشارات اليد الافتراضية: الكف المفتوح = إيقاف مؤقت، السبابة للأعلى = تشغيل، علامة النصر = التالي، الإبهام للأسفل = السابق. تحتاج إلى اختبار على الجهاز.")
                        Text("تنبيه: الاتصال يفتح لوحة الاتصال للاختيار والتأكيد؛ لا يبدأ مكالمة دون موافقتك.")
                    }
                }
            }
        }
    }

    private fun requestMic() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startListening()
        else permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
    }

    private fun requestCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) cameraOn = true
        else permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
    }

    private fun setupGestureRecognizer() {
        if (gestureRecognizer != null) return
        try {
            val options = GestureRecognizer.GestureRecognizerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("gesture_recognizer.task").build())
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumHands(1)
                .setResultListener { result: GestureRecognizerResult, _ ->
                    val category = result.gestures().firstOrNull()?.firstOrNull()
                    val name = category?.categoryName() ?: "None"
                    val score = category?.score() ?: 0f
                    runOnUiThread { gestureStatus = if (name == "None") "لم تُكتشف إشارة واضحة" else "الإشارة: $name (${(score * 100).toInt()}%)" }
                    if (score >= 0.72f && name != "None") handleGesture(name)
                }
                .setErrorListener { error -> runOnUiThread { gestureStatus = "خطأ في التعرف: ${error.message}" } }
                .build()
            gestureRecognizer = GestureRecognizer.createFromOptions(this, options)
        } catch (e: Exception) {
            status = "تعذر تحميل نموذج اليد. أعد بناء المشروع مع الإنترنت."
        }
    }

    private fun handleGesture(name: String) {
        val now = SystemClock.elapsedRealtime()
        if (name == lastGesture && now - lastGestureAt.get() < 1800) return
        if (now - lastGestureAt.get() < 900) return
        lastGesture = name
        lastGestureAt.set(now)
        when (name) {
            "Open_Palm" -> { media(android.view.KeyEvent.KEYCODE_MEDIA_PAUSE); say("إيقاف مؤقت") }
            "Pointing_Up" -> { media(android.view.KeyEvent.KEYCODE_MEDIA_PLAY); say("تشغيل") }
            "Victory" -> { media(android.view.KeyEvent.KEYCODE_MEDIA_NEXT); say("الفيديو التالي") }
            "Thumb_Down" -> { media(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS); say("الفيديو السابق") }
            "Closed_Fist" -> { stopListening(); say("تم إيقاف الاستماع") }
        }
    }

    private fun startCamera(view: PreviewView) {
        setupGestureRecognizer()
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(cameraExecutor) { proxy ->
                    try {
                        val bitmap = imageProxyToBitmap(proxy)
                        val rotated = rotate(bitmap, proxy.imageInfo.rotationDegrees)
                        val mpImage = BitmapImageBuilder(rotated).build()
                        gestureRecognizer?.recognizeAsync(mpImage, SystemClock.uptimeMillis())
                        if (rotated !== bitmap) bitmap.recycle()
                        rotated.recycle()
                    } catch (_: Exception) {
                    } finally { proxy.close() }
                }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
                runOnUiThread { gestureStatus = "الكاميرا تعمل؛ ارفع يدك أمامها." }
            } catch (e: Exception) {
                runOnUiThread { status = "تعذر تشغيل الكاميرا: ${e.localizedMessage}" }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        try { ProcessCameraProvider.getInstance(this).get().unbindAll() } catch (_: Exception) {}
        try { gestureRecognizer?.close() } catch (_: Exception) {}
        gestureRecognizer = null
        previewView = null
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val y = image.planes[0].buffer
        val u = image.planes[1].buffer
        val v = image.planes[2].buffer
        val ySize = y.remaining()
        val uSize = u.remaining()
        val vSize = v.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)
        y.get(nv21, 0, ySize)
        val chromaWidth = image.width / 2
        val chromaHeight = image.height / 2
        var offset = ySize
        val uBytes = ByteArray(u.remaining()); val vBytes = ByteArray(v.remaining())
        // Planes' buffers were consumed by y.get above only for Y; read chroma from duplicates safely.
        val ub = image.planes[1].buffer.duplicate()
        val vb = image.planes[2].buffer.duplicate()
        ub.get(uBytes); vb.get(vBytes)
        val up = image.planes[1].pixelStride
        val vp = image.planes[2].pixelStride
        val ur = image.planes[1].rowStride
        val vr = image.planes[2].rowStride
        for (row in 0 until chromaHeight) for (col in 0 until chromaWidth) {
            val ui = row * ur + col * up
            val vi = row * vr + col * vp
            if (vi < vBytes.size && ui < uBytes.size && offset + 1 < nv21.size) {
                nv21[offset++] = vBytes[vi]
                nv21[offset++] = uBytes[ui]
            }
        }
        val yuv = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, image.width, image.height), 80, out)
        return BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())
            ?: throw IllegalStateException("Could not decode camera frame")
    }

    private fun rotate(source: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return source
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    override fun onInit(result: Int) {
        ttsReady = result == TextToSpeech.SUCCESS
        if (ttsReady) tts?.language = Locale("ar")
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status = "خدمة التعرف على الكلام غير متاحة"; say(status); return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { listening = true; status = "أستمع الآن…" }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { listening = false }
            override fun onError(error: Int) { listening = false; status = "أعيد تشغيل الاستماع…"; if (continuousListening) speechHandler.postDelayed(restartListening, 700) }
            override fun onResults(results: Bundle?) {
                listening = false
                val command = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                lastCommand = command
                executeCommand(command)
                if (continuousListening && !isFinishing) speechHandler.postDelayed(restartListening, 900)
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-EG")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "قل الأمر بالعربية")
        }
        recognizer?.startListening(intent)
    }

    private fun executeCommand(raw: String) {
        val c = raw.lowercase(Locale("ar")).replace('أ','ا').replace('إ','ا').replace('آ','ا').trim()
        when {
            c.contains("ارفع الصوت") || c.contains("علي الصوت") -> { audio(AudioManager.ADJUST_RAISE); say("تم رفع الصوت") }
            c.contains("اخفض الصوت") || c.contains("وطي الصوت") || c.contains("خفض الصوت") -> { audio(AudioManager.ADJUST_LOWER); say("تم خفض الصوت") }
            c.contains("افتح الاعدادات") -> { say("سأفتح الإعدادات"); startActivity(Intent(Settings.ACTION_SETTINGS)) }
            c.contains("شغل الفيديو") || c == "شغل" -> { media(android.view.KeyEvent.KEYCODE_MEDIA_PLAY); say("أرسلت أمر التشغيل") }
            c.contains("اوقف الفيديو") || c.contains("وقف الفيديو") || c == "وقف" -> { media(android.view.KeyEvent.KEYCODE_MEDIA_PAUSE); say("أرسلت أمر الإيقاف المؤقت") }
            c.contains("التالي") || c.contains("غير الفيديو") -> { media(android.view.KeyEvent.KEYCODE_MEDIA_NEXT); say("أرسلت أمر التالي") }
            c.contains("السابق") -> { media(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS); say("أرسلت أمر السابق") }
            c.contains("اتصل") -> { say("اختر جهة الاتصال من شاشة الاتصال"); startActivity(Intent(Intent.ACTION_DIAL)) }
            c == "قف" || c.contains("توقف") -> { continuousListening = false; stopListening(); say("تم إيقاف الاستماع") }
            c.startsWith("افتح ") || c.startsWith("افتح تطبيق ") || c.startsWith("شغل تطبيق ") || c.startsWith("شغل ") -> {
                if (!launchInstalledApp(c)) say("لم أجد التطبيق. جرّب قول افتح ثم اسم التطبيق")
            }
            else -> say("لم أفهم الأمر. قل افتح ثم اسم التطبيق أو اختر أمرًا من القائمة")
        }
    }

    private fun normalizeAppName(value: String): String = value.lowercase(Locale("ar"))
        .replace('أ','ا').replace('إ','ا').replace('آ','ا').replace('ى','ي').trim()

    private fun launchInstalledApp(command: String): Boolean {
        var name = normalizeAppName(command)
        listOf("افتح تطبيق ", "افتح ", "شغل تطبيق ", "شغل ").forEach { prefix ->
            if (name.startsWith(prefix)) { name = name.removePrefix(prefix).trim(); return@forEach }
        }
        if (name.isBlank()) return false
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = packageManager.queryIntentActivities(launcher, 0)
        val match = activities.firstOrNull { normalizeAppName(it.loadLabel(packageManager).toString()) == name }
            ?: activities.firstOrNull { val label = normalizeAppName(it.loadLabel(packageManager).toString()); label.length > 2 && (label.contains(name) || name.contains(label)) }
            ?: return false
        val launchIntent = packageManager.getLaunchIntentForPackage(match.activityInfo.packageName) ?: return false
        return try { startActivity(launchIntent); true } catch (_: Exception) { false }
    }

    private fun audio(direction: Int) {
        (getSystemService(AUDIO_SERVICE) as AudioManager).adjustStreamVolume(
            AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI
        )
    }

    private fun media(code: Int) {
        val a = getSystemService(AUDIO_SERVICE) as AudioManager
        a.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
        a.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
    }

    private fun say(message: String) {
        status = message
        if (ttsReady) tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "tahakkum")
    }

    private fun stopListening() { listening = false; speechHandler.removeCallbacks(restartListening); recognizer?.cancel() }

    override fun onDestroy() {
        continuousListening = false
        speechHandler.removeCallbacksAndMessages(null)
        stopCamera()
        recognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}
