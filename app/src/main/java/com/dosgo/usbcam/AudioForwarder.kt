package com.dosgo.usbcam

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log

/**
 * USB(UAC) 音频捕获 → 扬声器实时转发。
 * HDMI 采集卡的音频在 Android 上是一个 USB 音频输入设备（source），
 * 系统不会自动外放，需要从这里录音再写进 AudioTrack。
 */
class AudioForwarder(private val context: Context) {

    companion object {
        private const val TAG = "UsbCamAudio"
        private const val SAMPLE_RATE = 48000
    }

    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    /** 是否正在转发 */
    fun isRunning(): Boolean = running

    private fun findUsbInput(): AudioDeviceInfo? {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        for (d in am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            if (!d.isSource) continue
            when (d.type) {
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_ACCESSORY -> return d
            }
        }
        return null
    }

    /** 启动转发，成功返回 true */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        val usbInput = findUsbInput() ?: run {
            Log.d(TAG, "没有找到 USB 音频输入设备")
            return false
        }

        val chIn = AudioFormat.CHANNEL_IN_STEREO
        val chOut = AudioFormat.CHANNEL_OUT_STEREO
        val fmt = AudioFormat.ENCODING_PCM_16BIT
        val minIn = AudioRecord.getMinBufferSize(SAMPLE_RATE, chIn, fmt).coerceAtLeast(8192)
        val minOut = AudioTrack.getMinBufferSize(SAMPLE_RATE, chOut, fmt).coerceAtLeast(16384)

        var rec = AudioRecord(MediaRecorder.AudioSource.DEFAULT, SAMPLE_RATE, chIn, fmt, minIn * 2)
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            rec = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, chIn, fmt, minIn * 2)
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            Log.e(TAG, "AudioRecord 初始化失败")
            return false
        }
        rec.preferredDevice = usbInput

        val trk = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(fmt)
                .setChannelMask(chOut)
                .build(),
            minOut * 2,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        if (trk.state != AudioTrack.STATE_INITIALIZED) {
            rec.release()
            trk.release()
            Log.e(TAG, "AudioTrack 初始化失败")
            return false
        }

        record = rec
        track = trk
        running = true
        rec.startRecording()
        trk.play()

        thread = Thread {
            val buf = ByteArray(4096)
            Log.d(TAG, "音频转发启动: 设备=${usbInput.productName} $SAMPLE_RATE Hz")
            while (running) {
                val n = try {
                    rec.read(buf, 0, buf.size)
                } catch (e: Exception) {
                    break
                }
                if (n > 0) {
                    val w = trk.write(buf, 0, n)
                    if (w < 0) break
                }
            }
            Log.d(TAG, "音频转发结束")
        }.also { it.name = "UsbAudioForward" }.apply { start() }
        return true
    }

    fun stop() {
        running = false
        thread?.join(800)
        thread = null
        try { record?.stop() } catch (_: Exception) {}
        try { record?.release() } catch (_: Exception) {}
        record = null
        try { track?.stop() } catch (_: Exception) {}
        try { track?.release() } catch (_: Exception) {}
        track = null
    }
}
