package com.raiyan.ai

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.sqrt

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

    private var tts: TextToSpeech? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var isServiceRunning = false
    @Volatile private var isSpeaking = false
    @Volatile private var isProcessing = false
    @Volatile private var isAwaitingCommand = false

    override fun onCreate() {
        super.onCreate()
        startNotification()
        acquireWakeLock()
        isServiceRunning = true
        tts = TextToSpeech(this, this, "com.google.android.tts")

        triggerDirectCommand = {
            isAwaitingCommand = true
            speak("জী বলুন কি সহায়তা করবো")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isServiceRunning) {
            isServiceRunning = true
            startRawAudioLoop()
        }
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
            speak("জারভিস চালু হয়েছে")
            startRawAudioLoop()
        } else {
            updateUI("TTS ত্রুটি: সেটিংস চেক করুন")
            startRawAudioLoop()
        }
    }

    private fun speak(text: String) {
        isSpeaking = true
        updateUI("জারভিস বলছে: $text")
        mainHandler.post {
            val params = Bundle()
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "jarvis_speech")
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "jarvis_speech")
            waitForSpeechToFinish()
        }
    }

    private fun waitForSpeechToFinish() {
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                if (tts?.isSpeaking == true) {
                    mainHandler.postDelayed(this, 300)
                } else {
                    isSpeaking = false
                    val mode = if (isAwaitingCommand) "এখন আপনার কমান্ড বলুন..." else "'জারভিস' বলে ডাকুন..."
                    updateUI("মাইক্রোফোন শুনছে ($mode)")
                }
            }
        }, 600)
    }

    // সরাসরি মাইক্রোফোন থেকে আওয়াজ শোনার লুপ (Samsung S23 Ultra-তে ১০০% কাজ করবে)
    @SuppressLint("MissingPermission")
    private fun startRawAudioLoop() {
        thread {
            val sampleRate = 16000
            val bufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(3200)

            var recorder: AudioRecord? = null
            try {
                recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
                recorder.startRecording()

                val buffer = ShortArray(1600) // প্রতি ১০০ মিলিসেকেন্ডের অডিও বাফার
                val speechOutput = ByteArrayOutputStream()
                var isRecordingVoice = false
                var silenceChunks = 0
                var voiceChunks = 0

                while (isServiceRunning) {
                    if (isSpeaking || isProcessing || tts?.isSpeaking == true) {
                        speechOutput.reset()
                        isRecordingVoice = false
                        silenceChunks = 0
                        voiceChunks = 0
                        Thread.sleep(200)
                        continue
                    }

                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        var sum = 0.0
                        for (i in 0 until read) {
                            sum += buffer[i] * buffer[i]
                        }
                        val rms = sqrt(sum / read)

                        // আপনি কথা বললেই কণ্ঠের তীব্রতা (RMS > 450) ধরা পড়বে
                        if (rms > 450) {
                            if (!isRecordingVoice) {
                                isRecordingVoice = true
                                speechOutput.reset()
                                voiceChunks = 0
                                updateUI("আপনার কথা রেকর্ড হচ্ছে...")
                            }
                            silenceChunks = 0
                            voiceChunks++
                            writeShortsToBytes(buffer, read, speechOutput)
                        } else if (isRecordingVoice) {
                            silenceChunks++
                            writeShortsToBytes(buffer, read, speechOutput)

                            // কথা বলা শেষ করে ১.২ সেকেন্ড থামলে বা ৫ সেকেন্ড পূর্ণ হলে প্রসেস করবে
                            if (silenceChunks > 12 || voiceChunks > 50) {
                                isRecordingVoice = false
                                val pcmBytes = speechOutput.toByteArray()
                                speechOutput.reset()

                                if (voiceChunks >= 3) {
                                    val wavBytes = addWavHeader(pcmBytes, sampleRate)
                                    processAudioWithGemini(wavBytes)
                                } else {
                                    val mode = if (isAwaitingCommand) "এখন আপনার কমান্ড বলুন..." else "'জারভিস' বলে ডাকুন..."
                                    updateUI("মাইক্রোফোন শুনছে ($mode)")
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                updateUI("মাইক্রোফোন ত্রুটি: ${e.message}")
            } finally {
                try {
                    recorder?.stop()
                    recorder?.release()
                } catch (_: Exception) {}
            }
        }
    }

    private fun writeShortsToBytes(shorts: ShortArray, count: Int, out: ByteArrayOutputStream) {
        val byteBuf = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) {
            byteBuf.putShort(shorts[i])
        }
        out.write(byteBuf.array())
    }

    private fun addWavHeader(pcmData: ByteArray, sampleRate: Int): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        val totalDataLen = pcmData.size + 36
        val byteRate = sampleRate * 2

        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(totalDataLen)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1.toShort()) // PCM
        header.putShort(1.toShort()) // Mono
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(2.toShort())
        header.putShort(16.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcmData.size)

        val out = ByteArrayOutputStream()
        out.write(header.array())
        out.write(pcmData)
        return out.toByteArray()
    }

    private fun processAudioWithGemini(wavBytes: ByteArray) {
        val rawKey = getSharedPreferences("RaiyanPrefs", Context.MODE_PRIVATE).getString("API_KEY", "") ?: ""
        val apiKey = rawKey.replace("\\s".toRegex(), "")
        if (apiKey.isEmpty()) {
            speak("দয়া করে রাইয়ান অ্যাপে আপনার এপিআই কি বসান")
            return
        }

        isProcessing = true
        updateUI("জারভিস আপনার কথা বিশ্লেষণ করছে...")

        try {
            val base64Audio = Base64.encodeToString(wavBytes, Base64.NO_WRAP)
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.doOutput = true

            val modePrompt = if (!isAwaitingCommand) {
                """
                Listen to the user's audio carefully.
                1. If the user ONLY called the wake word ("Jarvis", "জারভিস", "জার্ভিস", "সার্ভিস") without a specific command, return:
                   {"status": "WAKE_ONLY", "action": "NONE", "target": "", "reply": "জী বলুন কি সহায়তা করবো"}
                2. If the user called "Jarvis" AND gave a command (or if the audio is a clear command in Bangla), execute it:
                   {"status": "COMMAND", "action": "FLASH_ON|FLASH_OFF|VOLUME_UP|VOLUME_DOWN|WIFI_SETTINGS|CALL|OPEN_APP|NONE", "target": "phone number or English app name if needed", "reply": "Short helpful reply in Bangla"}
                3. If the audio is just background noise or unrelated speech not directed at Jarvis, return:
                   {"status": "IGNORE", "action": "NONE", "target": "", "reply": ""}
                """.trimIndent()
            } else {
                """
                The user is giving a direct command in Bangla after waking Jarvis.
                Return ONLY a valid JSON object:
                {"status": "COMMAND", "action": "FLASH_ON|FLASH_OFF|VOLUME_UP|VOLUME_DOWN|WIFI_SETTINGS|CALL|OPEN_APP|NONE", "target": "phone number or English app name if needed", "reply": "Short helpful reply in Bangla"}
                """.trimIndent()
            }

            val partsArray = JSONArray().apply {
                put(JSONObject().put("text", modePrompt))
                put(JSONObject().put("inlineData", JSONObject().apply {
                    put("mimeType", "audio/wav")
                    put("data", base64Audio)
                }))
            }

            val body = JSONObject().apply {
                put("contents", JSONArray().put(JSONObject().put("parts", partsArray)))
                put("generationConfig", JSONObject().apply {
                    put("responseMimeType", "application/json")
                })
            }

            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val responseCode = conn.responseCode
            if (responseCode != 200) {
                val err = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $responseCode"
                updateUI("API Error ($responseCode): $err")
                isProcessing = false
                return
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
            val status = resultJson.optString("status", "COMMAND")
            val action = resultJson.optString("action", "NONE")
            val target = resultJson.optString("target", "")
            val reply = resultJson.optString("reply", "ঠিক আছে")

            mainHandler.post {
                isProcessing = false
                when (status) {
                    "WAKE_ONLY" -> {
                        isAwaitingCommand = true
                        speak("জী বলুন কি সহায়তা করবো")
                    }
                    "COMMAND" -> {
                        isAwaitingCommand = false
                        executePhoneAction(action, target)
                        if (reply.isNotEmpty()) speak(reply)
                    }
                    else -> {
                        updateUI("মাইক্রোফোন শুনছে ('জারভিস' বলে ডাকুন...)")
                    }
                }
            }
        } catch (e: Exception) {
            isProcessing = false
            updateUI("সংযোগ ত্রুটি: ${e.message}")
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
        isServiceRunning = false
        wakeLock?.release()
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
