package team.hex.meshlink.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign

/**
 * Text painted with a horizontal multi-stop gradient that slowly shimmers
 * across the run width. Uses [TextStyle.brush] (Compose 1.5+) so the
 * gradient maps to the text glyphs themselves rather than a bounding
 * rectangle.
 *
 * The brush re-evaluates on every frame the infinite transition ticks,
 * which lets us shift its start/end offsets and produce a moving sheen
 * without re-laying-out the text. Costs roughly one extra draw per
 * recomposition tick — fine for a handful of hero labels.
 */
@Composable
fun GradientText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    colors: List<Color> = defaultBrandColors(),
    animated: Boolean = true,
    textAlign: TextAlign? = null,
) {
    val phase = if (animated) {
        val transition = rememberInfiniteTransition(label = "gradient-shimmer")
        val v by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 4_500, easing = LinearEasing),
            ),
            label = "gradient-phase",
        )
        v
    } else 0f

    // Wide gradient that mirrors at the edges so the sheen is continuous
    // as the start/end offsets translate across the text width.
    val brush = remember(colors, phase) {
        val width = 1200f
        val offset = phase * width
        Brush.linearGradient(
            colors = colors + colors.first(),
            start = Offset(-width / 2f + offset, 0f),
            end = Offset(width / 2f + offset, 0f),
            tileMode = TileMode.Mirror,
        )
    }

    Text(
        text = text,
        style = style.copy(brush = brush),
        textAlign = textAlign,
        modifier = modifier,
    )
}

@Composable
private fun defaultBrandColors(): List<Color> {
    val cs = MaterialTheme.colorScheme
    return listOf(cs.primary, cs.tertiary, cs.secondary, cs.primary)
}
