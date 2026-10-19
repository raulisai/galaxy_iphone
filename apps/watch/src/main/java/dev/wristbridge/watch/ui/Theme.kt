package dev.wristbridge.watch.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import kotlin.math.cos
import kotlin.math.sin

/** Apple-Watch-like palette: pure black canvas, dark grey grouped cards, system accent colors. */
internal object WbColors {
    val Black = Color(0xFF000000)
    val Card = Color(0xFF1C1C1E)
    val CardHigh = Color(0xFF2C2C2E)
    val Fill = Color(0xFF3A3A3C)
    val Label = Color(0xFFFFFFFF)
    val Secondary = Color(0xFF8E8E93)
    val Green = Color(0xFF30D158)
    val Red = Color(0xFFFF453A)
    val Amber = Color(0xFFFF9F0A)
    val Blue = Color(0xFF0A84FF)
    val Pink = Color(0xFFFF375F)
}

/**
 * Screen-size scale. The design reference is a 192 dp wide Galaxy Watch7 (432 px); everything that has a
 * physical size is expressed as `metrics.dp(referenceDp)` so smaller or larger watches scale smoothly.
 */
@Immutable
internal class Metrics(val k: Float) {
    fun dp(v: Int): Dp = (v * k).dp
    fun dp(v: Float): Dp = (v * k).dp

    /** Text follows the screen scale only mildly: the user's font scale is already applied by sp. */
    val text: Float get() = k.coerceIn(0.9f, 1.12f)
}

internal val LocalMetrics = compositionLocalOf { Metrics(1f) }

/** Text with explicit Apple-like color/weight defaults and screen-size scaling on top of the theme style. */
@Composable
internal fun WbText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = WbColors.Label,
    weight: FontWeight? = null,
    align: TextAlign = TextAlign.Start,
    maxLines: Int = Int.MAX_VALUE,
) {
    val k = LocalMetrics.current.text
    val scaled = style.copy(
        fontSize = style.fontSize.scaled(k),
        lineHeight = style.lineHeight.scaled(k),
    )
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontWeight = weight,
        textAlign = align,
        overflow = TextOverflow.Ellipsis,
        maxLines = maxLines,
        style = scaled,
    )
}

private fun TextUnit.scaled(k: Float): TextUnit = if (isSpecified) this * k else this

// ---------------------------------------------------------------------------------------------
// Vector-free glyphs: drawn on a Canvas so the module needs no icon dependency or drawable resources.
// ---------------------------------------------------------------------------------------------

internal enum class GlyphKind { PLAY, PAUSE, NEXT, PREVIOUS, PLUS, MINUS, CHEVRON, NOTE, BELL, GEAR, PHONE, PHONE_DOWN, RADIO }

/** [description] names a glyph that stands alone; without it the glyph is decorative and hidden from TalkBack. */
@Composable
internal fun Glyph(kind: GlyphKind, tint: Color, modifier: Modifier = Modifier, description: String? = null) {
    val semanticsModifier = if (description != null) {
        Modifier.semantics { contentDescription = description; role = Role.Image }
    } else {
        Modifier.decorative()
    }
    Canvas(modifier.then(semanticsModifier)) {
        val s = size.minDimension / 24f
        fun p(x: Float, y: Float) = Offset(x * s, y * s)
        fun triangle(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
            val path = Path().apply {
                moveTo(x1 * s, y1 * s); lineTo(x2 * s, y2 * s); lineTo(x3 * s, y3 * s); close()
            }
            drawPath(path, tint, style = Fill)
            drawPath(path, tint, style = Stroke(width = 2f * s, join = StrokeJoin.Round))
        }
        fun bar(x: Float) = drawRoundRect(tint, p(x, 5f), Size(3f * s, 14f * s), CornerRadius(1.2f * s))
        when (kind) {
            GlyphKind.PLAY -> triangle(7.5f, 5f, 7.5f, 19f, 19f, 12f)
            GlyphKind.PAUSE -> {
                drawRoundRect(tint, p(6.5f, 5f), Size(4f * s, 14f * s), CornerRadius(1.4f * s))
                drawRoundRect(tint, p(13.5f, 5f), Size(4f * s, 14f * s), CornerRadius(1.4f * s))
            }
            GlyphKind.NEXT -> {
                triangle(4.5f, 5.5f, 4.5f, 18.5f, 15f, 12f)
                bar(17f)
            }
            GlyphKind.PREVIOUS -> {
                triangle(19.5f, 5.5f, 19.5f, 18.5f, 9f, 12f)
                bar(4f)
            }
            GlyphKind.PLUS, GlyphKind.MINUS -> {
                drawLine(tint, p(5f, 12f), p(19f, 12f), 2.8f * s, StrokeCap.Round)
                if (kind == GlyphKind.PLUS) drawLine(tint, p(12f, 5f), p(12f, 19f), 2.8f * s, StrokeCap.Round)
            }
            GlyphKind.CHEVRON -> {
                val path = Path().apply { moveTo(9f * s, 5f * s); lineTo(16f * s, 12f * s); lineTo(9f * s, 19f * s) }
                drawPath(path, tint, style = Stroke(2.6f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            GlyphKind.NOTE -> {
                drawCircle(tint, 3.6f * s, p(8f, 18f))
                drawRect(tint, p(10.4f, 4.5f), Size(2.4f * s, 13.5f * s))
                val flag = Path().apply {
                    moveTo(11.6f * s, 5f * s)
                    cubicTo(13f * s, 9.5f * s, 19.5f * s, 8.5f * s, 18.5f * s, 14.5f * s)
                }
                drawPath(flag, tint, style = Stroke(2.6f * s, cap = StrokeCap.Round))
            }
            GlyphKind.BELL -> {
                val body = Path().apply {
                    moveTo(12f * s, 3f * s)
                    cubicTo(8.2f * s, 3f * s, 6.5f * s, 6f * s, 6.5f * s, 9.5f * s)
                    lineTo(6.5f * s, 14f * s); lineTo(4.5f * s, 17f * s); lineTo(19.5f * s, 17f * s)
                    lineTo(17.5f * s, 14f * s); lineTo(17.5f * s, 9.5f * s)
                    cubicTo(17.5f * s, 6f * s, 15.8f * s, 3f * s, 12f * s, 3f * s)
                    close()
                }
                drawPath(body, tint, style = Fill)
                drawCircle(tint, 2.1f * s, p(12f, 20.2f))
            }
            GlyphKind.GEAR -> {
                drawCircle(tint, 5f * s, p(12f, 12f), style = Stroke(2.8f * s))
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val c = cos(a).toFloat()
                    val d = sin(a).toFloat()
                    drawLine(tint, p(12f + 8f * c, 12f + 8f * d), p(12f + 10.4f * c, 12f + 10.4f * d), 3.4f * s, StrokeCap.Round)
                }
            }
            GlyphKind.PHONE, GlyphKind.PHONE_DOWN -> {
                val angle = if (kind == GlyphKind.PHONE_DOWN) 135f else 0f
                rotate(angle, pivot = p(12f, 12f)) {
                    drawArc(
                        color = tint, startAngle = 90f, sweepAngle = 90f, useCenter = false,
                        topLeft = p(4f, 4f), size = Size(16f * s, 16f * s),
                        style = Stroke(5.4f * s, cap = StrokeCap.Round),
                    )
                }
            }
            GlyphKind.RADIO -> {
                drawCircle(tint, 1.9f * s, p(12f, 19f))
                for (r in floatArrayOf(6f, 10.5f)) {
                    drawArc(
                        color = tint, startAngle = 225f, sweepAngle = 90f, useCenter = false,
                        topLeft = p(12f - r, 19f - r), size = Size(2f * r * s, 2f * r * s),
                        style = Stroke(2.6f * s, cap = StrokeCap.Round),
                    )
                }
            }
        }
    }
}
