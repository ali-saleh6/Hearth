package com.sal7one.transiber.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sal7one.transiber.caption.TvControls

/**
 * Television presentation helpers. A TV is driven by a D-pad from across the
 * room, so two things differ from the phone layout: the panel edges are not
 * trustworthy (overscan), and focus has to be visible because there is no
 * pointer to show where input will land.
 */
@Composable
internal fun rememberIsTv(): Boolean {
    val context = LocalContext.current
    return remember(context) { TvControls.isTvDevice(context) }
}

/**
 * Keep content inside the title-safe area. Television panels crop the signal
 * edges, so Google's TV guidance reserves 5% of each edge; phones keep their
 * own padding instead.
 */
internal fun Modifier.tvSafeArea(isTv: Boolean): Modifier =
    if (isTv) padding(horizontal = 48.dp, vertical = 27.dp) else this

/**
 * Make D-pad focus obvious. Material's default indication is a touch ripple,
 * which never fires for a remote, so a focused control would otherwise look
 * identical to an unfocused one.
 */
@Composable
internal fun Modifier.tvFocusHighlight(
    shape: Shape = RoundedCornerShape(18.dp),
    scaleWhenFocused: Float = 1.04f,
): Modifier {
    var focused by remember { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.primary
    val scale by animateFloatAsState(if (focused) scaleWhenFocused else 1f, label = "tvFocusScale")
    return this
        .onFocusChanged { focused = it.isFocused || it.hasFocus }
        .scale(scale)
        .border(if (focused) 3.dp else 0.dp, if (focused) accent else Color.Transparent, shape)
}
