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

import android.view.accessibility.AccessibilityEvent
import com.verbalogix.companion.http.EventDto
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/**
 * Throttled AccessibilityEvent pipeline.
 *
 * The brief locks in a 3-tier strategy. This class owns Tiers 2 and 3;
 * Tier 1 (OS-level event-type whitelist) lives in
 * `accessibility_service_config.xml`.
 *
 *  Tier 1 (manifest)      → drops ~70 % of events before they reach us
 *  Tier 2 (coroutine)     → 80 ms debounce + `conflate()` for bursts
 *  Tier 3 (content hash)  → drops `typeWindowContentChanged` duplicates
 *                            inside a 200 ms window
 *
 * Downstream consumers collect from [events]. The outbound WebSocket wiring
 * in `LocalHttpServer` subscribes once and fans out to all active clients.
 */
@OptIn(FlowPreview::class)
class EventStream {

    private val _raw = MutableSharedFlow<EventDto>(
        replay = 0,
        extraBufferCapacity = 128,
    )

    /** Debounced + deduplicated event flow for external consumers. */
    val events: Flow<EventDto> =
        _raw
            .debounce(DEBOUNCE_MS)
            .distinctUntilChanged { old, new -> sameContentBurst(old, new) }
            .conflate()
            .asDropSafe()

    /**
     * Map an AccessibilityEvent onto our DTO and emit. Returning synchronously
     * so the AccessibilityService's `onAccessibilityEvent` stays fast.
     */
    fun submit(event: AccessibilityEvent) {
        val dto = map(event) ?: return
        _raw.tryEmit(dto)
    }

    /** Emit a companion-state message (not an AccessibilityEvent). */
    fun submitCompanionState(state: EventDto.CompanionState) {
        _raw.tryEmit(state)
    }

    private fun map(event: AccessibilityEvent): EventDto? {
        val pkg = event.packageName?.toString()
        val now = event.eventTime.takeIf { it > 0 } ?: System.currentTimeMillis()

        return when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                EventDto.WindowStateChanged(
                    tMs = now,
                    pkg = pkg,
                    windowId = event.windowId,
                    title = event.text?.firstOrNull()?.toString(),
                )
            AccessibilityEvent.TYPE_VIEW_CLICKED ->
                EventDto.ViewClicked(
                    tMs = now, pkg = pkg, nodeId = null,
                )
            AccessibilityEvent.TYPE_VIEW_FOCUSED ->
                EventDto.ViewFocused(
                    tMs = now, pkg = pkg, nodeId = null,
                )
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ->
                EventDto.TextChanged(
                    tMs = now,
                    pkg = pkg,
                    nodeId = null,
                    text = event.text?.joinToString(" ")?.take(TEXT_SNIPPET_MAX),
                )
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ->
                EventDto.WindowContentChanged(
                    tMs = now,
                    pkg = pkg,
                    windowId = event.windowId,
                    changeHash = windowContentHash(event),
                )
            else -> null
        }
    }

    /**
     * Tier 3 dedup — return true if [new] is a duplicate of [old] in a way
     * that only affects the high-churn event types (scroll/content-changed).
     */
    private fun sameContentBurst(old: EventDto, new: EventDto): Boolean {
        if (old !is EventDto.WindowContentChanged || new !is EventDto.WindowContentChanged) {
            return false
        }
        val sameSurface = old.pkg == new.pkg && old.windowId == new.windowId
        val sameHash = old.changeHash == new.changeHash
        val withinWindow = (new.tMs - old.tMs) < CONTENT_BURST_WINDOW_MS
        return sameSurface && sameHash && withinWindow
    }

    private fun windowContentHash(event: AccessibilityEvent): String {
        // Cheap hash — we don't want to traverse the full tree here. `pkg +
        // windowId + source-class-name + text` is enough to dedupe animated
        // spinners + loading shimmers without holding structural locks.
        val src = event.source
        val key = buildString(64) {
            append(event.packageName).append('|')
            append(event.windowId).append('|')
            append(src?.className).append('|')
            append(event.text?.firstOrNull())
        }
        return Integer.toHexString(key.hashCode())
    }

    /**
     * Small shim — `SharedFlow.asSharedFlow()` returns a read-only view; this
     * alias exists purely so the call site reads in the right direction.
     */
    private fun <T> MutableSharedFlow<T>.asDropSafe(): Flow<T> =
        asSharedFlow().filter { true }

    companion object {
        private const val DEBOUNCE_MS = 80L
        private const val CONTENT_BURST_WINDOW_MS = 200L
        private const val TEXT_SNIPPET_MAX = 400
    }
}
