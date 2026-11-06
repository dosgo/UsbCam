package com.dosgo.usbcam

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.usb.UsbDevice
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.serenegiant.usb.IFrameCallback
import com.serenegiant.usb.USBMonitor
import com.serenegiant.usb.UVCCamera
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "UsbCamApp"
    }

    private var usbMonitor: USBMonitor? = null
    private var uvcCamera: UVCCamera? = null
    private var currentSurface: Surface? = null
    private var previewStarted = false
    private var sizeIndex = 0
    private var previewW = 0
    private var previewH = 0
    private var capturing = false

    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null

    private lateinit var surfaceView: SurfaceView
    private lateinit var btnSize: Button
    private lateinit var btnShot: Button
    private lateinit var btnAudio: Button
    private lateinit var btnFps: Button
    private lateinit var btnMenu: Button
    private lateinit var menuPanel: android.view.View

    private val audioForwarder by lazy { AudioForwarder(this) }

    // 帧率档位：0=自动(5~60 让摄像头挑最高)，其余取自 UVC 报告的当前分辨率可用帧率
    private var supportedFps: FloatArray = FloatArray(0)
    private var fpsIndex = 0
    private var previewFmt = UVCCamera.FRAME_FORMAT_MJPEG
    private val switchBusy = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun fpsLabel(i: Int): String =
        if (i == 0) "自动" else {
            val f = supportedFps[i - 1]
            if (f == f.toLong().toFloat()) "${f.toLong()}" else String.format("%.1f", f)
        }

    // 依次尝试的分辨率候选：宽、高、帧格式（高分辨率优先，画质最好）
    private val sizeCandidates = arrayOf(
        intArrayOf(1920, 1080, UVCCamera.FRAME_FORMAT_MJPEG),
        intArrayOf(1280, 960, UVCCamera.FRAME_FORMAT_MJPEG),
        intArrayOf(1280, 720, UVCCamera.FRAME_FORMAT_MJPEG),
        intArrayOf(640, 480, UVCCamera.FRAME_FORMAT_MJPEG),
        intArrayOf(640, 480, UVCCamera.FRAME_FORMAT_YUYV),
        intArrayOf(1280, 720, UVCCamera.FRAME_FORMAT_YUYV)
    )

    private var permissionRequesting = false

    private val deviceListener = object : USBMonitor.OnDeviceConnectListener {
        override fun onAttach(device: UsbDevice) {
            Log.d(TAG, "onAttach: v=${device.vendorId} p=${device.productId} cls=${device.deviceClass} ifaces=${device.interfaceCount}")
            if (!isUvcDevice(device)) {
                Log.d(TAG, "非摄像头设备，跳过")
                return
            }
            if (usbMonitor?.hasPermission(device) == true) {
                // 已有权限（弹窗时授过但回执丢失的情况），直接连
                Log.d(TAG, "已有 USB 权限，直接连接")
                connectDevice(device)
            } else if (!permissionRequesting) {
                permissionRequesting = true
                runOnUiThread { toast("请求 USB 权限…") }
                usbMonitor?.requestPermission(device)
            }
        }

        override fun onDettach(device: UsbDevice?) {
            Log.d(TAG, "onDettach")
            permissionRequesting = false
            workerHandler?.post { releaseCamera() }
            runOnUiThread { toast("摄像头已拔出") }
        }

        override fun onConnect(device: UsbDevice, ctrlBlock: USBMonitor.UsbControlBlock, createNew: Boolean) {
            Log.d(TAG, "onConnect: v=${device.vendorId} p=${device.productId} createNew=$createNew")
            permissionRequesting = false
            workerHandler?.post { openCamera(ctrlBlock) }
        }

        override fun onDisconnect(device: UsbDevice, ctrlBlock: USBMonitor.UsbControlBlock) {
            Log.d(TAG, "onDisconnect")
            workerHandler?.post { releaseCamera() }
        }

        override fun onCancel(device: UsbDevice?) {
            Log.d(TAG, "onCancel: 权限被拒绝")
            permissionRequesting = false
            runOnUiThread { toast("USB 权限被拒绝，无法打开摄像头") }
        }
    }

    /** 直接创建 ControlBlock 并打开相机（绕过广播回执） */
    private fun connectDevice(device: UsbDevice) {
        workerHandler?.post {
            try {
                val ctrlBlock = usbMonitor?.openDevice(device)
                if (ctrlBlock != null) {
                    openCamera(ctrlBlock)
                } else {
                    Log.e(TAG, "openDevice 返回 null")
                }
            } catch (e: Exception) {
                Log.e(TAG, "连接失败", e)
            }
        }
    }

    /** 判断是否为 UVC 摄像头：设备级视频类(0x0E) 或任一接口为视频类 */
    private fun isUvcDevice(device: UsbDevice): Boolean {
        if (device.deviceClass == 14) return true
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == 14) return true
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        surfaceView = findViewById(R.id.surface)
        btnSize = findViewById(R.id.btnSize)
        btnShot = findViewById(R.id.btnShot)
        btnAudio = findViewById(R.id.btnAudio)
        btnFps = findViewById(R.id.btnFps)
        btnMenu = findViewById(R.id.btnMenu)
        menuPanel = findViewById(R.id.menuPanel)

        btnSize.setOnClickListener { switchSize(); collapseMenu() }
        btnShot.setOnClickListener { capture(); collapseMenu() }
        btnAudio.setOnClickListener { toggleAudio(); collapseMenu() }
        btnFps.setOnClickListener { switchFps(); collapseMenu() }
        btnMenu.setOnClickListener {
            menuPanel.visibility = if (menuPanel.visibility == android.view.View.VISIBLE)
                android.view.View.GONE else android.view.View.VISIBLE
        }

        // Android 14+：访问 USB 视频类设备必须先持有 CAMERA 运行时权限；音频转发需要 RECORD_AUDIO
        val needPerms = mutableListOf(Manifest.permission.CAMERA)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needPerms.add(Manifest.permission.RECORD_AUDIO)
        }
        ActivityCompat.requestPermissions(this, needPerms.toTypedArray(), 2)

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.d(TAG, "surfaceCreated")
                currentSurface = holder.surface
                workerHandler?.post { tryStartPreview() }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.d(TAG, "surfaceDestroyed")
                currentSurface = null
                workerHandler?.post { stopPreviewOnly() }
            }
        })
    }

    override fun onStart() {
        super.onStart()
        hideSystemBars()
        workerThread = HandlerThread("UvcWorker").also { it.start() }
        workerHandler = Handler(workerThread!!.looper)
        usbMonitor = USBMonitor(this, deviceListener)
        usbMonitor?.register()
    }

    override fun onStop() {
        usbMonitor?.unregister()
        usbMonitor?.destroy()
        usbMonitor = null
        workerHandler?.post { releaseCamera() }
        workerThread?.quitSafely()
        workerThread = null
        workerHandler = null
        super.onStop()
    }

    // ---------- 摄像头操作（均在 worker 线程） ----------

    private fun openCamera(ctrlBlock: USBMonitor.UsbControlBlock) {
        if (uvcCamera != null) return
        try {
            val cam = UVCCamera()
            cam.open(ctrlBlock)
            Log.d(TAG, "UVCCamera.open 成功, supportedSize=${try { cam.supportedSize } catch (e: Exception) { "?" }}")

            var opened = false
            for (i in sizeCandidates.indices) {
                val idx = (sizeIndex + i) % sizeCandidates.size
                val c = sizeCandidates[idx]
                Log.d(TAG, "尝试分辨率 ${c[0]}x${c[1]} fmt=${c[2]} fps=${fpsLabel(fpsIndex)}")
                if (applySize(cam, c[0], c[1], c[2])) {
                    sizeIndex = idx
                    previewW = c[0]
                    previewH = c[1]
                    previewFmt = c[2]
                    opened = true
                    break
                }
                Log.d(TAG, "不支持，试下一个")
            }

            if (!opened) {
                cam.close()
                cam.destroy()
                runOnUiThread { toast("该摄像头没有可用分辨率") }
                return
            }

            uvcCamera = cam
            refreshSupportedFps(cam)
            runOnUiThread {
                btnSize.text = "${previewW}x${previewH}"
                toast("摄像头已连接 ${previewW}x${previewH}")
                adjustSurfaceAspect(previewW, previewH)
                autoStartAudio()
            }
            tryStartPreview()
        } catch (e: Exception) {
            runOnUiThread { toast("打开摄像头失败: ${e.message}") }
        }
    }

    /** 按当前帧率档位应用分辨率，成功返回 true（非法档位自动回退，绝不留下坏状态） */
    private fun applySize(cam: UVCCamera, w: Int, h: Int, fmt: Int): Boolean {
        // 固定档：只在 UVC 报告过支持时才用
        if (fpsIndex > 0 && fpsIndex - 1 < supportedFps.size) {
            val f = Math.round(supportedFps[fpsIndex - 1])
            try {
                cam.setPreviewSize(w, h, f, f, fmt, UVCCamera.DEFAULT_BANDWIDTH)
                return true
            } catch (e: Exception) { }
        }
        // 自动档（或固定档失败回退）
        return try {
            cam.setPreviewSize(w, h, 5, 60, fmt, UVCCamera.DEFAULT_BANDWIDTH)
            true
        } catch (e: Exception) {
            try {
                cam.setPreviewSize(w, h, fmt)
                true
            } catch (e2: Exception) {
                false
            }
        }
    }

    /** 从 UVC 描述符读取当前分辨率+格式下支持的帧率档位（worker 线程） */
    private fun refreshSupportedFps(cam: UVCCamera) {
        val raw = try { cam.supportedSize } catch (e: Exception) { "<err:${e.message}>" }
        Log.w(TAG, "UVC supportedSize 原始JSON: ${raw.take(600)}")
        val all = try { cam.getSupportedSizeList() } catch (e: Exception) { emptyList() }
        Log.w(TAG, "解析出 ${all.size} 个 Size: " + all.joinToString { "${it.width}x${it.height}(fps=${it.fps?.joinToString(",")})" }.take(600))
        supportedFps = all
            .filter { it.width == previewW && it.height == previewH && it.fps != null && it.fps.isNotEmpty() }
            .flatMap { it.fps.toList() }
            .filter { it > 0f && it < 1000f }
            // 四舍五入去重：59.94/60、29.97/30 合并成一档，避免按钮按不完
            .groupBy { Math.round(it) }
            .map { it.key.toFloat() }
            .sortedDescending()
            .toFloatArray()
        if (supportedFps.isEmpty()) {
            // 便宜采集卡固件常不报 frame interval 描述符，退回常用档（协商不支持也不会崩）
            supportedFps = floatArrayOf(60f, 30f, 15f)
            Log.w(TAG, "UVC 未报告帧率，使用常用档兜底")
        }
        fpsIndex = 0
        Log.w(TAG, "当前 ${previewW}x${previewH} 可用帧率: ${supportedFps.joinToString("/")}")
        runOnUiThread { btnFps.text = "帧率:自动" }
    }

    private fun collapseMenu() {
        if (::menuPanel.isInitialized) menuPanel.visibility = android.view.View.GONE
    }

    private fun tryStartPreview() {
        val cam = uvcCamera ?: return
        val surface = currentSurface ?: run { Log.d(TAG, "预览等待 Surface（还没就绪）"); return }
        if (previewStarted) return
        try {
            Log.d(TAG, "startPreview on surface=$surface")
            cam.setPreviewDisplay(surface)
            cam.startPreview()
            previewStarted = true
            Log.d(TAG, "startPreview 成功")
        } catch (e: Exception) {
            Log.e(TAG, "启动预览失败", e)
            runOnUiThread { toast("启动预览失败: ${e.message}") }
        }
    }

    private fun stopPreviewOnly() {
        if (!previewStarted) return
        try { uvcCamera?.stopPreview() } catch (e: Exception) { }
        previewStarted = false
    }

    private fun releaseCamera() {
        val cam = uvcCamera ?: return
        uvcCamera = null
        previewStarted = false
        try { cam.setFrameCallback(null, 0) } catch (e: Exception) { }
        try { cam.stopPreview() } catch (e: Exception) { }
        try { cam.destroy() } catch (e: Exception) { }
        runOnUiThread {
            btnSize.text = getString(R.string.btn_size)
            stopAudio()
        }
    }

    // ---------- 音频转发 ----------

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 3 && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            toggleAudio()
        }
    }

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** 摄像头连上后，若已有录音权限且存在 USB 音频输入则自动开播 */
    private fun autoStartAudio() {
        if (!hasAudioPermission()) return
        if (audioForwarder.start()) {
            btnAudio.text = getString(R.string.btn_audio_on)
            Log.d(TAG, "音频转发已自动开启")
        } else {
            Log.d(TAG, "没有 USB 音频输入，跳过自动开播")
        }
    }

    private fun toggleAudio() {
        if (audioForwarder.isRunning()) {
            stopAudio()
            toast("已静音")
            return
        }
        if (!hasAudioPermission()) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), 3
            )
            toast("需要麦克风权限用于 USB 音频转发，请授权后再点")
            return
        }
        if (audioForwarder.start()) {
            btnAudio.text = getString(R.string.btn_audio_on)
            toast("USB 音频已开启")
        } else {
            toast("未检测到 USB 音频输入设备")
        }
    }

    private fun stopAudio() {
        if (audioForwarder.isRunning()) {
            audioForwarder.stop()
        }
        btnAudio.text = getString(R.string.btn_audio_off)
    }

    private fun switchSize() {
        val handler = workerHandler ?: return
        if (!switchBusy.compareAndSet(false, true)) return   // native 流不支持并发切换，防抖
        handler.post {
            try {
                val cam = uvcCamera ?: run {
                    runOnUiThread { toast("未连接摄像头") }
                    return@post
                }
                if (previewStarted) {
                    try { cam.stopPreview() } catch (e: Exception) { }
                    previewStarted = false
                }
                for (i in 1..sizeCandidates.size) {
                    val idx = (sizeIndex + i) % sizeCandidates.size
                    val c = sizeCandidates[idx]
                    if (applySize(cam, c[0], c[1], c[2])) {
                        sizeIndex = idx
                        previewW = c[0]
                        previewH = c[1]
                        previewFmt = c[2]
                        break
                    }
                }
                refreshSupportedFps(cam)
                runOnUiThread {
                    btnSize.text = "${previewW}x${previewH}"
                    adjustSurfaceAspect(previewW, previewH)
                }
                tryStartPreview()
            } finally {
                switchBusy.set(false)
            }
        }
    }

    private fun switchFps() {
        val handler = workerHandler ?: return
        if (!switchBusy.compareAndSet(false, true)) return   // native 流不支持并发切换，防抖
        handler.post {
            try {
                val cam = uvcCamera ?: run {
                    runOnUiThread { toast("未连接摄像头") }
                    return@post
                }
                val n = supportedFps.size + 1
                val next = (fpsIndex + 1) % n
                if (previewStarted) {
                    try { cam.stopPreview() } catch (e: Exception) { }
                    previewStarted = false
                }
                val prev = fpsIndex
                fpsIndex = next
                if (!applySize(cam, previewW, previewH, previewFmt)) {
                    fpsIndex = prev
                    runOnUiThread { toast("该帧率不可用，已保持原档") }
                }
                runOnUiThread {
                    btnFps.text = "帧率:${fpsLabel(fpsIndex)}"
                    toast("帧率 ${fpsLabel(fpsIndex)}")
                }
                tryStartPreview()
            } finally {
                switchBusy.set(false)
            }
        }
    }

    private fun capture() {
        if (uvcCamera == null || !previewStarted) {
            toast("未连接摄像头")
            return
        }
        if (Build.VERSION.SDK_INT < 29 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1
            )
            toast("需要存储权限才能保存照片，请再点一次拍照")
            return
        }
        if (capturing) return
        capturing = true

        val w = previewW
        val h = previewH
        uvcCamera?.setFrameCallback(IFrameCallback { buffer ->
            uvcCamera?.setFrameCallback(null, 0)
            try {
                val nv21 = ByteArray(buffer.remaining())
                buffer.get(nv21)
                saveJpeg(nv21, w, h)
            } catch (e: Exception) {
                runOnUiThread { toast("保存失败: ${e.message}") }
            } finally {
                capturing = false
            }
        }, UVCCamera.PIXEL_FORMAT_NV21)
    }

    private fun saveJpeg(nv21: ByteArray, w: Int, h: Int) {
        val yuv = YuvImage(nv21, ImageFormat.NV21, w, h, null)
        val out = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, w, h), 92, out)
        val data = out.toByteArray()
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "USBCAM_$ts.jpg"

        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/UsbCam")
            }
            val uri = applicationContext.contentResolver
                .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("无法写入相册")
            applicationContext.contentResolver.openOutputStream(uri)?.use { it.write(data) }
            runOnUiThread { toast("已保存到相册 Pictures/UsbCam/$name") }
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "UsbCam"
            )
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, name)
            FileOutputStream(file).use { it.write(data) }
            runOnUiThread { toast("已保存: ${file.absolutePath}") }
        }
    }

    // ---------- UI 工具 ----------

    /**
     * 按预览分辨率等比缩放 SurfaceView（居中 + 黑边），避免画面拉伸变形。
     * 等待布局完成后取容器实际尺寸计算。
     */
    private fun adjustSurfaceAspect(pw: Int, ph: Int) {
        if (pw <= 0 || ph <= 0) return
        val container = surfaceView.parent as? android.view.ViewGroup ?: return
        fun apply() {
            val cw = container.width
            val ch = container.height
            if (cw <= 0 || ch <= 0) {
                surfaceView.post { apply() }
                return
            }
            val scale = minOf(cw.toFloat() / pw, ch.toFloat() / ph)
            val lp = surfaceView.layoutParams
            lp.width = (pw * scale).toInt().coerceAtLeast(1)
            lp.height = (ph * scale).toInt().coerceAtLeast(1)
            (lp as android.widget.FrameLayout.LayoutParams).gravity =
                android.view.Gravity.CENTER
            surfaceView.layoutParams = lp
        }
        apply()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun toast(msg: String) {
        runOnUiThread {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }
}
