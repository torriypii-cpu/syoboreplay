package com.example.retrorec

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        status = TextView(this).apply { textSize = 18f }
        root.addView(status)

        fun button(label: String, onClick: () -> Unit) {
            root.addView(Button(this).apply {
                text = label
                setOnClickListener { onClick() }
            })
        }

        button("録音開始") { startRecording() }
        button("直近5分を保存") { send(RecorderService.ACTION_SAVE, 5) }
        button("直近10分を保存") { send(RecorderService.ACTION_SAVE, 10) }
        button("直近30分を保存") { send(RecorderService.ACTION_SAVE, 30) }
        button("停止（保持中の音声は破棄）") { send(RecorderService.ACTION_STOP) }
        button("電池の最適化を解除（常時動作用）") { requestIgnoreBattery() }

        root.addView(TextView(this).apply {
            text = "\n音声は端末のメモリ内だけに保持され、保存ボタンを押したときだけ Music/RetroRec に書き出されます。\n" +
                   "録音する場合は周囲の人への配慮と、法令・ルールの確認をお願いします。"
        })
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun refresh() {
        val b = RecorderService.buffer
        val text = if (RecorderService.running && b != null) {
            val sec = b.bufferedUs() / 1_000_000
            "● 録音中\n保持: %d分%02d秒 / 最大%d分 (%.1f MB)".format(
                sec / 60, sec % 60, RecorderService.MAX_MINUTES, b.bufferedBytes() / 1e6
            )
        } else "停止中"
        status.text = text + "\n" + RecorderService.lastMessage
    }

    private fun startRecording() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 1)
            return
        }
        startForegroundService(
            Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_START)
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startRecording()
        } else {
            Toast.makeText(this, "マイクの権限が必要です", Toast.LENGTH_LONG).show()
        }
    }

    private fun send(action: String, minutes: Int = 0) {
        startService(
            Intent(this, RecorderService::class.java)
                .setAction(action)
                .putExtra(RecorderService.EXTRA_MINUTES, minutes)
        )
    }

    private fun requestIgnoreBattery() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "すでに解除済みです", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        )
    }
}
