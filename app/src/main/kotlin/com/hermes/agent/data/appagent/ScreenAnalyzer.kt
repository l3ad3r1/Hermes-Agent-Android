package com.hermes.agent.data.appagent

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

data class UiNode(
    val tag: Int,
    val bounds: Rect,
    val description: String,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isScrollable: Boolean,
    val resourceId: String = "",
    val className: String = "",
)

object ScreenAnalyzer {
    fun analyze(rootNode: AccessibilityNodeInfo?, screenshot: Bitmap?): AnalysisResult {
        val nodes = mutableListOf<UiNode>()
        var tagCounter = 1

        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null) return
            
            val isActionable = node.isClickable || node.isEditable || node.isScrollable || node.isCheckable
            if (isActionable && node.isVisibleToUser) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                
                // Only consider nodes with valid bounds
                if (bounds.width() > 0 && bounds.height() > 0) {
                    val desc = buildString {
                        if (!node.contentDescription.isNullOrEmpty()) append(node.contentDescription)
                        if (!node.text.isNullOrEmpty()) {
                            if (isNotEmpty()) append(" - ")
                            append(node.text)
                        }
                        if (isEmpty() && !node.className.isNullOrEmpty()) {
                            append(node.className.split(".").lastOrNull() ?: "")
                        }
                    }

                    nodes.add(
                        UiNode(
                            tag = tagCounter++,
                            bounds = bounds,
                            description = desc,
                            isClickable = node.isClickable || node.isCheckable,
                            isEditable = node.isEditable,
                            isScrollable = node.isScrollable,
                            resourceId = runCatching { node.viewIdResourceName.orEmpty() }
                                .getOrDefault(""),
                            className = node.className?.toString().orEmpty(),
                        )
                    )
                }
            }

            for (i in 0 until node.childCount) {
                traverse(node.getChild(i))
            }
        }

        traverse(rootNode)

        var annotatedBitmap = screenshot
        if (screenshot != null && nodes.isNotEmpty()) {
            annotatedBitmap = drawTagsOnScreenshot(screenshot, nodes)
        }

        return AnalysisResult(nodes, annotatedBitmap)
    }

    private fun drawTagsOnScreenshot(bitmap: Bitmap, nodes: List<UiNode>): Bitmap {
        val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutableBitmap)
        
        val bgPaint = Paint().apply {
            color = Color.parseColor("#80000000") // Semi-transparent black
            style = Paint.Style.FILL
        }
        val borderPaint = Paint().apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 32f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }

        for (node in nodes) {
            val bounds = node.bounds
            // Draw red border
            canvas.drawRect(bounds, borderPaint)
            
            // Draw tag in top-left corner
            val text = node.tag.toString()
            val textBounds = Rect()
            textPaint.getTextBounds(text, 0, text.length, textBounds)
            
            val tagBgRect = Rect(
                bounds.left,
                bounds.top,
                bounds.left + textBounds.width() + 16,
                bounds.top + textBounds.height() + 16
            )
            canvas.drawRect(tagBgRect, bgPaint)
            
            val x = bounds.left + 8f + textBounds.width() / 2f
            val y = bounds.top + 8f + textBounds.height()
            canvas.drawText(text, x, y, textPaint)
        }

        return mutableBitmap
    }
}

data class AnalysisResult(
    val nodes: List<UiNode>,
    val annotatedBitmap: Bitmap?
)
