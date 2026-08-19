package com.hermes.agent.ui.memory

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermes.agent.domain.model.Memory
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class StarNode(
    val memory: Memory,
    val x: Float,
    val y: Float,
    val radius: Float,
    val color: Color,
    val label: String,
)

/**
 * Interactive 2D Starmap Memory & Knowledge Graph Visualizer.
 * Renders user memories as celestial nodes with constellation lines,
 * pinch-to-zoom, pan, and interactive node selection.
 */
@Composable
fun StarmapCanvas(
    memories: List<Memory>,
    onDeleteMemory: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var selectedNode by remember { mutableStateOf<StarNode?>(null) }

    val transformState = rememberTransformableState { zoomChange, offsetChange, _ ->
        scale = (scale * zoomChange).coerceIn(0.5f, 3.5f)
        offset += offsetChange
    }

    // Generate node positions algorithmically around center
    val nodes = remember(memories) {
        if (memories.isEmpty()) emptyList()
        else {
            val count = memories.size
            val centerRadius = 260f
            val nodePalette = listOf(
                Color(0xFF64B5F6), // Blue
                Color(0xFF81C784), // Green
                Color(0xFFFFB74D), // Orange
                Color(0xFFBA68C8), // Purple
                Color(0xFF4DD0E1), // Cyan
                Color(0xFFFF8A65), // Coral
            )

            memories.mapIndexed { index, memory ->
                val angle = (index.toDouble() / count) * 2.0 * Math.PI
                val distance = centerRadius + (index % 3) * 90f
                val x = (cos(angle) * distance).toFloat() + 500f
                val y = (sin(angle) * distance).toFloat() + 500f
                val color = nodePalette[index % nodePalette.size]
                val label = memory.content.take(24) + if (memory.content.length > 24) "..." else ""
                StarNode(
                    memory = memory,
                    x = x,
                    y = y,
                    radius = 22f,
                    color = color,
                    label = label,
                )
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0D1117))
            .pointerInput(nodes) {
                detectTapGestures { tapOffset ->
                    val transformedTap = (tapOffset - offset) / scale
                    val tapped = nodes.find { node ->
                        val dx = transformedTap.x - node.x
                        val dy = transformedTap.y - node.y
                        sqrt(dx * dx + dy * dy) <= node.radius * 2.5f
                    }
                    selectedNode = tapped
                }
            }
            .transformable(state = transformState),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y,
                ),
        ) {
            // Draw background ambient grid lines
            val gridStep = 100f
            for (gx in 0..(size.width / gridStep).toInt() + 10) {
                drawLine(
                    color = Color(0x15FFFFFF),
                    start = Offset(gx * gridStep, 0f),
                    end = Offset(gx * gridStep, size.height + 500f),
                    strokeWidth = 1f,
                )
            }

            // Draw constellation links between nearby memory nodes
            for (i in nodes.indices) {
                for (j in (i + 1) until nodes.size) {
                    val n1 = nodes[i]
                    val n2 = nodes[j]
                    val dist = sqrt((n1.x - n2.x) * (n1.x - n2.x) + (n1.y - n2.y) * (n1.y - n2.y))
                    if (dist < 320f) {
                        drawLine(
                            color = Color(0x3080D8FF),
                            start = Offset(n1.x, n1.y),
                            end = Offset(n2.x, n2.y),
                            strokeWidth = 1.5f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f),
                        )
                    }
                }
            }

            // Draw Central Knowledge Core
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0x805C6BC0), Color.Transparent),
                    center = Offset(500f, 500f),
                    radius = 120f,
                ),
                radius = 120f,
                center = Offset(500f, 500f),
            )

            // Draw Star Nodes
            nodes.forEach { node ->
                val isSelected = selectedNode?.memory?.id == node.memory.id

                // Glow halo
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(node.color.copy(alpha = if (isSelected) 0.8f else 0.4f), Color.Transparent),
                        center = Offset(node.x, node.y),
                        radius = if (isSelected) node.radius * 2.8f else node.radius * 2.0f,
                    ),
                    radius = if (isSelected) node.radius * 2.8f else node.radius * 2.0f,
                    center = Offset(node.x, node.y),
                )

                // Node core
                drawCircle(
                    color = if (isSelected) Color.White else node.color,
                    radius = node.radius,
                    center = Offset(node.x, node.y),
                )

                // Selected ring
                if (isSelected) {
                    drawCircle(
                        color = Color.White,
                        radius = node.radius + 6f,
                        center = Offset(node.x, node.y),
                        style = Stroke(width = 2.5f),
                    )
                }
            }
        }

        // Overlay Instructions
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color(0xCC1E293B),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp),
        ) {
            Text(
                text = "✨ ${memories.size} Knowledge Stars • Pinch to zoom • Tap node to inspect",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFE2E8F0),
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }

        // Selected Memory Node Inspector Card
        selectedNode?.let { star ->
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = star.color.copy(alpha = 0.2f),
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Psychology,
                                    contentDescription = null,
                                    tint = star.color,
                                    modifier = Modifier.padding(6.dp),
                                )
                            }
                            Text(
                                text = " Memory Node",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }

                        IconButton(onClick = { selectedNode = null }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Outlined.Close, contentDescription = "Close", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = star.memory.content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(
                            onClick = {
                                onDeleteMemory(star.memory.id)
                                selectedNode = null
                            },
                        ) {
                            Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Text(" Remove Fact", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}
