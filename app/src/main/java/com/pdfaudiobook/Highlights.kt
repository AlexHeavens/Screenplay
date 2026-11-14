/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The six paragraph highlight colours, in wheel order clockwise from the top. [YELLOW] is the default.
 * Each has a pastel for light mode, under dark text, and a deep tone for dark mode, under light text; HighlightsTest holds all twelve to 4.5:1.
 */
enum class HighlightColor(val label: String, private val light: Color, private val dark: Color) {
    YELLOW("Yellow", Color(0xFFFFF176), Color(0xFF6B5B00)),
    ORANGE("Orange", Color(0xFFFFB74D), Color(0xFF8C4A00)),
    GREEN("Green", Color(0xFFA5D6A7), Color(0xFF1E5E2B)),
    BLUE("Blue", Color(0xFF90CAF9), Color(0xFF1A4F8A)),
    PURPLE("Purple", Color(0xFFCE93D8), Color(0xFF5B2D82)),
    PINK("Pink", Color(0xFFF48FB1), Color(0xFF8A2352));

    fun background(dark: Boolean) = if (dark) this.dark else light

    /** The pen colour as drawn: [background] at [HighlightBands.ALPHA], so the page shows through a little. */
    fun pen(dark: Boolean) = background(dark).copy(alpha = HighlightBands.ALPHA)

    companion object { val DEFAULT = YELLOW }
}

/** A paragraph's highlight, keyed like a [ParagraphNote]: by position and text hash, so it stays on its paragraph while the transcript grows. */
data class ParagraphHighlight(val index: Int, val hash: Int, val color: HighlightColor)

object Highlights {
    fun load(ctx: Context, id: String): List<ParagraphHighlight> {
        val f = File(Library.dir(ctx, id), Files.HIGHLIGHTS)
        if (!f.exists()) return emptyList()
        val a = JSONArray(f.readText())
        return List(a.length()) {
            val o = a.getJSONObject(it)
            ParagraphHighlight(o.getInt("index"), o.getInt("hash"), HighlightColor.entries.firstOrNull { c -> c.name == o.getString("color") } ?: HighlightColor.DEFAULT)
        }
    }

    fun save(ctx: Context, id: String, highlights: List<ParagraphHighlight>) {
        val a = JSONArray()
        highlights.forEach { a.put(JSONObject().put("index", it.index).put("hash", it.hash).put("color", it.color.name)) }
        Library.writeAtomically(File(Library.dir(ctx, id), Files.HIGHLIGHTS), a.toString())
    }

    fun place(highlights: List<ParagraphHighlight>, segments: List<Segment>): Map<Int, ParagraphHighlight> =
        ParagraphNotes.placeBy(highlights, segments, { it.hash }, { it.index })

    /** [at] highlighted in [color]; in the default colour (yellow) when none is given, and a highlight already there is removed instead when no colour is given. */
    fun toggle(all: List<ParagraphHighlight>, placed: Map<Int, ParagraphHighlight>, segments: List<Segment>, at: Int, color: HighlightColor? = null): List<ParagraphHighlight> {
        val existing = placed[at]
        val others = all - setOfNotNull(existing)
        if (color == null && existing != null) return others
        return others + ParagraphHighlight(at, ParagraphNotes.hash(segments[at].text), color ?: HighlightColor.DEFAULT)
    }
}

/** Marker-pen geometry: one band per text line, as if a highlighter had been run across each line of the paragraph. */
object HighlightBands {
    /** Opacity of the pen. HighlightsTest checks the text contrast on the colour as composited over each theme's background. */
    const val ALPHA = 0.55f

    /** Each line's own box (left, top, right, bottom) from the text layout, so a short last line is a short box. */
    fun lines(layout: TextLayoutResult): List<Rect> =
        List(layout.lineCount) { Rect(layout.getLineLeft(it), layout.getLineTop(it), layout.getLineRight(it), layout.getLineBottom(it)) }

    /**
     * One band per non-empty line: shrunk by [inset] top and bottom, so the bands of adjacent lines leave a gap and never touch
     * (at most a quarter of the line's height, so a tight line keeps a visible band), and widened by [bleed] each side so the rounded ends sit just outside the letters.
     */
    fun of(lines: List<Rect>, inset: Float, bleed: Float): List<Rect> =
        lines.filter { it.width > 0f && it.height > 0f }.map {
            val v = inset.coerceIn(0f, it.height / 4)
            Rect(it.left - bleed, it.top + v, it.right + bleed, it.bottom - v)
        }
}

/** The bands for a laid-out paragraph at [density] pixels per dp: the pen's inset and bleed, in one place for the transcript and the notes panel. */
fun HighlightBands.forLayout(layout: TextLayoutResult?, density: Float): List<Rect> =
    layout?.let { of(lines(it), inset = 1.5f * density, bleed = 2f * density) }.orEmpty()

/** True when the theme is the dark one. */
@Composable
fun isDarkTheme() = MaterialTheme.colorScheme.background.red < 0.5f

/** [text] with the pen run across each of its lines in [color]. */
@Composable
fun PenText(text: String, color: HighlightColor, modifier: Modifier = Modifier, style: androidx.compose.ui.text.TextStyle) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val density = LocalDensity.current.density
    val bands = remember(layout, density) { HighlightBands.forLayout(layout, density) }
    Text(text, modifier.highlighterBands(bands, color.pen(isDarkTheme())), onTextLayout = { layout = it }, style = style, color = MaterialTheme.colorScheme.onSurface)
}

/** The pen's bands on a paragraph's semantics node, in the text's pixel space, so a test can tell bands from a block. */
val HighlightBandsKey = SemanticsPropertyKey<List<Rect>>("HighlightBands")
var SemanticsPropertyReceiver.highlightBands by HighlightBandsKey

/** Draws [bands] behind the content in [color], with rounded ends. */
fun Modifier.highlighterBands(bands: List<Rect>, color: Color): Modifier = drawBehind {
    for (b in bands) drawRoundRect(color, b.topLeft, b.size, CornerRadius(minOf(3.dp.toPx(), b.height / 2)))
}

/** The highlight colour's name on a paragraph's semantics node, for tests and accessibility services. */
val HighlightKey = SemanticsPropertyKey<String>("Highlight")
var SemanticsPropertyReceiver.highlight by HighlightKey

/** Geometry of the wheel, in dp: slices lie between [INNER] and [OUTER] from the centre; a release further than [CANCEL] picks nothing. */
object HighlightWheel {
    const val INNER = 24f
    const val OUTER = 92f
    const val CANCEL = 150f
    const val LABEL_RADIUS = 58f

    /** Slice 0 is centred at the top, the rest follow clockwise 60° apart. [dx], [dy] is the finger's offset from the wheel's centre, in the same unit as the radii. Null in the hole or outside the cancel radius. */
    fun sliceAt(dx: Float, dy: Float, inner: Float = INNER, cancel: Float = CANCEL): Int? {
        val r = hypot(dx, dy)
        if (r < inner || r > cancel) return null
        val degrees = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) + 90 + 30
        return (((degrees % 360) + 360) % 360 / 60).toInt().coerceAtMost(HighlightColor.entries.lastIndex)
    }

    /** The centre of slice [k] relative to the wheel's centre, at [radius]. */
    fun centreOf(k: Int, radius: Float): Offset {
        val a = (-90 + 60 * k) * PI / 180
        return Offset((radius * cos(a)).toFloat(), (radius * sin(a)).toFloat())
    }
}

/** Where the open wheel really sits, set by its popup when it clamps the wheel inside the window; picking measures from here. */
class WheelGeometry { var centre: Offset = Offset.Zero }

/** The wheel, drawn as a popup centred on [anchor] (window pixels, kept inside the window). [hovered] is the slice under the finger. */
@Composable
fun HighlightWheelPopup(anchor: Offset, geometry: WheelGeometry, hovered: Int?, dark: Boolean) {
    val outer = with(LocalDensity.current) { HighlightWheel.OUTER.dp.toPx() }
    val provider = remember(anchor, outer) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val cx = anchor.x.coerceIn(outer, (windowSize.width - outer).coerceAtLeast(outer))
                val cy = anchor.y.coerceIn(outer, (windowSize.height - outer).coerceAtLeast(outer))
                geometry.centre = Offset(cx, cy)
                return IntOffset((cx - popupContentSize.width / 2f).roundToInt(), (cy - popupContentSize.height / 2f).roundToInt())
            }
        }
    }
    val ring = MaterialTheme.colorScheme.surface
    val edge = MaterialTheme.colorScheme.onSurface
    Popup(provider, properties = PopupProperties(focusable = false, clippingEnabled = false)) {
        Box(Modifier.size((HighlightWheel.OUTER * 2).dp).testTag("highlight-wheel")) {
            Canvas(Modifier.size((HighlightWheel.OUTER * 2).dp)) {
                val r = HighlightWheel.OUTER.dp.toPx()
                val hole = HighlightWheel.INNER.dp.toPx()
                HighlightColor.entries.forEachIndexed { k, c ->
                    val grow = if (k == hovered) 6.dp.toPx() else 0f
                    drawArc(c.background(dark), -120f + 60f * k, 60f, useCenter = true, topLeft = Offset(-grow, -grow), size = Size(2 * r + 2 * grow, 2 * r + 2 * grow))
                    drawArc(edge, -120f + 60f * k, 60f, useCenter = true, topLeft = Offset(-grow, -grow), size = Size(2 * r + 2 * grow, 2 * r + 2 * grow), style = Stroke(if (k == hovered) 3.dp.toPx() else 1.dp.toPx()))
                }
                drawCircle(ring, hole)
            }
            HighlightColor.entries.forEachIndexed { k, c ->
                val at = HighlightWheel.centreOf(k, HighlightWheel.LABEL_RADIUS)
                Box(
                    Modifier.size(28.dp)
                        .offset((HighlightWheel.OUTER + at.x - 14).dp, (HighlightWheel.OUTER + at.y - 14).dp)
                        .testTag("highlight-slice-$k")
                        .semantics { contentDescription = c.label; selected = k == hovered },
                )
            }
        }
    }
}
