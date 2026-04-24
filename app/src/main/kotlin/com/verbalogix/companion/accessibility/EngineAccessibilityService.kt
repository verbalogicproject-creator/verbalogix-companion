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
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.verbalogix.companion.MainApplication
import com.verbalogix.companion.http.EngineHttpService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * The AccessibilityService that feeds the Screen Agent.
 *
 * Responsibilities:
 *  - Forward incoming AccessibilityEvents to [EventStream].
 *  - Provide the HTTP handlers with a reference back to us so they can
 *    read the root node, dispatch gestures, and perform actions.
 *  - Kick off the foreground HTTP service when connected; shut it down
 *    when the OS revokes the service.
 *  - Periodic "keep-warm tap" to work around the Android 15 QPR2 regression
 *    where dispatchGesture silently stops working after ~2 h idle.
 *
 * Lives in the companion app process — the HTTP server and the
 * AccessibilityService share a process, which is why the static holder
 * in the companion object works. Do not reach for it from other processes.
 */
class EngineAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val eventStream = EventStream()
    val gestures by lazy { GestureDispatcher(this) }

    private var watchdogJob: Job? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "EngineAccessibilityService connected")
        currentRef.set(this)

        // Hand off foreground ownership to the HTTP service. It keeps the
        // Ktor server + notification alive; this service just handles
        // events and gestures.
        EngineHttpService.startForeground(this)

        watchdogJob = serviceScope.launch { gestureKeepAliveLoop() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        eventStream.submit(event)
    }

    override fun onInterrupt() {
        Log.w(TAG, "onInterrupt — service was told to abandon in-flight work")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        Log.i(TAG, "EngineAccessibilityService unbinding")
        watchdogJob?.cancel()
        currentRef.compareAndSet(this, null)
        serviceScope.cancel()
        EngineHttpService.stopForeground(this)
        return super.onUnbind(intent)
    }

    /** Root of the currently-active window, or null. Caller owns recycling. */
    fun captureRoot(): AccessibilityNodeInfo? = rootInActiveWindow

    /**
     * Convenience — find a node by its content-stable ID by traversing the
     * tree. Linear in node count; acceptable for Screen Agent's low call
     * rate. Returns null if the node has since gone away.
     */
    fun findNodeById(nodeId: String): AccessibilityNodeInfo? {
        val root = captureRoot() ?: return null
        return findInTree(root, nodeId)
    }

    private fun findInTree(root: AccessibilityNodeInfo, targetId: String): AccessibilityNodeInfo? {
        // Re-use the same hashing as the serializer so IDs match.
        val dto = NodeSerializer.toDto(root) ?: return null
        val path = findPath(dto, targetId) ?: return null
        return traverse(root, path)
    }

    private fun findPath(node: com.verbalogix.companion.http.NodeDto, id: String): List<Int>? {
        if (node.nodeId == id) return emptyList()
        node.children.forEachIndexed { idx, child ->
            val sub = findPath(child, id) ?: return@forEachIndexed
            return listOf(idx) + sub
        }
        return null
    }

    private fun traverse(root: AccessibilityNodeInfo, path: List<Int>): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = root
        for (i in path) {
            cur = cur?.getChild(i) ?: return null
        }
        return cur
    }

    /**
     * Watchdog for the Android 15 QPR2 touch-simulation regression. If
     * nothing has dispatched a real gesture for [KEEP_ALIVE_MS], send a
     * throwaway tap to keep the dispatch path warm. Real dispatches reset
     * the idle timer via [markGestureActivity].
     */
    private suspend fun gestureKeepAliveLoop() {
        while (true) {
            delay(KEEP_ALIVE_MS)
            val idleFor = System.currentTimeMillis() - lastGestureAt
            if (idleFor >= KEEP_ALIVE_MS) {
                runCatching { gestures.keepAliveTap() }
                    .onFailure { Log.w(TAG, "keep-alive tap failed: ${it.message}") }
                lastGestureAt = System.currentTimeMillis()
            }
        }
    }

    @Volatile private var lastGestureAt: Long = System.currentTimeMillis()
    fun markGestureActivity() { lastGestureAt = System.currentTimeMillis() }

    companion object {
        private const val TAG = "VbxA11yService"
        private const val KEEP_ALIVE_MS: Long = 5 * 60 * 1000    // 5 minutes idle → synthetic tap

        // The HTTP server fetches this at request time. Null if the user
        // has not enabled the service yet, or the OS has revoked it.
        private val currentRef = AtomicReference<EngineAccessibilityService?>()

        fun current(): EngineAccessibilityService? = currentRef.get()
    }
}
