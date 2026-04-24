/*
 * Copyright 2026 Eyal Nof (Verbalogix)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.verbalogix.companion.apm

import android.content.Context
import android.os.Build
import android.util.Log
import com.verbalogix.companion.MainApplication
import com.verbalogix.companion.http.ApmState
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Advanced Protection Mode detector.
 *
 * Android 17 (API 37+) introduces `AdvancedProtectionManager` which blocks
 * non-accessibility-tool apps from holding the BIND_ACCESSIBILITY_SERVICE
 * permission when APM is on.
 *
 * This class wraps the API reflectively so the module still compiles + runs
 * against `compileSdk = 34`. Once compileSdk is bumped to 37+, this can be
 * rewritten to use the class references directly. The reflection path is
 * forward-compatible either way.
 *
 * On pre-API-37 devices, [current] returns `UNAVAILABLE` and [observe] emits
 * that value once then completes — APM doesn't exist, nothing to detect.
 */
class AdvancedProtectionDetector(private val context: Context) {

    /** One-shot current state. Cheap; call anywhere. */
    fun current(): ApmState {
        if (Build.VERSION.SDK_INT < API_ADVANCED_PROTECTION_MANAGER) {
            return ApmState.UNAVAILABLE
        }
        return try {
            val mgr = context.getSystemService(CLASS_APM) ?: return ApmState.UNKNOWN
            val enabled = mgr.javaClass
                .getMethod("isAdvancedProtectionEnabled")
                .invoke(mgr) as? Boolean
                ?: return ApmState.UNKNOWN
            if (enabled) ApmState.ON else ApmState.OFF
        } catch (t: Throwable) {
            Log.w(MainApplication.TAG, "APM state probe failed: ${t.message}")
            ApmState.UNKNOWN
        }
    }

    /**
     * Flow of state transitions. Emits the current state immediately, then one
     * element per `onAdvancedProtectionChanged` callback. Collector's scope
     * controls lifetime via [awaitClose].
     */
    fun observe(): Flow<ApmState> = callbackFlow {
        val initial = current()
        trySend(initial)

        if (Build.VERSION.SDK_INT < API_ADVANCED_PROTECTION_MANAGER) {
            // Nothing to observe on older Android; close gracefully.
            close()
            return@callbackFlow
        }

        // TODO(post-compileSdk-37): replace reflection with direct API call:
        //   val apm = context.getSystemService(AdvancedProtectionManager::class.java)
        //   val cb = Consumer<Boolean> { trySend(if (it) ON else OFF) }
        //   apm.registerAdvancedProtectionCallback(mainExecutor, cb)
        //   awaitClose { apm.unregisterAdvancedProtectionCallback(cb) }
        //
        // For now, we emit the initial value and keep the channel open so the
        // consumer's scope decides when to close. Real push notifications land
        // when we bump compileSdk.
        awaitClose { /* no-op until compileSdk bump */ }
    }

    companion object {
        private const val CLASS_APM = "advanced_protection"
        private const val API_ADVANCED_PROTECTION_MANAGER = 37
    }
}
