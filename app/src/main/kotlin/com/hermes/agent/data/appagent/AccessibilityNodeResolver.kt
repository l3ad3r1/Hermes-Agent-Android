package com.hermes.agent.data.appagent

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/** Resolves a cached element against the current tree without regenerating tags. */
object AccessibilityNodeResolver {
    fun find(root: AccessibilityNodeInfo?, target: UiNode): AccessibilityNodeInfo? {
        if (root == null) return null

        var boundsFallback: AccessibilityNodeInfo? = null

        fun traverse(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            val bounds = Rect().also(node::getBoundsInScreen)
            val sameBounds = bounds == target.bounds
            val sameClass = target.className.isEmpty() ||
                node.className?.toString().orEmpty() == target.className
            val nodeResourceId = runCatching { node.viewIdResourceName.orEmpty() }
                .getOrDefault("")
            val sameResourceId = target.resourceId.isNotEmpty() &&
                nodeResourceId == target.resourceId

            if (sameBounds && sameClass && sameResourceId) return node
            if (sameBounds && sameClass && boundsFallback == null) boundsFallback = node

            for (index in 0 until node.childCount) {
                traverse(node.getChild(index))?.let { return it }
            }
            return null
        }

        return traverse(root) ?: boundsFallback
    }
}
