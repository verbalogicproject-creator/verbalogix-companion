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

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.verbalogix.companion.accessibility.EngineAccessibilityService
import com.verbalogix.companion.apm.AdvancedProtectionDetector
import com.verbalogix.companion.http.ApmState
import com.verbalogix.companion.http.EngineHttpService
import com.verbalogix.companion.http.TokenStore
import kotlinx.coroutines.delay

/**
 * Minimal Compose UI.
 *
 * Scope (v1):
 *  - Show AccessibilityService state + deep-link to settings
 *  - Show server URL + bearer token with copy buttons
 *  - Show APM state banner if active (and suppress settings deep-link — it
 *    would just fail)
 *  - Regenerate token button
 *
 * Out of scope (v1):
 *  - Log viewer
 *  - Per-endpoint usage counters
 *  - Settings screen for port override
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CompanionApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompanionApp() {
    val ctx = LocalContext.current
    val tokenStore = remember { TokenStore(ctx) }
    val apm = remember { AdvancedProtectionDetector(ctx) }

    var token by remember { mutableStateOf(tokenStore.getOrCreate()) }
    var serviceEnabled by remember { mutableStateOf(false) }
    var apmState by remember { mutableStateOf(apm.current()) }
    var captureGranted by remember { mutableStateOf(false) }

    // Result launcher for the system screen-capture consent dialog. Only
    // this path is allowed to turn a resultCode/resultData pair into a
    // MediaProjection instance — that's Android's contract.
    val captureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val intent = Intent(ctx, com.verbalogix.companion.capture.ScreenCaptureService::class.java).apply {
                putExtra(
                    com.verbalogix.companion.capture.ScreenCaptureService.EXTRA_RESULT_CODE,
                    result.resultCode,
                )
                putExtra(
                    com.verbalogix.companion.capture.ScreenCaptureService.EXTRA_RESULT_DATA,
                    result.data,
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
            Toast.makeText(ctx,
                "Screen capture granted. Remember: Android revokes it when the app is killed.",
                Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(ctx, "Screen capture permission declined", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        // Cheap polling — the proper fix lives in AdvancedProtectionDetector
        // once compileSdk is bumped to 37 and we can register a callback.
        while (true) {
            serviceEnabled = EngineAccessibilityService.current() != null
            apmState = apm.current()
            captureGranted = com.verbalogix.companion.capture.ScreenCaptureService.current() != null
            delay(1_000)
        }
    }

    // Two-tab navigation: the Status tab hosts the existing operator
    // surface (service toggle, token, URL); the Engine tab embeds the
    // engine's web UI via WebView. Selection state is kept inside the
    // composable because there is no reason to persist it across app
    // kills — the user picks a tab per session.
    var selectedTab by remember { mutableStateOf(0) }

    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF00FF88))) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    Column {
                        TopAppBar(title = { Text("Verbalogix Companion") })
                        TabRow(selectedTabIndex = selectedTab) {
                            Tab(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                text = { Text("Status") },
                            )
                            Tab(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                text = { Text("Engine") },
                            )
                        }
                    }
                },
            ) { inner ->
                Box(modifier = Modifier.padding(inner).fillMaxSize()) {
                    when (selectedTab) {
                        0 -> StatusTab(
                            ctx = ctx,
                            token = token,
                            onTokenRegenerate = {
                                token = tokenStore.regenerate()
                                Toast.makeText(ctx, "New token generated — update the engine config", Toast.LENGTH_SHORT).show()
                            },
                            serviceEnabled = serviceEnabled,
                            apmState = apmState,
                            captureGranted = captureGranted,
                            onRequestCapture = {
                                val mpm = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                                        as MediaProjectionManager
                                captureLauncher.launch(mpm.createScreenCaptureIntent())
                            },
                            onRevokeCapture = {
                                com.verbalogix.companion.capture.ScreenCaptureService.stop(ctx)
                            },
                        )
                        1 -> EngineWebView(engineUrl = "http://127.0.0.1:8000")
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusTab(
    ctx: Context,
    token: String,
    onTokenRegenerate: () -> Unit,
    serviceEnabled: Boolean,
    apmState: ApmState,
    captureGranted: Boolean,
    onRequestCapture: () -> Unit,
    onRevokeCapture: () -> Unit,
) {
    Column(
        modifier = Modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        ApmBanner(state = apmState)
        Spacer(Modifier.height(12.dp))

        StatusCard(
            serviceEnabled = serviceEnabled,
            serverPort = EngineHttpService.DEFAULT_PORT,
            apmBlocked = apmState == ApmState.ON,
            onOpenSettings = {
                ctx.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK,
                    ),
                )
            },
        )

        Spacer(Modifier.height(12.dp))

        CaptureCard(
            granted = captureGranted,
            onGrant = onRequestCapture,
            onRevoke = onRevokeCapture,
        )

        Spacer(Modifier.height(12.dp))

        TokenCard(
            token = token,
            onCopy = { copyToClipboard(ctx, "bearer-token", token) },
            onRegenerate = onTokenRegenerate,
        )

        Spacer(Modifier.height(12.dp))

        UrlCard(
            port = EngineHttpService.DEFAULT_PORT,
            onCopy = { url -> copyToClipboard(ctx, "server-url", url) },
        )

        Spacer(Modifier.height(24.dp))

        Text(
            text = "Both values go into the engine as env vars: " +
                    "COMPANION_URL and COMPANION_BEARER. See " +
                    "ANDROID-COMPANION-APP-BRIEF.md in the engine repo.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CaptureCard(granted: Boolean, onGrant: () -> Unit, onRevoke: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.capture_card_header),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (granted) stringResource(R.string.capture_state_active)
                       else stringResource(R.string.capture_state_inactive),
                style = MaterialTheme.typography.bodyMedium,
                color = if (granted) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!granted) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.capture_state_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            if (granted) {
                OutlinedButton(onClick = onRevoke, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.btn_revoke_capture))
                }
            } else {
                Button(onClick = onGrant, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.btn_grant_capture))
                }
            }
        }
    }
}

@Composable
private fun ApmBanner(state: ApmState) {
    if (state != ApmState.ON) return
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Advanced Protection Mode is active. The companion " +
                    "service cannot run while APM is on. The engine will fall " +
                    "back to its ADB Screen Agent automatically.",
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun StatusCard(
    serviceEnabled: Boolean,
    serverPort: Int,
    apmBlocked: Boolean,
    onOpenSettings: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = if (serviceEnabled) "Accessibility service: enabled"
                       else "Accessibility service: NOT enabled",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (serviceEnabled) "Local server: 127.0.0.1:$serverPort"
                       else "Local server: stopped (needs service)",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!serviceEnabled && !apmBlocked) {
                Spacer(Modifier.height(12.dp))
                Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                    Text("Open Accessibility Settings")
                }
            }
        }
    }
}

@Composable
private fun TokenCard(token: String, onCopy: () -> Unit, onRegenerate: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Bearer token",
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = token,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onCopy, modifier = Modifier.fillMaxWidth()) {
                Text("Copy token")
            }
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = onRegenerate, modifier = Modifier.fillMaxWidth()) {
                Text("Regenerate token")
            }
        }
    }
}

@Composable
private fun UrlCard(port: Int, onCopy: (String) -> Unit) {
    val url = "http://127.0.0.1:$port"
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = "Server URL", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                text = url,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { onCopy(url) }, modifier = Modifier.fillMaxWidth()) {
                Text("Copy URL")
            }
        }
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}
