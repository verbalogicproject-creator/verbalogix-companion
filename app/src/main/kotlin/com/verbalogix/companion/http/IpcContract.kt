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

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * HTTP + WebSocket contract between the engine (Python client) and the
 * companion app (Ktor CIO server on 127.0.0.1).
 *
 * All names match the engine-side JSON. Do not rename fields without also
 * updating `engine/screen_agent_companion.py`.
 */

// ── Ping ─────────────────────────────────────────────────────────────────

@Serializable
data class PingResponse(
    val ok: Boolean = true,
    val version: String,
    val apm: ApmState,
    val uptimeMs: Long,
    val accessibilityEnabled: Boolean,
)

@Serializable
enum class ApmState {
    @SerialName("unavailable") UNAVAILABLE,
    @SerialName("off")         OFF,
    @SerialName("on")          ON,
    @SerialName("unknown")     UNKNOWN,
}

// ── Tree ─────────────────────────────────────────────────────────────────

/**
 * AccessibilityNodeInfo tree as JSON. Mirrors the fields listed in the
 * brief's "Node JSON schema" section — bounds are absolute-screen coords,
 * nodeId is a content-stable hash.
 */
@Serializable
data class NodeDto(
    val nodeId: String,
    val className: String?        = null,
    val viewId: String?           = null,
    val text: String?             = null,
    val contentDesc: String?      = null,
    val bounds: BoundsDto?        = null,
    val clickable: Boolean        = false,
    val longClickable: Boolean    = false,
    val scrollable: Boolean       = false,
    val focusable: Boolean        = false,
    val focused: Boolean          = false,
    val enabled: Boolean          = true,
    val checkable: Boolean        = false,
    val checked: Boolean          = false,
    val editable: Boolean         = false,
    val packageName: String?      = null,
    val inputType: Int            = 0,
    val children: List<NodeDto>   = emptyList(),
    val structuredData: StructuredDataDto? = null,
)

@Serializable
data class BoundsDto(val l: Int, val t: Int, val r: Int, val b: Int)

/**
 * Optional semantic tags from Android 17 API 37 `AccessibilityNodeInfo.
 * StructuredDataInfo`. `null` on older devices or for nodes that don't
 * declare it.
 */
@Serializable
data class StructuredDataDto(
    val tag: String? = null,
    val attrs: Map<String, String> = emptyMap(),
)

@Serializable
data class TreeResponse(
    val rootPackage: String?,
    val windowId: Int,
    val capturedAtMs: Long,
    val node: NodeDto?,
)

// ── Actions ──────────────────────────────────────────────────────────────

@Serializable
data class TapRequest(
    val nodeId: String? = null,
    val x: Int? = null,
    val y: Int? = null,
)

@Serializable
data class SwipeRequest(
    val x1: Int, val y1: Int,
    val x2: Int, val y2: Int,
    val durationMs: Long = 200,
)

@Serializable
data class ActionRequest(
    val nodeId: String,
    val action: String,                        // e.g. "ACTION_CLICK", "ACTION_SCROLL_FORWARD"
    val args: Map<String, String> = emptyMap(),
)

@Serializable
data class TextRequest(
    val nodeId: String,
    val text: String,
)

@Serializable
enum class GlobalAction {
    @SerialName("HOME")          HOME,
    @SerialName("BACK")          BACK,
    @SerialName("RECENTS")       RECENTS,
    @SerialName("NOTIFICATIONS") NOTIFICATIONS,
    @SerialName("QUICK_SETTINGS") QUICK_SETTINGS,
    @SerialName("POWER_DIALOG") POWER_DIALOG,
    @SerialName("LOCK_SCREEN")   LOCK_SCREEN,
    @SerialName("TAKE_SCREENSHOT") TAKE_SCREENSHOT,
}

@Serializable
data class GlobalRequest(val action: GlobalAction)

@Serializable
data class ActionResponse(
    val ok: Boolean,
    val latencyMs: Long,
    val error: String? = null,
)

// ── Screenshot stub (v1) ─────────────────────────────────────────────────

@Serializable
data class ScreenshotStubResponse(
    val error: String = "vision_mode_not_enabled",
    val fallback: String = "use_mode_xml",
    val hint: String = "Screenshot via MediaProjection is planned for v2; the engine should use Mode.ADB with `adb exec-out screencap` until then.",
)

// ── Events (WebSocket) ───────────────────────────────────────────────────

@Serializable
sealed class EventDto {
    abstract val tMs: Long

    @Serializable
    @SerialName("window_state")
    data class WindowStateChanged(
        override val tMs: Long,
        val pkg: String?,
        val windowId: Int,
        val title: String?,
    ) : EventDto()

    @Serializable
    @SerialName("view_clicked")
    data class ViewClicked(
        override val tMs: Long,
        val pkg: String?,
        val nodeId: String?,
    ) : EventDto()

    @Serializable
    @SerialName("view_focused")
    data class ViewFocused(
        override val tMs: Long,
        val pkg: String?,
        val nodeId: String?,
    ) : EventDto()

    @Serializable
    @SerialName("text_changed")
    data class TextChanged(
        override val tMs: Long,
        val pkg: String?,
        val nodeId: String?,
        val text: String?,
    ) : EventDto()

    @Serializable
    @SerialName("window_content")
    data class WindowContentChanged(
        override val tMs: Long,
        val pkg: String?,
        val windowId: Int,
        val changeHash: String,
    ) : EventDto()

    @Serializable
    @SerialName("companion_state")
    data class CompanionState(
        override val tMs: Long,
        val apm: ApmState,
        val accessibilityEnabled: Boolean,
    ) : EventDto()
}

// ── Error envelope ───────────────────────────────────────────────────────

@Serializable
data class ErrorDto(
    val error: String,
    val message: String? = null,
    val detail: Map<String, String> = emptyMap(),
)
