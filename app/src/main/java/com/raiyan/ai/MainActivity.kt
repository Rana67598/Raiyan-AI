package com.raiyan.ai

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.*

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 80, 50, 50)
            setBackgroundColor(Color.parseColor("#121212"))
        }

        val title = TextView(this).apply {
            text = "Raiyan AI (জারভিস)"
            textSize = 26f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 30)
        }

        val prefs = getSharedPreferences("RaiyanPrefs", Context.MODE_PRIVATE)
        val apiInput = EditText(this).apply {
            hint = "এখানে আপনার Gemini API Key পেস্ট করুন"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setText(prefs.getString("API_KEY", ""))
        }

        val statusText = TextView(this).apply {
            text = "স্ট্যাটাস: বন্ধ আছে। চালু করে 'জারভিস' বলে ডাকুন।"
            textSize = 16f
            setTextColor(Color.LTGRAY)
            setPadding(0, 30, 0, 30)
        }

        val startBtn = Button(this).apply {
            text = "জারভিস চালু করুন (Start)"
            setOnClickListener {
                val key = apiInput.text.toString().trim()
                if (key.isEmpty()) {
                    Toast.makeText(context, "দয়া করে আগে Gemini API Key দিন!", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                prefs.edit().putString("API_KEY", key).apply()
                checkPermissionsAndStart()
                statusText.text = "স্ট্যাটাস: জারভিস সচল আছে! 'জারভিস' বলে ডাকুন।"
            }
        }

        val stopBtn = Button(this).apply {
            text = "জারভিস বন্ধ করুন (Stop)"
            setOnClickListener {
                stopService(Intent(this@MainActivity, JarvisService::class.java))
                statusText.text = "স্ট্যাটাস: জারভিস বন্ধ করা হয়েছে।"
            }
        }

        layout.addView(title)
        layout.addView(apiInput)
        layout.addView(statusText)
        layout.addView(startBtn)
        layout.addView(stopBtn)
        setContentView(layout)

        requestNeededPermissions()
    }

    private fun requestNeededPermissions() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE
        )
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestPermissions(perms.toTypedArray(), 101)
    }

    private fun checkPermissionsAndStart() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            val serviceIntent = Intent(this, JarvisService::class.java)
            startForegroundService(serviceIntent)
        } else {
            requestNeededPermissions()
        }
    }
}
