/*
 * Copyright 2026 Eyal Nof (Verbalogix)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.verbalogix.companion.capture

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.verbalogix.companion.MainActivity
import com.verbalogix.companion.MainApplication
import com.verbalogix.companion.R
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * Foreground service that owns the MediaProjection session.
 *
 * Lifecycle:
 *   MainActivity → system screen-capture dialog → result Intent →
 *   startForegroundService(EXTRA_RESULT_CODE + EXTRA_DATA) →
 *   onStartCommand → startForeground (type = MEDIA_PROJECTION) →
 *   MediaProjectionManager.getMediaProjection(resultCode, data) →
 *   register VirtualDisplay + ImageReader → ready.
 *
 *   On every GET /screenshot:
 *   ScreenCaptureService.current()?.captureNow() → PNG bytes.
 *
 *   On revoke (user or death):
 *   stopForeground + projection.stop() + clear currentRef.
 *
 * Thread safety: the service runs on the main thread but captureNow()
 * is called from Ktor worker coroutines. ImageReader.acquireLatestImage
 * is documented thread-safe for single-reader use — we serialize via a
 * synchronized block anyway for the Bitmap + PNG encode path.
 */
class ScreenCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var displayWidth: Int = 0
    private var displayHeight: Int = 0
    private var displayDpi: Int = 0

    // Stopped callback so we notice if Android kills the projection
    // (e.g., user opens a secure surface or revokes via the notification).
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection.onStop — clearing state")
            teardown()
            currentRef.compareAndSet(this@ScreenCaptureService, null)
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand")

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode == 0 || data == null) {
            Log.w(TAG, "missing resultCode/data — shutting down")
            stopSelf()
            return START_NOT_STICKY
        }

        // MUST start foreground with the mediaProjection type BEFORE
        // calling getMediaProjection. Android 14+ enforces this ordering
        // and throws SecurityException otherwise.
        startForegroundWithNotification()

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = try {
            mpm.getMediaProjection(resultCode, data)
        } catch (t: Throwable) {
            Log.e(TAG, "getMediaProjection failed", t)
            stopSelf()
            return START_NOT_STICKY
        }

        proj.registerCallback(projectionCallback, null)
        projection = proj

        capturePipelineInit()
        currentRef.set(this)

        // START_NOT_STICKY — we don't want Android to silently restart
        // the capture session after a crash. The permission must be
        // granted again through the system dialog.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        teardown()
        currentRef.compareAndSet(this, null)
        super.onDestroy()
    }

    // ── Capture pipeline ─────────────────────────────────────────────

    private fun capturePipelineInit() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        displayWidth = metrics.widthPixels
        displayHeight = metrics.heightPixels
        displayDpi = metrics.densityDpi

        // ImageReader with a small buffer (2) — the agent only ever needs
        // the latest frame, not a queue. Bigger buffers waste RAM.
        imageReader = ImageReader.newInstance(
            displayWidth, displayHeight,
            PixelFormat.RGBA_8888,
            /* maxImages = */ 2,
        )

        virtualDisplay = projection?.createVirtualDisplay(
            "verbalogix-companion-capture",
            displayWidth, displayHeight, displayDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            /* callback = */ null,
            /* handler = */ null,
        )

        Log.i(TAG, "capture pipeline ready: ${displayWidth}x$displayHeight @ $displayDpi dpi")
    }

    /**
     * Capture one frame, encode as PNG. Called from a background thread
     * (the Ktor route handler). Returns null if no frame is available.
     *
     * Performance notes:
     *   - acquireLatestImage drains all buffered frames and returns only
     *     the newest. On a stable screen, subsequent calls within ~16ms
     *     will return null because there's no new frame to deliver — we
     *     handle that by retrying once with a small sleep.
     *   - PNG compression at level 100 on a ~3M-pixel phone screen takes
     *     ~200-400 ms. Acceptable for the 1-FPS agent use case; if we
     *     ever need faster, switch to JPEG quality 85.
     */
    @Synchronized
    fun captureNow(): ByteArray? {
        val reader = imageReader ?: return null
        var image: Image? = reader.acquireLatestImage()
        if (image == null) {
            // Small retry for the first call after the VirtualDisplay
            // starts — the first frame can take ~100 ms to land.
            Thread.sleep(120)
            image = reader.acquireLatestImage() ?: return null
        }
        return try {
            val bitmap = imageToBitmap(image)
            val baos = ByteArrayOutputStream(bitmap.byteCount / 4)
            bitmap.compress(Bitmap.CompressFormat.PNG, /* quality ignored for PNG */ 100, baos)
            bitmap.recycle()
            baos.toByteArray()
        } finally {
            image.close()
        }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        // rowStride is in bytes; pixelStride is 4 for RGBA_8888. Android
        // often aligns to 16-px boundaries, so the actual row width in
        // bytes may be larger than displayWidth * 4. We extract with the
        // aligned width, then crop.
        val rowPadding = rowStride - pixelStride * image.width
        val alignedWidth = image.width + rowPadding / pixelStride

        val padded = Bitmap.createBitmap(
            alignedWidth, image.height, Bitmap.Config.ARGB_8888,
        )
        padded.copyPixelsFromBuffer(buffer)

        // Crop the padding back off so the delivered bitmap matches the
        // real screen resolution. Without this, the agent's coordinate
        // math would be wrong on any device with non-multiple-of-16 width.
        return if (rowPadding == 0) {
            padded
        } else {
            val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
            padded.recycle()
            cropped
        }
    }

    private fun teardown() {
        try { virtualDisplay?.release() } catch (_: Throwable) {}
        try { imageReader?.close() } catch (_: Throwable) {}
        try { projection?.unregisterCallback(projectionCallback) } catch (_: Throwable) {}
        try { projection?.stop() } catch (_: Throwable) {}
        virtualDisplay = null
        imageReader = null
        projection = null
    }

    // ── Foreground notification ──────────────────────────────────────

    private fun startForegroundWithNotification() {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification: Notification = NotificationCompat.Builder(
            this, MainApplication.FGS_CHANNEL_ID,
        )
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(getString(R.string.fgs_screen_capture_title))
            .setContentText(getString(R.string.fgs_screen_capture_text))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "VbxScreenCapture"
        private const val NOTIFICATION_ID = 0x76_62_78_02.toInt()  // "vbx\x02"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        private val currentRef = AtomicReference<ScreenCaptureService?>()

        /** Returns the running service instance, or null if permission not granted. */
        fun current(): ScreenCaptureService? = currentRef.get()

        /** Ask the OS politely to stop the capture session. */
        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ScreenCaptureService::class.java))
        }
    }
}
