package com.example.pairhelper

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(40), dp(20), dp(20))
        }
        col.addView(TextView(this).apply {
            textSize = 16f
            text = "Pair Helper\n\n" +
                "1. Allow draw-over-apps.\n" +
                "2. Tap Start helper and accept screen capture.\n" +
                "3. Open the card game. When the board shows (all cards face-down) the helper " +
                "watches the preview flip, finds the pairs, and puts matching numbers on the cards.\n\n" +
                "Panel buttons: Scan = scan now, Grid = show grid outline, arrows / W / H = " +
                "line the grid up with the cards, Hide = hide numbers, X = stop."
        })
        col.addView(Button(this).apply {
            text = "1. Allow draw-over-apps"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        })
        col.addView(Button(this).apply {
            text = "2. Start helper"
            setOnClickListener { start() }
        })
        setContentView(col)
    }

    private fun start() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Do step 1 first", Toast.LENGTH_LONG).show(); return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
            Toast.makeText(this, "Allow notifications, then tap Start again", Toast.LENGTH_LONG).show()
            return
        }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), 1)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, HelperService::class.java).putExtra("code", resultCode).putExtra("data", data)
            startForegroundService(i)
            moveTaskToBack(true)
        }
    }
}
