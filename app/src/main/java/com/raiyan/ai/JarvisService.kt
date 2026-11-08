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

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isAwaitingCommand = false
    private var isSpeaking = false

    override fun onCreate() {
        super.onCreate()
        startNotification()
        acquireWakeLock()
        tts = TextToSpeech(this, this)
        mainHandler.post { initSpeechRecognizer() }
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
            tts?.language = Locale("bn", "BD")
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { isSpeaking = true }
                override fun onDone(utteranceId: String?) {
                    isSpeaking = false
                    mainHandler.postDelayed({ startListening() }, 400)
                }
                override fun onError(utteranceId: String?) {
                    isSpeaking = false
                    mainHandler.postDelayed({ startListening() }, 400)
                }
            })
        }
    }

    private fun speak(text: String) {
        isSpeaking = true
        mainHandler.post { speechRecognizer?.cancel() }
        val params = Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "jarvis_speech")
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "jarvis_speech")
    }

    private fun initSpeechRecognizer() {
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onError(error: Int) {
                if (!isSpeaking) {
                    mainHandler.postDelayed({ startListening() }, 800)
                }
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val spokenText = matches?.firstOrNull()?.trim() ?: ""
                if (spokenText.isNotEmpty()) {
                    handleSpokenText(spokenText)
                } else {
                    startListening()
                }
            }
        })
        startListening()
    }

    private fun startListening() {
        if (isSpeaking) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-BD")
        }
        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            mainHandler.postDelayed({ startListening() }, 1000)
        }
    }

    private fun handleSpokenText(text: String) {
        val lower = text.lowercase()
        val hasWakeWord = lower.contains("জারভিস") || lower.contains("যারভিস") || lower.contains("jarvis")

        if (!isAwaitingCommand) {
            if (hasWakeWord) {
                val cleanedCommand = lower
                    .replace("জারভিস", "")
                    .replace("যারভিস", "")
                    .replace("jarvis", "")
                    .trim()

                if (cleanedCommand.length > 2) {
                    processCommandWithGemini(cleanedCommand)
                } else {
                    isAwaitingCommand = true
                    speak("জী বলুন কি সহায়তা করবো")
                }
            } else {
                startListening()
            }
        } else {
            isAwaitingCommand = false
            processCommandWithGemini(text)
        }
    }

    private fun processCommandWithGemini(command: String) {
        val apiKey = getSharedPreferences("RaiyanPrefs", Context.MODE_PRIVATE).getString("API_KEY", "") ?: ""
        if (apiKey.isEmpty()) {
            speak("দয়া করে রাইয়ান অ্যাপে আপনার এপিআই কি বসান")
            return
        }

        thread {
            try {
                val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true

                val systemPrompt = """
                    You are Jarvis, a Bangla voice assistant inside the Android app Raiyan.
                    Respond ONLY in raw JSON format without markdown blocks:
                    {"action": "FLASH_ON|FLASH_OFF|VOLUME_UP|VOLUME_DOWN|WIFI_SETTINGS|CALL|OPEN_APP|NONE", "target": "phone number or app name in English if needed", "reply": "Short natural response in Bangla"}
                    User command: $command
                """.trimIndent()

                val body = JSONObject().apply {
                    put("contents", JSONArray().put(JSONObject().apply {
                        put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
                    }))
                }

                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                val responseText = conn.inputStream.bufferedReader().readText()
                val rawReply = JSONObject(responseText)
                    .getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts")
                    .getJSONObject(0).getString("text")
                    .replace("```json", "").replace("```", "").trim()

                val resultJson = JSONObject(rawReply)
                val action = resultJson.optString("action", "NONE")
                val target = resultJson.optString("target", "")
                val reply = resultJson.optString("reply", "ঠিক আছে")

                mainHandler.post {
                    executePhoneAction(action, target)
                    speak(reply)
                }
            } catch (e: Exception) {
                mainHandler.post { speak("দুঃখিত, ইন্টারনেট বা এপিআই সংযোগে সমস্যা হচ্ছে") }
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
        wakeLock?.release()
        speechRecognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
