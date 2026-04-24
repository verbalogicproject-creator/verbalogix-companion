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

import android.content.Context
import android.util.Log
import com.verbalogix.companion.BuildConfig
import com.verbalogix.companion.MainApplication
import com.verbalogix.companion.accessibility.EngineAccessibilityService
import com.verbalogix.companion.accessibility.NodeSerializer
import com.verbalogix.companion.apm.AdvancedProtectionDetector
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.send
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

/**
 * Embedded Ktor CIO server bound to `127.0.0.1`.
 *
 * All routes (except /ping) require a Bearer token matching
 * [TokenStore.validate]. The server lives inside [EngineHttpService] so its
 * lifecycle is tied to the foreground service notification.
 *
 * Routes (locked by the brief's "API endpoint contract"):
 *
 *   GET  /ping                  — engine health probe, no a11y work
 *   GET  /tree                  — full AccessibilityNodeInfo tree
 *   GET  /tree?since={ts}       — differential update (stub for v1.1)
 *   POST /tap                   — { nodeId } preferred, { x, y } fallback
 *   POST /action                — generic performAction on node
 *   POST /swipe                 — coord-based gesture
 *   POST /text                  — ACTION_SET_TEXT
 *   POST /global                — performGlobalAction
 *   GET  /events    (WS)        — throttled event stream
 *   GET  /events/sse            — browser-debug fallback
 *   GET  /screenshot            — v1 stub: returns use_mode_xml error
 */
class LocalHttpServer(
    private val context: Context,
    private val tokenStore: TokenStore,
    private val port: Int,
) {

    private var engine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val startedAt = System.currentTimeMillis()
    private val apm = AdvancedProtectionDetector(context)

    private val jsonCodec = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    fun start() {
        if (engine != null) return
        Log.i(TAG, "starting Ktor CIO on 127.0.0.1:$port")
        engine = embeddedServer(
            factory = CIO,
            port = port,
            host = LOOPBACK,
        ) {
            install(ContentNegotiation) { json(jsonCodec) }
            install(WebSockets) { pingPeriod = 15.seconds }
            install(StatusPages) {
                exception<Throwable> { call, cause ->
                    Log.e(TAG, "unhandled route error", cause)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorDto(error = "internal", message = cause.message),
                    )
                }
            }
            routing {
                // ── Unauthenticated liveness ─────────────────────────────
                get("/ping") {
                    call.respond(
                        PingResponse(
                            version = BuildConfig.VERSION_NAME,
                            apm = apm.current(),
                            uptimeMs = System.currentTimeMillis() - startedAt,
                            accessibilityEnabled = EngineAccessibilityService.current() != null,
                        )
                    )
                }

                // ── Gated routes — every handler runs requireAuth() first
                get("/tree") {
                    if (!call.requireAuth()) return@get
                    val svc = EngineAccessibilityService.current()
                    if (svc == null) {
                        call.respond(HttpStatusCode.ServiceUnavailable,
                            ErrorDto("a11y_disabled",
                                "Accessibility service is not enabled on this device."))
                        return@get
                    }
                    val root = svc.captureRoot()
                    call.respond(
                        TreeResponse(
                            rootPackage = root?.packageName?.toString(),
                            windowId = root?.windowId ?: -1,
                            capturedAtMs = System.currentTimeMillis(),
                            node = NodeSerializer.toDto(root),
                        )
                    )
                }

                post("/tap") {
                    if (!call.requireAuth()) return@post
                    val req = call.receive<TapRequest>()
                    val svc = requireService(call) ?: return@post
                    val start = System.currentTimeMillis()
                    val ok = when {
                        req.nodeId != null -> {
                            val node = svc.findNodeById(req.nodeId)
                            if (node != null) svc.gestures.clickNode(node) else false
                        }
                        req.x != null && req.y != null ->
                            svc.gestures.tapAt(req.x.toFloat(), req.y.toFloat())
                        else -> {
                            call.respond(HttpStatusCode.BadRequest,
                                ErrorDto("bad_request", "Either nodeId or (x,y) is required"))
                            return@post
                        }
                    }
                    svc.markGestureActivity()
                    call.respond(ActionResponse(ok = ok, latencyMs = System.currentTimeMillis() - start))
                }

                post("/action") {
                    if (!call.requireAuth()) return@post
                    val req = call.receive<ActionRequest>()
                    val svc = requireService(call) ?: return@post
                    val node = svc.findNodeById(req.nodeId)
                    if (node == null) {
                        call.respond(HttpStatusCode.NotFound,
                            ErrorDto("node_not_found", "nodeId ${req.nodeId} not in current tree"))
                        return@post
                    }
                    val actionId = mapActionName(req.action)
                    val ok = if (actionId != null) {
                        node.performAction(actionId)
                    } else {
                        call.respond(HttpStatusCode.BadRequest,
                            ErrorDto("unknown_action", req.action))
                        return@post
                    }
                    svc.markGestureActivity()
                    call.respond(ActionResponse(ok = ok, latencyMs = 0))
                }

                post("/swipe") {
                    if (!call.requireAuth()) return@post
                    val req = call.receive<SwipeRequest>()
                    val svc = requireService(call) ?: return@post
                    val t0 = System.currentTimeMillis()
                    val ok = svc.gestures.swipe(
                        req.x1.toFloat(), req.y1.toFloat(),
                        req.x2.toFloat(), req.y2.toFloat(),
                        req.durationMs,
                    )
                    svc.markGestureActivity()
                    call.respond(ActionResponse(ok = ok, latencyMs = System.currentTimeMillis() - t0))
                }

                post("/text") {
                    if (!call.requireAuth()) return@post
                    val req = call.receive<TextRequest>()
                    val svc = requireService(call) ?: return@post
                    val node = svc.findNodeById(req.nodeId)
                    if (node == null) {
                        call.respond(HttpStatusCode.NotFound,
                            ErrorDto("node_not_found", "nodeId ${req.nodeId} not in current tree"))
                        return@post
                    }
                    val ok = svc.gestures.setText(node, req.text)
                    svc.markGestureActivity()
                    call.respond(ActionResponse(ok = ok, latencyMs = 0))
                }

                post("/global") {
                    if (!call.requireAuth()) return@post
                    val req = call.receive<GlobalRequest>()
                    val svc = requireService(call) ?: return@post
                    val globalId = mapGlobalAction(req.action)
                    val ok = svc.performGlobalAction(globalId)
                    svc.markGestureActivity()
                    call.respond(ActionResponse(ok = ok, latencyMs = 0))
                }

                get("/screenshot") {
                    if (!call.requireAuth()) return@get
                    call.respond(HttpStatusCode.NotImplemented, ScreenshotStubResponse())
                }

                webSocket("/events") {
                    val token = call.request.queryParameters["token"]
                        ?: call.request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()
                    if (token == null || !tokenStore.validate(token)) {
                        close(io.ktor.websocket.CloseReason(
                            io.ktor.websocket.CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
                        return@webSocket
                    }
                    val svc = EngineAccessibilityService.current() ?: run {
                        send(Frame.Text(jsonCodec.encodeToString(
                            ErrorDto.serializer(),
                            ErrorDto("a11y_disabled", "enable the service in Settings"))))
                        close()
                        return@webSocket
                    }
                    launch {
                        svc.eventStream.events.collect { evt ->
                            send(Frame.Text(jsonCodec.encodeToString(EventDto.serializer(), evt)))
                        }
                    }
                }
            }
        }.also { it.start(wait = false) }
    }

    fun stop() {
        Log.i(TAG, "stopping Ktor")
        engine?.stop(gracePeriodMillis = 1_000, timeoutMillis = 3_000)
        engine = null
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private suspend fun ApplicationCall.requireAuth(): Boolean {
        val header = request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()
        if (header == null || !tokenStore.validate(header)) {
            respond(HttpStatusCode.Unauthorized,
                ErrorDto("unauthorized", "valid Bearer token required"))
            return false
        }
        return true
    }

    private suspend fun requireService(call: ApplicationCall): EngineAccessibilityService? {
        val svc = EngineAccessibilityService.current()
        if (svc == null) {
            call.respond(HttpStatusCode.ServiceUnavailable,
                ErrorDto("a11y_disabled", "Accessibility service is not enabled"))
        }
        return svc
    }

    private fun mapActionName(name: String): Int? = when (name.uppercase()) {
        "ACTION_CLICK" -> android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK
        "ACTION_LONG_CLICK" -> android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK
        "ACTION_SCROLL_FORWARD" -> android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        "ACTION_SCROLL_BACKWARD" -> android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        "ACTION_FOCUS" -> android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS
        "ACTION_CLEAR_FOCUS" -> android.view.accessibility.AccessibilityNodeInfo.ACTION_CLEAR_FOCUS
        "ACTION_SELECT" -> android.view.accessibility.AccessibilityNodeInfo.ACTION_SELECT
        "ACTION_COLLAPSE" -> android.view.accessibility.AccessibilityNodeInfo.ACTION_COLLAPSE
        "ACTION_EXPAND" -> android.view.accessibility.AccessibilityNodeInfo.ACTION_EXPAND
        else -> null
    }

    private fun mapGlobalAction(action: GlobalAction): Int = when (action) {
        GlobalAction.HOME -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
        GlobalAction.BACK -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
        GlobalAction.RECENTS -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
        GlobalAction.NOTIFICATIONS -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
        GlobalAction.QUICK_SETTINGS -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
        GlobalAction.POWER_DIALOG -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
        GlobalAction.LOCK_SCREEN -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
        GlobalAction.TAKE_SCREENSHOT -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
    }

    companion object {
        private const val TAG = "VbxKtor"
        private const val LOOPBACK = "127.0.0.1"
    }
}
