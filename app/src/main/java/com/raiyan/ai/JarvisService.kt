package com.raiyan.ai

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

class JarvisService : Service(), TextToSpeech.OnInitListener {

    companion object {
        var logCallback: ((String) -> Unit)? = null
        var triggerDirectCommand: (() -> Unit)? = null

        fun updateUI(msg: String) {
            Handler(Looper.getMainLooper()).post {
                logCallback?.invoke(msg)
            }
        }
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isAwaitingCommand = false
    private var isSpeaking = false
    private var lastActivityTime = System.currentTimeMillis()

    // Watchdog: মাইক্রোফোন ১০ সেকেন্ডের বেশি আটকে থাকলে অটোমেটিক রিস্টার্ট করবে
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (!isSpeaking && System.currentTimeMillis() - lastActivityTime > 10000) {
                startListening()
            }
            mainHandler.postDelayed(this, 5000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        startNotification()
        acquireWakeLock()
        tts = TextToSpeech(this, this)

        triggerDirectCommand = {
            mainHandler.post {
                isAwaitingCommand = true
                speak("জী বলুন কি সহায়তা করবো")
            }
        }
        mainHandler.postDelayed(watchdogRunnable, 5000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun startNotification() {
        val channelId = "raiyan_channel"
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(channelId, "Raiyan Assistant", NotificationManager.IMPORTANCE_LOW)
        manager.createNotificationChannel(channel)

        val notification = Notification.Builder(this, channelId)
            .setContentTitle("Raiyan (জারভিস) সচল আছে")
            .setContentText("'জারভিস' বলে ডাকলে সাড়া দেবে...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()

        startForeground(1, notification)
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Raiyan::JarvisWakeLock")
        wakeLock?.acquire()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.setLanguage(Locale("bn", "BD"))
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    isSpeaking = true
                    lastActivityTime = System.currentTimeMillis()
                }
                override fun onDone(utteranceId: String?) {
                    isSpeaking = false
                    lastActivityTime = System.currentTimeMillis()
                    // স্যামসাং ফোনে অডিও রিলিজ হওয়ার জন্য ১২০০ মিলিসেকেন্ড বিরতি
                    mainHandler.postDelayed({ startListening() }, 1200)
                }
                override fun onError(utteranceId: String?) {
                    isSpeaking = false
                    mainHandler.postDelayed({ startListening() }, 1200)
                }
            })
            speak("জারভিস চালু হয়েছে")
        }
    }

    private fun speak(text: String) {
        isSpeaking = true
        lastActivityTime = System.currentTimeMillis()
        updateUI("জারভিস বলছে: $text")
        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
                speechRecognizer?.destroy()
                speechRecognizer = null
            } catch (_: Exception) {}

            val params = Bundle()
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "jarvis_speech")
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "jarvis_speech")
        }
    }

    private fun startListening() {
        if (isSpeaking) return
        lastActivityTime = System.currentTimeMillis()

        mainHandler.post {
            try {
                speechRecognizer?.destroy()
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this@JarvisService)
                speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        lastActivityTime = System.currentTimeMillis()
                        val mode = if (isAwaitingCommand) "এখন আপনার কমান্ড বলুন..." else "'জারভিস' বলে ডাকুন..."
                        updateUI("মাইক্রোফোন শুনছে ($mode)")
                    }
                    override fun onBeginningOfSpeech() {
                        lastActivityTime = System.currentTimeMillis()
                    }
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}

                    override fun onPartialResults(partialResults: Bundle?) {
                        lastActivityTime = System.currentTimeMillis()
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull()?.trim() ?: ""
                        if (text.isNotEmpty()) {
                            updateUI("শুনতে পাচ্ছি: $text")
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}

                    override fun onError(error: Int) {
                        lastActivityTime = System.currentTimeMillis()
                        if (!isSpeaking) {
                            mainHandler.postDelayed({ startListening() }, 1000)
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        lastActivityTime = System.currentTimeMillis()
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val spokenText = matches?.firstOrNull()?.trim() ?: ""
                        if (spokenText.isNotEmpty()) {
                            updateUI("আপনি বলেছেন: $spokenText")
                            handleSpokenText(spokenText)
                        } else {
                            mainHandler.postDelayed({ startListening() }, 500)
                        }
                    }
                })

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-BD")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                }
                speechRecognizer?.startListening(intent)
            } catch (e: Exception) {
                updateUI("মাইক্রোফোন রিস্টার্ট হচ্ছে...")
                mainHandler.postDelayed({ startListening() }, 1500)
            }
        }
    }

    private fun handleSpokenText(text: String) {
        val lower = text.lowercase()
        val wakeWords = listOf("জার্ভিস", "জারভিস", "যারভিস", "জারবিস", "সার্ভিস", "ভিস", "jarvis", "service")
        val hasWakeWord = wakeWords.any { lower.contains(it) }

        if (!isAwaitingCommand) {
            if (hasWakeWord) {
                var cleanedCommand = lower
                wakeWords.forEach { cleanedCommand = cleanedCommand.replace(it, "") }
                cleanedCommand = cleanedCommand.replace("হ্যালো", "").replace("এই", "").trim()

                if (cleanedCommand.length > 6) {
                    processCommandWithGemini(cleanedCommand)
                } else {
                    isAwaitingCommand = true
                    speak("জী বলুন কি সহায়তা করবো")
                }
            } else {
                mainHandler.postDelayed({ startListening() }, 400)
            }
        } else {
            isAwaitingCommand = false
            processCommandWithGemini(text)
        }
    }

    private fun processCommandWithGemini(command: String) {
        val rawKey = getSharedPreferences("RaiyanPrefs", Context.MODE_PRIVATE).getString("API_KEY", "") ?: ""
        val apiKey = rawKey.replace("\\s".toRegex(), "")
        if (apiKey.isEmpty()) {
            speak("দয়া করে রাইয়ান অ্যাপে আপনার এপিআই কি বসান")
            return
        }

        isSpeaking = true
        updateUI("জারভিস চিন্তা করছে: '$command'...")

        thread {
            try {
                val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.doOutput = true

                val systemPrompt = """
                    You are Jarvis, a Bangla voice assistant inside the Android app Raiyan.
                    Return ONLY a valid JSON object with these keys:
                    {"action": "FLASH_ON or FLASH_OFF or VOLUME_UP or VOLUME_DOWN or WIFI_SETTINGS or CALL or OPEN_APP or NONE", "target": "phone number or English app name if needed, else empty", "reply": "Short helpful reply in Bangla"}
                    User command in Bangla: $command
                """.trimIndent()

                val body = JSONObject().apply {
                    put("contents", JSONArray().put(JSONObject().apply {
                        put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
                    }))
                    put("generationConfig", JSONObject().apply {
                        put("responseMimeType", "application/json")
                    })
                }

                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

                val responseCode = conn.responseCode
                if (responseCode != 200) {
                    val errorStream = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $responseCode"
                    mainHandler.post {
                        updateUI("API Error ($responseCode): $errorStream")
                        speak("আপনার এপিআই কি সঠিক নয় অথবা মেয়াদ শেষ হয়েছে")
                    }
                    return@thread
                }

                val responseText = conn.inputStream.bufferedReader().readText()
                val rawReply = JSONObject(responseText)
                    .getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts")
                    .getJSONObject(0).getString("text").trim()

                val jsonStart = rawReply.indexOf("{")
                val jsonEnd = rawReply.lastIndexOf("}")
                val cleanJson = if (jsonStart != -1 && jsonEnd != -1) rawReply.substring(jsonStart, jsonEnd + 1) else rawReply

                val resultJson = JSONObject(cleanJson)
                val action = resultJson.optString("action", "NONE")
                val target = resultJson.optString("target", "")
                val reply = resultJson.optString("reply", "ঠিক আছে")

                mainHandler.post {
                    executePhoneAction(action, target)
                    speak(reply)
                }
            } catch (e: Exception) {
                mainHandler.post {
                    updateUI("সমস্যার কারণ: ${e.message}")
                    speak("দুঃখিত, ইন্টারনেট বা এপিআই সংযোগে সমস্যা হচ্ছে")
                }
            }
        }
    }

    private fun executePhoneAction(action: String, target: String) {
        try {
            when (action) {
                "FLASH_ON" -> toggleFlash(true)
                "FLASH_OFF" -> toggleFlash(false)
                "VOLUME_UP" -> adjustVolume(AudioManager.ADJUST_RAISE)
                "VOLUME_DOWN" -> adjustVolume(AudioManager.ADJUST_LOWER)
                "WIFI_SETTINGS" -> {
                    val i = Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(i)
                }
                "CALL" -> {
                    if (target.isNotEmpty()) {
                        val i = Intent(Intent.ACTION_CALL, Uri.parse("tel:$target")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(i)
                    }
                }
                "OPEN_APP" -> openAppByName(target)
            }
        } catch (_: Exception) {}
    }

    private fun toggleFlash(state: Boolean) {
        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = cm.cameraIdList.firstOrNull() ?: return
        cm.setTorchMode(cameraId, state)
    }

    private fun adjustVolume(direction: Int) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
    }

    private fun openAppByName(appName: String) {
        val pm = packageManager
        val packages = pm.getInstalledApplications(0)
        val matched = packages.firstOrNull {
            pm.getApplicationLabel(it).toString().contains(appName, ignoreCase = true)
        }
        matched?.let {
            val launchIntent = pm.getLaunchIntentForPackage(it.packageName)
            launchIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(watchdogRunnable)
        wakeLock?.release()
        speechRecognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
