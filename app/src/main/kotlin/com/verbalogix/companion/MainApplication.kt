/*
 * Copyright 2026 Eyal Nof (Verbalogix)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.verbalogix.companion

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log

/**
 * Application-level initialisation.
 *
 * Responsibilities:
 *  - Create the foreground-service notification channel (required on API 26+)
 *    once, up-front, so starting the FGS later is synchronous.
 *  - Seed the Bearer-token store on first launch.
 *
 * Anything expensive (Ktor engine warm-up, NodeInfo serializer init) lives in
 * the foreground service, not here — `Application.onCreate` runs on the main
 * thread and blocks app launch.
 */
class MainApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Verbalogix Companion ${BuildConfig.VERSION_NAME} starting")

        createFgsNotificationChannel()

        // Enable Chrome DevTools remote inspection for the Engine tab's
        // WebView in debug builds. Lets us answer "why is the page
        // blank" in 30 seconds via chrome://inspect/#devices on a
        // connected laptop. Production builds skip this — release APKs
        // should never expose remote debugging to the network.
        if (BuildConfig.DEBUG) {
            android.webkit.WebView.setWebContentsDebuggingEnabled(true)
        }
    }

    private fun createFgsNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            FGS_CHANNEL_ID,
            getString(R.string.fgs_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.fgs_notification_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        const val TAG = "VbxCompanion"
        const val FGS_CHANNEL_ID = "verbalogix-companion-fgs"
        const val FGS_NOTIFICATION_ID = 0x76_62_78_01.toInt() // "vbx\x01"
    }
}
