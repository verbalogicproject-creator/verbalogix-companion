/*
 * Copyright 2026 Eyal Nof (Verbalogix)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.verbalogix.companion.http

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.verbalogix.companion.MainActivity
import com.verbalogix.companion.MainApplication
import com.verbalogix.companion.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that hosts the Ktor HTTP + WebSocket server.
 *
 * Why a separate FGS from the AccessibilityService:
 *  - AccessibilityService has its own lifecycle bound to the OS. Running Ktor
 *    inside it works but makes shutdown ambiguous when the user toggles the
 *    service off via settings.
 *  - FGS keeps Ktor running even if the OS (briefly) re-binds the
 *    AccessibilityService.
 *  - Foreground notification is a Google Play compliance requirement for
 *    long-running background work; making it explicit here keeps the rule
 *    visible in code review.
 *
 * `foregroundServiceType=specialUse` is declared in the manifest with the
 * required PROPERTY_SPECIAL_USE_FGS_SUBTYPE description.
 */
class EngineHttpService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverJob: Job? = null
    private var server: LocalHttpServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand")
        startForegroundWithNotification(port = DEFAULT_PORT)
        startServerIfNeeded()
        // START_STICKY so the OS restarts us if it kills us — the user
        // expects the bridge to come back after memory pressure.
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy — stopping server")
        serverJob?.cancel()
        server?.stop()
        server = null
        scope.cancel()
        super.onDestroy()
    }

    private fun startServerIfNeeded() {
        if (server != null) return
        val s = LocalHttpServer(
            context = applicationContext,
            tokenStore = TokenStore(applicationContext),
            port = DEFAULT_PORT,
        )
        server = s
        serverJob = scope.launch {
            runCatching { s.start() }
                .onFailure { Log.e(TAG, "Ktor server crashed", it) }
        }
    }

    private fun startForegroundWithNotification(port: Int) {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification: Notification = NotificationCompat.Builder(this, MainApplication.FGS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(getString(R.string.fgs_notification_title))
            .setContentText(getString(R.string.fgs_notification_text, port))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                MainApplication.FGS_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(MainApplication.FGS_NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "VbxHttpService"
        const val DEFAULT_PORT = 3737

        fun startForeground(ctx: Context) {
            val i = Intent(ctx, EngineHttpService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun stopForeground(ctx: Context) {
            ctx.stopService(Intent(ctx, EngineHttpService::class.java))
        }
    }
}
