/*
 * Copyright 2026 Eyal Nof (Verbalogix)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.verbalogix.companion.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Thin wrappers over dispatchGesture + performAction.
 *
 * Architectural rule (from the brief): **prefer node-targeted
 * performAction over coordinate-based dispatchGesture**.
 *
 *  - `performAction(ACTION_CLICK)` routes through the Android action
 *    framework. ~2 ms. No coordinate translation. Not susceptible to the
 *    Android 15 QPR2 "gesture path goes idle" regression. Works reliably
 *    on OEMs where dispatchGesture is flaky (MIUI, older One UI).
 *
 *  - `dispatchGesture` is the fallback for non-accessible elements:
 *    canvases, games, WebViews without a11y tree, custom-drawn surfaces.
 *    ~10 ms plus OEM variability.
 *
 *  - Both paths return a uniform success/failure; the engine treats them
 *    interchangeably once the response lands.
 */
class GestureDispatcher(private val service: AccessibilityService) {

    /**
     * Click the given node via the accessibility action framework.
     * Returns true if the framework accepted the action.
     */
    fun clickNode(node: AccessibilityNodeInfo): Boolean =
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)

    /** Long-press a node. */
    fun longClickNode(node: AccessibilityNodeInfo): Boolean =
        node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)

    /** Scroll the nearest scrollable ancestor forward. */
    fun scrollNode(node: AccessibilityNodeInfo, forward: Boolean): Boolean =
        node.performAction(
            if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
        )

    /** Set text on an editable node — replaces current content. */
    fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text,
            )
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /**
     * Coordinate-based single tap via dispatchGesture. Fallback for surfaces
     * without a usable accessibility tree. Suspends until the framework
     * reports completion or the gesture is cancelled.
     */
    suspend fun tapAt(x: Float, y: Float): Boolean =
        dispatchGestureAsync {
            val path = Path().also { it.moveTo(x, y) }
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MS))
                .build()
        }

    /** Coordinate-based swipe. */
    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean =
        dispatchGestureAsync {
            val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
                .build()
        }

    /**
     * Send a synthetic "keep-warm" tap outside any UI element. Mitigates the
     * Android 15 QPR2 Beta regression where the gesture dispatch path
     * silently goes idle after extended runtimes. Called by a watchdog in
     * the service; safe to call from anywhere.
     */
    suspend fun keepAliveTap(): Boolean =
        // (-1, -1) is outside any rendered element on every Android build
        // seen so far — counts as a gesture-path warm-up without producing
        // any visible user impact.
        dispatchGestureAsync {
            val path = Path().also { it.moveTo(-1f, -1f) }
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 1L))
                .build()
        }

    private suspend fun dispatchGestureAsync(
        build: () -> GestureDescription,
    ): Boolean = suspendCancellableCoroutine { cont ->
        val cb = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gesture: GestureDescription?) {
                if (cont.isActive) cont.resume(true)
            }
            override fun onCancelled(gesture: GestureDescription?) {
                if (cont.isActive) cont.resume(false)
            }
        }
        val accepted = try {
            service.dispatchGesture(build(), cb, /* handler = */ null)
        } catch (t: Throwable) {
            false
        }
        if (!accepted && cont.isActive) cont.resume(false)
    }

    companion object {
        private const val TAP_DURATION_MS: Long = 50
    }
}
