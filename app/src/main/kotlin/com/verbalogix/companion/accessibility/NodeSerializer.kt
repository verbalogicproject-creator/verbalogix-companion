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

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.verbalogix.companion.http.BoundsDto
import com.verbalogix.companion.http.NodeDto
import java.security.MessageDigest

/**
 * Serialize an AccessibilityNodeInfo tree to our NodeDto graph.
 *
 * Key invariants (from the locked decisions in the brief):
 *
 *  - `nodeId = hash(viewIdResName + className + bounds + text)`. Stable
 *    across re-queries as long as the element doesn't move or change text.
 *    This is what lets the engine do `GET /tree` then `POST /tap {nodeId}`
 *    without the ID invalidating between calls.
 *
 *  - Bounds are `getBoundsInScreen()` (absolute screen coordinates). Absolute
 *    coords are what `dispatchGesture` expects — using `getBoundsInParent()`
 *    would shift taps on scrolled content.
 *
 *  - Recursion is bounded by the OS-imposed tree depth, which is usually
 *    <30. We do not add our own cap; if a malicious app produces a
 *    pathological tree, we let the OS's AccessibilityManager throttle.
 */
object NodeSerializer {

    /**
     * Serialize [root] and its descendants. Returns null if [root] is null.
     *
     * Safe to call from any thread — does not modify the node; only reads.
     */
    fun toDto(root: AccessibilityNodeInfo?): NodeDto? {
        root ?: return null
        val index = NodeIdIndex()
        return serialize(root, index)
    }

    private fun serialize(node: AccessibilityNodeInfo, ids: NodeIdIndex): NodeDto {
        val bounds = Rect().also { node.getBoundsInScreen(it) }.toDto()
        val className = node.className?.toString()
        val viewId = node.viewIdResourceName
        val text = node.text?.toString()
        val contentDesc = node.contentDescription?.toString()

        val nodeId = ids.makeStable(
            viewId = viewId,
            className = className,
            bounds = bounds,
            text = text,
        )

        val children = buildList(node.childCount) {
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try {
                    add(serialize(child, ids))
                } finally {
                    // AccessibilityNodeInfo objects are pooled by the framework.
                    // On older APIs you must recycle children after use; on API
                    // 33+ it's a no-op but safe to call.
                    @Suppress("DEPRECATION")
                    child.recycle()
                }
            }
        }

        return NodeDto(
            nodeId = nodeId,
            className = className,
            viewId = viewId,
            text = text,
            contentDesc = contentDesc,
            bounds = bounds,
            clickable = node.isClickable,
            longClickable = node.isLongClickable,
            scrollable = node.isScrollable,
            focusable = node.isFocusable,
            focused = node.isFocused,
            enabled = node.isEnabled,
            checkable = node.isCheckable,
            checked = node.isChecked,
            editable = node.isEditable,
            packageName = node.packageName?.toString(),
            inputType = node.inputType,
            children = children,
            // structuredData is null until compileSdk bump to 37 exposes
            // AccessibilityNodeInfo.StructuredDataInfo properly.
            structuredData = null,
        )
    }

    private fun Rect.toDto() = BoundsDto(l = left, t = top, r = right, b = bottom)
}

/**
 * Handles nodeId collisions inside a single tree traversal.
 *
 * Two elements can hash identically if they share `viewIdResName`, class,
 * bounds, and text — the most common case is two invisible copies of a
 * label during a transition. The index appends a disambiguating suffix
 * only when a duplicate shows up, so non-colliding IDs remain stable
 * across re-queries (which is the whole point).
 */
private class NodeIdIndex {
    private val seen = HashMap<String, Int>()

    fun makeStable(
        viewId: String?,
        className: String?,
        bounds: BoundsDto,
        text: String?,
    ): String {
        val base = hash(viewId, className, bounds, text)
        val count = seen.getOrDefault(base, 0)
        seen[base] = count + 1
        return if (count == 0) base else "$base~$count"
    }

    private fun hash(
        viewId: String?,
        className: String?,
        bounds: BoundsDto,
        text: String?,
    ): String {
        val raw = buildString(128) {
            append(viewId.orEmpty()); append('|')
            append(className.orEmpty()); append('|')
            append(bounds.l); append(','); append(bounds.t); append(',')
            append(bounds.r); append(','); append(bounds.b); append('|')
            append(text.orEmpty())
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        // First 8 bytes as hex — 64 bits of content identity is plenty at
        // tree-level collision rates. Short enough to grep in the engine
        // logs; long enough to virtually never collide by accident.
        return digest.copyOf(8).joinToString("") { "%02x".format(it) }
    }
}
