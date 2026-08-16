package com.morpheus.family.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.morpheus.family.ui.theme.BombNavy
import com.morpheus.family.ui.theme.BombYellow
import com.morpheus.family.ui.theme.PressStart2P

/**
 * Shared "Bomberman-style" arcade widgets: hard-shadow beveled blocks, chunky
 * pixel buttons, a drawn bomb sprite and a faint stage grid. Everything else in
 * the app inherits the arcade look automatically from the theme (pixel fonts,
 * saturated palette, blocky shapes); these add the signature flourishes.
 */

private val BLOCK_DEPTH = 5.dp
private val BORDER_W = 3.dp

/**
 * A beveled arcade block: a filled, navy-bordered panel with a hard offset drop
 * shadow, so it reads like a raised tile on the stage. Drop it in place of a Card.
 */
@Composable
fun ArcadeBlock(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    border: Color = BombNavy,
    shadow: Color = BombNavy,
    shape: Shape = MaterialTheme.shapes.medium,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Column(
            modifier
                .drawBehind {
                    val d = BLOCK_DEPTH.toPx()
                    val r = 6.dp.toPx()
                    drawRoundRect(
                        color = shadow,
                        topLeft = Offset(d, d),
                        size = Size(size.width, size.height),
                        cornerRadius = CornerRadius(r, r),
                    )
                }
                .background(color, shape)
                .border(BorderStroke(BORDER_W, border), shape)
                .clip(shape)
                .padding(contentPadding),
            content = content,
        )
    }
}

/**
 * Chunky arcade button with a hard shadow that visibly "presses in" when held.
 * Fills its width by default; pass a [Modifier] to change that.
 */
@Composable
fun PixelButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val down = pressed && enabled
    val shape: Shape = MaterialTheme.shapes.small
    val faceColor = if (enabled) color else color.copy(alpha = 0.4f)

    Box(modifier) {
        // Shadow block behind; the face slides onto it while pressed.
        Box(
            Modifier
                .matchParentSize()
                .offset(BLOCK_DEPTH, BLOCK_DEPTH)
                .background(BombNavy, shape),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .offset(
                    x = if (down) BLOCK_DEPTH else 0.dp,
                    y = if (down) BLOCK_DEPTH else 0.dp,
                )
                .background(faceColor, shape)
                .border(BorderStroke(BORDER_W, BombNavy), shape)
                .clip(shape)
                .heightIn(min = 52.dp)
                .clickableNoRipple(interaction, enabled, onClick)
                .padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompositionLocalProvider(LocalContentColor provides contentColor) { content() }
        }
    }
}

// Clickable without the Material ripple (the hard press-offset is the feedback).
private fun Modifier.clickableNoRipple(
    interaction: MutableInteractionSource,
    enabled: Boolean,
    onClick: () -> Unit,
): Modifier = this.clickable(
    interactionSource = interaction,
    indication = null,
    enabled = enabled,
    onClick = onClick,
)

/**
 * A Press Start 2P title with the classic hard arcade drop shadow (offset navy
 * copy behind the fill). Keep the text short — this is a logotype face.
 */
@Composable
fun PixelTitle(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onBackground,
    shadow: Color = BombNavy,
    fontSize: androidx.compose.ui.unit.TextUnit = 22.sp,
    textAlign: TextAlign = TextAlign.Center,
) {
    Box(modifier) {
        Text(
            text,
            color = shadow,
            fontFamily = PressStart2P,
            fontWeight = FontWeight.Normal,
            fontSize = fontSize,
            textAlign = textAlign,
            modifier = Modifier.offset(3.dp, 3.dp).fillMaxWidth(),
        )
        Text(
            text,
            color = color,
            fontFamily = PressStart2P,
            fontWeight = FontWeight.Normal,
            fontSize = fontSize,
            textAlign = textAlign,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * A drawn bomb — the app mascot. A round navy body with a highlight, a cap and a
 * fuse whose tip sparks yellow-orange when [lit]. Original vector art (no game
 * assets), sized by [size].
 */
@Composable
fun BombSprite(
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    lit: Boolean = true,
    bodyColor: Color = BombNavy,
) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val bodyR = w * 0.34f
        val cx = w * 0.46f
        val cy = h * 0.62f

        // Fuse: a short curve from the cap up to the right.
        val fuseStart = Offset(cx + bodyR * 0.35f, cy - bodyR * 0.95f)
        val fuseTip = Offset(w * 0.82f, h * 0.16f)
        val fuse = Path().apply {
            moveTo(fuseStart.x, fuseStart.y)
            cubicTo(
                cx + bodyR, cy - bodyR * 1.9f,
                w * 0.62f, h * 0.10f,
                fuseTip.x, fuseTip.y,
            )
        }
        drawPath(fuse, Color(0xFF9A6A3A), style = Stroke(width = w * 0.055f, cap = StrokeCap.Round))

        // Body + cap.
        drawCircle(bodyColor, radius = bodyR, center = Offset(cx, cy))
        // Cap (little cylinder on top).
        drawRoundRect(
            color = bodyColor,
            topLeft = Offset(cx - bodyR * 0.34f, cy - bodyR * 1.28f),
            size = Size(bodyR * 0.68f, bodyR * 0.55f),
            cornerRadius = CornerRadius(w * 0.02f, w * 0.02f),
        )
        // Glossy highlight.
        drawCircle(
            Color.White.copy(alpha = 0.85f),
            radius = bodyR * 0.20f,
            center = Offset(cx - bodyR * 0.38f, cy - bodyR * 0.40f),
        )

        // Spark at the fuse tip.
        if (lit) {
            drawCircle(Color(0xFFFF7A18), radius = w * 0.075f, center = fuseTip)
            drawCircle(BombYellow, radius = w * 0.05f, center = fuseTip)
            drawCircle(Color.White, radius = w * 0.022f, center = fuseTip)
        } else {
            drawCircle(Color(0xFF6B7280), radius = w * 0.03f, center = fuseTip)
        }
    }
}

/**
 * Faint stage grid drawn behind a screen's content: thin navy lines on a 26dp
 * pitch, so the sky-blue background reads as an arcade playfield without hurting
 * text contrast. Apply to a full-height container.
 */
fun Modifier.arcadeGrid(color: Color = BombNavy, alpha: Float = 0.06f): Modifier =
    this.drawBehind {
        val step = 26.dp.toPx()
        val stroke = 1.dp.toPx()
        val c = color.copy(alpha = alpha)
        var x = 0f
        while (x <= size.width) {
            drawLine(c, Offset(x, 0f), Offset(x, size.height), strokeWidth = stroke)
            x += step
        }
        var y = 0f
        while (y <= size.height) {
            drawLine(c, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke)
            y += step
        }
    }
