package team.hex.meshlink.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Liquid-glass primitives for MeshLink's UI.
 *
 *   - [AuroraBackground] paints the saturated gradient that lives behind
 *     every screen and animates slowly.
 *   - [GlassSurface] is a translucent, gently-bordered panel that floats
 *     on top of the aurora. On API 31+ it picks up [RenderEffect] blur
 *     from whatever is rendered beneath it for a real frosted look; on
 *     older devices it falls back to a translucent gradient that still
 *     reads as "glass".
 *   - [glassBorder] is the thin highlight stroke shared by chips, cards,
 *     and bubbles to keep visual language consistent.
 *
 * The blur radius and tint are tuned so the surfaces stay legible against
 * the dynamic-color aurora regardless of the wallpaper-derived palette.
 */

@Composable
fun AuroraBackground(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val transition = rememberInfiniteTransition(label = "aurora")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 18_000, easing = LinearEasing),
        ),
        label = "aurora-phase",
    )
    val cs = MaterialTheme.colorScheme
    val a = cs.primary.copy(alpha = 0.38f)
    val b = cs.tertiary.copy(alpha = 0.32f)
    val c = cs.secondary.copy(alpha = 0.30f)
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(cs.background)
            .drawBehind {
                val w = size.width; val h = size.height
                val t = phase * 2f * Math.PI.toFloat()
                val cx1 = w * (0.5f + 0.30f * kotlin.math.cos(t))
                val cy1 = h * (0.30f + 0.18f * kotlin.math.sin(t * 0.7f))
                val cx2 = w * (0.5f + 0.30f * kotlin.math.cos(t + 2.1f))
                val cy2 = h * (0.70f + 0.18f * kotlin.math.sin(t * 0.7f + 2.1f))
                val cx3 = w * (0.5f + 0.30f * kotlin.math.cos(t + 4.2f))
                val cy3 = h * (0.50f + 0.18f * kotlin.math.sin(t * 0.7f + 4.2f))
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(a, Color.Transparent),
                        center = Offset(cx1, cy1),
                        radius = w * 0.7f,
                    ),
                    size = Size(w, h),
                )
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(b, Color.Transparent),
                        center = Offset(cx2, cy2),
                        radius = w * 0.65f,
                    ),
                    size = Size(w, h),
                )
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(c, Color.Transparent),
                        center = Offset(cx3, cy3),
                        radius = w * 0.55f,
                    ),
                    size = Size(w, h),
                )
            },
    ) { content() }
}

/**
 * Frosted-glass panel. Wrap content that should look "lifted" against the
 * aurora background. The optional [tint] is mixed on top of the blur so
 * dialogs and bubbles get a slightly different read.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    tint: Color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
    borderColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(shape)
            // Translucent gradient with a hairline highlight stroke gives a
            // legible "frosted" read against the animated aurora behind it,
            // without blurring the panel's own content (which RenderEffect
            // would do — Compose has no public API for blurring siblings).
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        tint,
                        tint.copy(alpha = (tint.alpha - 0.10f).coerceAtLeast(0.10f)),
                    )
                ),
                shape = shape,
            )
            .glassBorder(borderColor, shape),
    ) { content() }
}

/**
 * Hairline highlight stroke around a shape — the visual signature that
 * separates a translucent glass panel from its aurora background.
 */
fun Modifier.glassBorder(
    color: Color,
    shape: Shape,
    width: Dp = 1.dp,
): Modifier = this.border(width = width, color = color, shape = shape)

/** A subtle 1px highlight on a chip-like rounded shape. */
@Composable
fun GlassChip(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color, RoundedCornerShape(999.dp))
            .glassBorder(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                RoundedCornerShape(999.dp)),
    ) { content() }
}

/** Tiny "live" pulse for direct-link / online indicators. */
@Composable
fun LivePulse(
    color: Color,
    modifier: Modifier = Modifier,
    sizeDp: Dp = 8.dp,
) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val a by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_400, easing = LinearEasing),
        ),
        label = "pulse-alpha",
    )
    Canvas(modifier = modifier) {
        drawCircle(
            color = color.copy(alpha = a),
            radius = sizeDp.toPx() / 2f,
            center = Offset(size.width / 2f, size.height / 2f),
        )
    }
}
