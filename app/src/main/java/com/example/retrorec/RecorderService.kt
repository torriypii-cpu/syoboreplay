package com.example.retrorec

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecorderService : Service() {

    companion object {
        const val ACTION_START = "com.example.retrorec.START"
        const val ACTION_STOP = "com.example.retrorec.STOP"
        const val ACTION_SAVE = "com.example.retrorec.SAVE"
        const val EXTRA_MINUTES = "minutes"

        const val MAX_MINUTES = 30
        private const val SAMPLE_RATE = 44100
        private const val BIT_RATE = 64000
        private const val CHANNEL_ID = "retrorec"
        private const val CHANNEL_RESUME = "retrorec_resume"
        private const val NOTIF_ID = 1
        private const val NOTIF_RESUME_ID = 2
        private const val PREFS = "retrorec"
        private const val KEY_WANT = "want_running"

        @Volatile var running = false
            private set
        @Volatile var buffer: AacRingBuffer? = null
            private set
        @Volatile var lastMessage: String = ""

        fun wantRunning(ctx: Context): Boolean =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_WANT, false)

        private fun setWantRunning(ctx: Context, v: Boolean) =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_WANT, v).apply()

        /** ユーザーが通知をタップすれば、バックグラウンドからでもマイク付きサービスを開始できる */
        fun postResumeNotification(ctx: Context, text: String) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_RESUME, "録音の再開", NotificationManager.IMPORTANCE_DEFAULT)
            )
            val pi = PendingIntent.getForegroundService(
                ctx, 10,
                Intent(ctx, RecorderService::class.java).setAction(ACTION_START),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = Notification.Builder(ctx, CHANNEL_RESUME)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("さかのぼり録音が止まっています")
                .setContentText(text)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            runCatching { nm.notify(NOTIF_RESUME_ID, n) }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var worker: Thread? = null
    @Volatile private var stopFlag = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start()
            ACTION_STOP -> stopRecording(userRequested = true)
            ACTION_SAVE -> save(intent.getIntExtra(EXTRA_MINUTES, 10))
            null -> {
                // システムが START_STICKY で再起動した場合。ここではマイクを開始できないので通知だけ出す
                if (!running) {
                    if (wantRunning(this)) postResumeNotification(this, "システムに停止されました。タップで再開")
                    stopSelf()
                }
            }
            else -> if (!running) stopSelf()
        }
        return START_STICKY
    }

    // ---------------- 開始・停止 ----------------

    private fun start() {
        createChannel()
        try {
            startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            message("開始できませんでした: ${e.message}")
            if (wantRunning(this)) postResumeNotification(this, "開始に失敗しました。タップで再試行")
            stopSelf()
            return
        }
        getSystemService(NotificationManager::class.java).cancel(NOTIF_RESUME_ID)
        setWantRunning(this, true)
        if (running) return

        val buf = AacRingBuffer(MAX_MINUTES * 60_000_000L)
        buffer = buf
        stopFlag = false
        running = true
        message("録音中（直近${MAX_MINUTES}分を保持）")

        worker = Thread({
            try {
                recordLoop(buf)
            } catch (e: Exception) {
                message("録音エラー: ${e.message}")
            } finally {
                if (!stopFlag) main.post {
                    stopRecording(userRequested = false)
                    if (wantRunning(this)) postResumeNotification(this, "録音エラーで停止しました。タップで再開")
                }
            }
        }, "RetroRec-recorder").also { it.start() }
    }

    private fun stopRecording(userRequested: Boolean) {
        if (userRequested) setWantRunning(this, false)
        stopFlag = true
        worker?.join(2000)
        worker = null
        buffer?.clear()
        buffer = null
        if (running) message("停止しました")
        running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopFlag = true
        super.onDestroy()
    }

    // ---------------- 録音 + AACエンコード ----------------

    @SuppressLint("MissingPermission")
    private fun recordLoop(buf: AacRingBuffer) {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, SAMPLE_RATE)
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw IOException("マイクを初期化できません")
        }

        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)

        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            recorder.startRecording()

            val pcm = ByteArray(4096)
            val info = MediaCodec.BufferInfo()
            var totalSamples = 0L

            while (!stopFlag) {
                val n = recorder.read(pcm, 0, pcm.size)
                if (n < 0) throw IOException("AudioRecord.read エラー: $n")

                var offset = 0
                while (offset < n && !stopFlag) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)!!
                        inBuf.clear()
                        val len = minOf(n - offset, inBuf.remaining())
                        inBuf.put(pcm, offset, len)
                        val ptsUs = totalSamples * 1_000_000L / SAMPLE_RATE
                        codec.queueInputBuffer(inIdx, 0, len, ptsUs, 0)
                        totalSamples += len / 2
                        offset += len
                    }
                    drain(codec, info, buf)
                }
                drain(codec, info, buf)
            }
        } finally {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }

    private fun drain(codec: MediaCodec, info: MediaCodec.BufferInfo, buf: AacRingBuffer) {
        while (true) {
            val outIdx = codec.dequeueOutputBuffer(info, 0)
            if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) return
            if (outIdx < 0) continue
            val out = codec.getOutputBuffer(outIdx)!!
            val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
            if (!isConfig && info.size > 0) {
                val frame = ByteArray(info.size + 7)
                writeAdtsHeader(frame, frame.size)
                out.position(info.offset)
                out.limit(info.offset + info.size)
                out.get(frame, 7, info.size)
                buf.add(info.presentationTimeUs, frame)
            }
            codec.releaseOutputBuffer(outIdx, false)
        }
    }

    /** AAC-LC / 44.1kHz / mono の ADTS ヘッダ（7バイト） */
    private fun writeAdtsHeader(p: ByteArray, len: Int) {
        val profile = 2
        val freqIdx = 4
        val chanCfg = 1
        p[0] = 0xFF.toByte()
        p[1] = 0xF1.toByte()
        p[2] = (((profile - 1) shl 6) + (freqIdx shl 2) + (chanCfg shr 2)).toByte()
        p[3] = (((chanCfg and 3) shl 6) + (len shr 11)).toByte()
        p[4] = ((len and 0x7FF) shr 3).toByte()
        p[5] = (((len and 7) shl 5) + 0x1F).toByte()
        p[6] = 0xFC.toByte()
    }

    // ---------------- 保存 ----------------

    private fun save(minutes: Int) {
        val b = buffer
        if (b == null || !running) {
            message("録音していないので保存できません")
            if (!running) stopSelf()
            return
        }
        val frames = b.snapshot(minutes * 60_000_000L)
        if (frames.isEmpty()) {
            message("まだ音声がありません")
            return
        }
        Thread {
            try {
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val name = "retro_${stamp}_${minutes}min.aac"
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/aac")
                    put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/RetroRec")
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("MediaStore への登録に失敗")
                contentResolver.openOutputStream(uri)!!.use { os -> frames.forEach { os.write(it) } }
                values.clear()
                values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
                message("保存しました: Music/RetroRec/$name")
            } catch (e: Exception) {
                message("保存失敗: ${e.message}")
            }
        }.start()
    }

    // ---------------- 通知・メッセージ ----------------

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "さかのぼり録音", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        fun svc(req: Int, action: String, min: Int = 0) = PendingIntent.getService(
            this, req,
            Intent(this, RecorderService::class.java).setAction(action).putExtra(EXTRA_MINUTES, min),
            flags
        )
        val noIcon: Icon? = null
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("さかのぼり録音中")
            .setContentText("直近${MAX_MINUTES}分をメモリに保持しています")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(noIcon, "10分保存", svc(1, ACTION_SAVE, 10)).build())
            .addAction(Notification.Action.Builder(noIcon, "30分保存", svc(2, ACTION_SAVE, 30)).build())
            .addAction(Notification.Action.Builder(noIcon, "停止", svc(3, ACTION_STOP)).build())
            .build()
    }

    private fun message(text: String) {
        lastMessage = text
        main.post { Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show() }
    }
}
