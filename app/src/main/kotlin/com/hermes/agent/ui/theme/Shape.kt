package com.hermes.agent.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// Rounding matches the Nous design system: chips/inputs ~11dp, cards 13–15dp,
// hero cards/sheets 18dp, pills via extraLarge.
val HermesShapes = Shapes(
    extraSmall = RoundedCornerShape(7.dp),
    small = RoundedCornerShape(11.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/** Chat bubble corner radius — asymmetric for a "tail" feel. */
val ChatBubbleRadius = 16.dp
