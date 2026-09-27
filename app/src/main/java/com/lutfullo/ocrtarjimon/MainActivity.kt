package com.lutfullo.ocrtarjimon

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * OCR Tarjimon — hammasi shu bitta faylda.
 * Tarjima uchun MyMemory (bepul, kalitsiz) onlayn xizmatidan foydalaniladi,
 * chunki ML Kit'ning o'z tarjima kutubxonasi o'zbek tilini qo'llab-quvvatlamaydi.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var infoText: TextView
    private lateinit var translatedText: TextView
    private lateinit var permissionText: TextView
    private lateinit var langButton: Button

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val languageIdentifier = LanguageIdentification.getClient()
    private var isProcessing = false

    private val targetLangs = listOf("uz" to "UZ", "en" to "EN", "ru" to "RU")
    private var targetLangIndex = 0
    private val targetLangCode get() = targetLangs[targetLangIndex].first

    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results[Manifest.permission.CAMERA] == true) {
                startCamera()
            } else {
                permissionText.visibility = View.VISIBLE
            }
            if (results[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
                requestLocationAndWeather()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        updatePhoneInfo()

        val neededPermissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            neededPermissions += Manifest.permission.BLUETOOTH_CONNECT
        }
        permissionLauncher.launch(neededPermissions.toTypedArray())
    }

    private fun buildUi(): FrameLayout {
        val matchParent = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        previewView = PreviewView(this)
        root.addView(previewView, matchParent)

        infoText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            setBackgroundColor(0x88000000.toInt())
            setPadding(24, 16, 24, 16)
        }
        root.addView(
            infoText,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP
            )
        )

        val bottomPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xAA000000.toInt())
            setPadding(16, 16, 16, 24)
        }

        translatedText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(8, 8, 8, 16)
        }
        bottomPanel.addView(translatedText)

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        langButton = smallButton(targetLangs[targetLangIndex].second) {
            targetLangIndex = (targetLangIndex + 1) % targetLangs.size
            langButton.text = targetLangs[targetLangIndex].second
        }
        row1.addView(langButton)
        row1.addView(smallButton("WiFi") { openWifiPanel() })
        row1.addView(smallButton("Bluetooth") { toggleBluetooth() })
        row1.addView(smallButton("Hotspot") { openHotspotSettings() })

        row2.addView(smallButton("🔊+") { changeVolume(true) })
        row2.addView(smallButton("🔊-") { changeVolume(false) })
        row2.addView(smallButton("☀+") { changeBrightness(20) })
        row2.addView(smallButton("☀-") { changeBrightness(-20) })

        bottomPanel.addView(row1)
        bottomPanel.addView(row2)

        root.addView(
            bottomPanel,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
            )
        )

        permissionText = TextView(this).apply {
            text = "Kamera uchun ruxsat kerak"
            setTextColor(Color.WHITE)
            textSize = 16f
            visibility = View.GONE
        }
        root.addView(
            permissionText,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER
            )
        )

        return root
    }

    private fun smallButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 11f
            setOnClickListener { onClick() }
        }
    }

    private fun startCamera() {
        permissionText.visibility = View.GONE
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(ContextCompat.getMainExecutor(this)) { imageProxy ->
                if (isProcessing) {
                    imageProxy.close()
                } else {
                    isProcessing = true
                    val mediaImage = imageProxy.image
                    if (mediaImage != null) {
                        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                        recognizer.process(image)
                            .addOnSuccessListener { visionText ->
                                val text = visionText.text
                                imageProxy.close()
                                if (text.isNotBlank()) translateDetectedText(text) else isProcessing = false
                            }
                            .addOnFailureListener {
                                imageProxy.close()
                                isProcessing = false
                            }
                    } else {
                        imageProxy.close()
                        isProcessing = false
                    }
                }
            }

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
            )
        }, ContextCompat.getMainExecutor(this))
    }

    private fun translateDetectedText(text: String) {
        languageIdentifier.identifyLanguage(text)
            .addOnSuccessListener { langCode ->
                if (langCode == "und") {
                    isProcessing = false
                    return@addOnSuccessListener
                }
                if (langCode == targetLangCode) {
                    translatedText.text = text
                    isProcessing = false
                    return@addOnSuccessListener
                }
                fetchTranslation(text, langCode, targetLangCode)
            }
            .addOnFailureListener { isProcessing = false }
    }

    private fun fetchTranslation(text: String, source: String, target: String) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val encoded = URLEncoder.encode(text, "UTF-8")
                    val url = URL("https://api.mymemory.translated.net/get?q=$encoded&langpair=$source|$target")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000
                    val body = conn.inputStream.bufferedReader().readText()
                    conn.disconnect()
                    JSONObject(body).getJSONObject("responseData").getString("translatedText")
                } catch (e: Exception) {
                    null
                }
            }
            translatedText.text = result ?: text
            isProcessing = false
        }
    }

    private fun requestLocationAndWeather() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return

        fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
            if (loc != null) fetchWeather(loc.latitude, loc.longitude)
        }
    }

    private fun fetchWeather(lat: Double, lon: Double) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val url = URL(
                        "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current_weather=true"
                    )
                    val conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000
                    val body = conn.inputStream.bufferedReader().readText()
                    conn.disconnect()
                    val current = JSONObject(body).getJSONObject("current_weather")
                    "${current.getDouble("temperature")}°C"
                } catch (e: Exception) {
                    null
                }
            }
            if (result != null) updatePhoneInfo(weather = result)
        }
    }

    private fun openWifiPanel() {
        startActivity(Intent(Settings.Panel.ACTION_WIFI))
    }

    private fun toggleBluetooth() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) return
        if (!adapter.isEnabled) {
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } else {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
    }

    private fun openHotspotSettings() {
        try {
            startActivity(Intent("android.settings.TETHER_SETTINGS"))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
        }
    }

    private fun changeVolume(raise: Boolean) {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (raise) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI
        )
    }

    private fun changeBrightness(delta: Int) {
        if (!Settings.System.canWrite(this)) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))
            )
            return
        }
        val current = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        val next = (current + delta).coerceIn(0, 255)
        Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, next)
    }

    private fun updatePhoneInfo(weather: String? = null) {
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        val battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val phone = "${Build.MANUFACTURER} ${Build.MODEL} | Android ${Build.VERSION.RELEASE} | Batareya: $battery%"
        infoText.text = if (weather != null) "$phone | Ob-havo: $weather" else phone
    }

    override fun onDestroy() {
        super.onDestroy()
        recognizer.close()
        languageIdentifier.close()

