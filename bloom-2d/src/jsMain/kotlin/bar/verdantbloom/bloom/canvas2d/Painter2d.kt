package bar.verdantbloom.bloom.canvas2d

import bar.verdantbloom.bloom.api.ModelRing
import bar.verdantbloom.bloom.api.Palette
import bar.verdantbloom.bloom.api.RingIds
import org.w3c.dom.CanvasGradient
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/** How much the glow pass may cost. Chosen by [QualityGovernor] unless the host pins it. */
enum class Quality2d {
    /** One wide translucent stroke per ring, half the samples. For software canvases and old phones. */
    LEAN,

    /** Three widening translucent strokes per ring. No blur filter involved. The default. */
    STANDARD,

    /** `shadowBlur` glow. Only when the frame budget has been shown to allow it. */
    RICH,
}

/**
 * Draws one frame of a [Scene2d] into a 2D context. The canvas stays TRANSPARENT (the page ground shows
 * through), which is why the gaps at crossings are cut with `destination-out` instead of being painted
 * in a ground colour, and why the glow goes in afterwards with `destination-over` (behind the lines, so
 * it fills the background but never washes out a crisp line or a gap edge).
 *
 * Pass 1, far to near, per chunk: erase a slightly wider "casing" (butt caps), then stroke the line
 *   (round caps). The nearer ring therefore interrupts the farther one with a small gap on both sides -
 *   the knot-diagram convention - and the single linking of every pair of fibers can be read off.
 *   Spin discs are items of the same sorted list.
 * Pass 2: louche. Glow strokes + a milky haze at the bloom centre, all behind pass 1.
 *
 * No allocation per frame: colour strings are cached, the dash arrays and the haze gradient are reused.
 */
class Painter2d(maxDiscs: Int = Scene2d.MAX_DISCS) {
    /** "Louche" 0..1. 0 switches pass 2 off completely. */
    var louche: Double = 0.5

    /** Ring to emphasise, or RingIds.NONE. */
    var highlight: Int = RingIds.NONE

    /** stroke() + fill() calls of the last frame (diagnostics and tests). */
    var drawCalls: Int = 0
        private set

    private val ringCss = Array(RingIds.COUNT) { "#ffffff" }
    private val ringWidth = DoubleArray(RingIds.COUNT) { MODEL_WIDTH }
    private var glowCss = "#ffffff"
    private var themeFillCss = "#0000ff"
    private var themeStrokeCss = "#ff0000"
    private var themeWedgeCss = "#ffff00"
    private var hazeInner = "rgba(255,255,255,0.5)"
    private var hazeMid = "rgba(255,255,255,0.16)"
    private var hazeOuter = "rgba(255,255,255,0)"
    private var hazeStrength = 0.3
    private var haze: CanvasGradient? = null
    private var hazeOwner: CanvasRenderingContext2D? = null
    private var hazeStale = true

    private val discFillInt = IntArray(maxDiscs) { -1 }
    private val discStrokeInt = IntArray(maxDiscs) { -1 }
    private val discWedgeInt = IntArray(maxDiscs) { -1 }
    private val discFillCss = Array(maxDiscs) { "#0000ff" }
    private val discStrokeCss = Array(maxDiscs) { "#ff0000" }
    private val discWedgeCss = Array(maxDiscs) { "#ffff00" }

    private val ghostDash: Array<Double> = arrayOf(3.0, 5.0)
    private val noDash: Array<Double> = emptyArray()

    init {
        ringWidth[RingIds.VISITOR] = VISITOR_WIDTH
        ringWidth[RingIds.GHOST] = GHOST_WIDTH
    }

    fun setPalette(palette: Palette, rings: List<ModelRing>) {
        for (ring in 0 until RingIds.COUNT) ringCss[ring] = Css.hex(palette.ringColor(ring, rings))
        glowCss = Css.hex(palette.glow)
        themeFillCss = Css.hex(palette.accent)
        themeStrokeCss = Css.hex(palette.alert)
        themeWedgeCss = Css.hex(palette.warm)
        hazeInner = Css.rgba(palette.glow, 0.5)
        hazeMid = Css.rgba(palette.glow, 0.16)
        hazeOuter = Css.rgba(palette.glow, 0.0)
        // a milky cloud suits the night ground; on herbarium paper it must stay a faint wash
        hazeStrength = if (palette.night) 0.34 else 0.14
        hazeStale = true
    }

    fun paint(
        ctx: CanvasRenderingContext2D,
        scene: Scene2d,
        proj: Projector,
        pixelRatio: Double,
        quality: Quality2d,
        unixSeconds: Double,
        rippleGlow: Double,
        glowLayer: GlowLayer? = null,
    ) {
        drawCalls = 0
        val pr = pixelRatio
        val c = ctx.asDynamic()
        ctx.setTransform(pr, 0.0, 0.0, pr, 0.0, 0.0)
        ctx.globalAlpha = 1.0
        ctx.globalCompositeOperation = "source-over"
        ctx.clearRect(0.0, 0.0, proj.width, proj.height)
        if (scene.frames == 0) return
        c.lineJoin = "round"

        // bead position on the visitor ring (a travelling dot: "you are here")
        val vn = scene.ringPoints[RingIds.VISITOR]
        val beadPos = if (vn > 0) {
            val turns = unixSeconds / BEAD_PERIOD_SECONDS
            (turns - floor(turns)) * vn
        } else {
            -1.0
        }

        // ---- pass 1: painter's algorithm over chunks and discs
        val order = scene.order
        val visible = scene.itemVisible
        val discBase = scene.discItemBase
        for (idx in 0 until scene.itemCount) {
            val item = order[idx]
            if (!visible[item]) continue
            if (item >= discBase) {
                drawDisc(ctx, scene, proj, item - discBase, scene.itemDepth[item].toDouble(), pr)
            } else {
                drawChunk(ctx, scene, proj, item, beadPos)
            }
        }

        // ---- pass 2: louche, behind everything drawn so far
        var g = louche + 0.6 * rippleGlow
        if (g > 1.4) g = 1.4
        if (glowLayer != null && glowLayer.usable) {
            // The glow is soft by nature, so it lives in its own canvas UNDER this one at a quarter of the CSS
            // resolution: 1/16 of the fill cost, the browser's compositor does the upscale (which blurs it for
            // free), and the gaps cut in pass 1 show it through. LEAN refreshes it only every other frame.
            val lctx = glowLayer.ctx
            val ls = glowLayer.scale
            if (g > 0.004) {
                val interval = if (quality == Quality2d.LEAN) 2 else 1
                if (glowLayer.stale || glowLayer.age >= interval) {
                    lctx.setTransform(ls, 0.0, 0.0, ls, 0.0, 0.0)
                    lctx.globalAlpha = 1.0
                    lctx.globalCompositeOperation = "source-over"
                    lctx.clearRect(0.0, 0.0, proj.width, proj.height)
                    drawGlow(lctx, scene, proj, ls, quality, g, behind = false, step = glowStep(scene))
                    lctx.setTransform(ls, 0.0, 0.0, ls, 0.0, 0.0)
                    lctx.globalAlpha = 1.0
                    glowLayer.stale = false
                    glowLayer.blank = false
                    glowLayer.age = 0
                }
                glowLayer.age++
            } else if (!glowLayer.blank) {
                lctx.setTransform(ls, 0.0, 0.0, ls, 0.0, 0.0)
                lctx.clearRect(0.0, 0.0, proj.width, proj.height)
                glowLayer.blank = true
                glowLayer.stale = true
            }
        } else if (g > 0.004) {
            drawGlow(ctx, scene, proj, pr, quality, g, behind = true, step = 1)
        }

        ctx.setTransform(pr, 0.0, 0.0, pr, 0.0, 0.0)
        ctx.globalAlpha = 1.0
        ctx.globalCompositeOperation = "source-over"
    }

    private fun drawChunk(ctx: CanvasRenderingContext2D, scene: Scene2d, proj: Projector, item: Int, beadPos: Double) {
        val cpr = scene.chunksPerRing
        val ring = item / cpr
        val chunk = item - ring * cpr
        val n = scene.ringPoints[ring]
        val ringAlpha = scene.ringAlpha[ring]
        if (n < 2 || ringAlpha <= MIN_ALPHA) return
        val seg = scene.segments
        val base = ring * seg
        val start = chunk * scene.chunkSize
        var end = start + scene.chunkSize
        if (end > n) end = n
        val sx = scene.sx
        val sy = scene.sy
        val iStart = base + start
        val iEnd = base + (if (end == n) 0 else end)

        // cheap reject: both ends beyond the same edge (chunks are short, the middle cannot stray far)
        val x0 = sx[iStart].toDouble()
        val y0 = sy[iStart].toDouble()
        val x1 = sx[iEnd].toDouble()
        val y1 = sy[iEnd].toDouble()
        val w = proj.width
        val h = proj.height
        if ((x0 < -CULL_MARGIN && x1 < -CULL_MARGIN) || (x0 > w + CULL_MARGIN && x1 > w + CULL_MARGIN) ||
            (y0 < -CULL_MARGIN && y1 < -CULL_MARGIN) || (y0 > h + CULL_MARGIN && y1 > h + CULL_MARGIN)
        ) {
            return
        }

        // depth cue: nearer = wider and more opaque
        val rel = scene.itemDepth[item] / proj.distance
        var fade = 1.2 - 0.4 * rel
        if (fade > 1.0) fade = 1.0 else if (fade < 0.3) fade = 0.3
        var scale = sqrt(1.0 / rel)
        if (scale > 2.4) scale = 2.4 else if (scale < 0.6) scale = 0.6
        var alpha = ringAlpha * fade
        var width = ringWidth[ring] * scale
        if (highlight != RingIds.NONE) {
            if (ring == highlight) {
                width *= HIGHLIGHT_WIDTH
                alpha = alpha * 1.25 + 0.15
                if (alpha > 1.0) alpha = 1.0
            } else {
                alpha *= HIGHLIGHT_DIM
            }
        }

        val c = ctx.asDynamic()
        ctx.beginPath()
        ctx.moveTo(x0, y0)
        for (k in start + 1..end) {
            val i = base + (if (k == n) 0 else k)
            ctx.lineTo(sx[i].toDouble(), sy[i].toDouble())
        }

        if (ring == RingIds.GHOST) {
            // a ghost occludes nothing: no casing, thin dashes
            ctx.setLineDash(ghostDash)
        } else if (scene.itemCasing[item]) {
            // only chunks that come near another ring can be part of a crossing (Scene2d.itemCasing)
            ctx.globalCompositeOperation = "destination-out"
            ctx.globalAlpha = if (ringAlpha < 1.0) ringAlpha else 1.0
            c.lineCap = "butt"
            ctx.lineWidth = width + 2.0 * (GAP_PX + GAP_PER_WIDTH * width)
            ctx.stroke()
            drawCalls++
            ctx.globalCompositeOperation = "source-over"
        }
        ctx.globalAlpha = alpha
        c.lineCap = "round"
        ctx.lineWidth = width
        c.strokeStyle = ringCss[ring]
        ctx.stroke()
        drawCalls++
        if (ring == RingIds.GHOST) ctx.setLineDash(noDash)

        if (ring == RingIds.VISITOR && beadPos >= start && beadPos < end) {
            val k0 = floor(beadPos).toInt()
            val f = beadPos - k0
            val a = base + (if (k0 >= n) 0 else k0)
            val b = base + (if (k0 + 1 >= n) 0 else k0 + 1)
            val bx = sx[a] + (sx[b] - sx[a]) * f
            val by = sy[a] + (sy[b] - sy[a]) * f
            ctx.globalAlpha = if (alpha * 1.3 > 1.0) 1.0 else alpha * 1.3
            c.fillStyle = ringCss[ring]
            ctx.beginPath()
            ctx.arc(bx, by, width * 1.5 + 1.0, 0.0, TWO_PI)
            ctx.fill()
            drawCalls++
        }
    }

    private fun drawDisc(ctx: CanvasRenderingContext2D, scene: Scene2d, proj: Projector, slot: Int, depth: Double, pr: Double) {
        val r = scene.discRadius[slot]
        val k = scene.discScale[slot]
        if (r * k < MIN_DISC_PX) return
        var fade = 1.2 - 0.4 * (depth / proj.distance)
        if (fade > 1.0) fade = 1.0 else if (fade < 0.3) fade = 0.3
        val alpha = scene.discAlpha[slot] * fade
        if (alpha <= MIN_ALPHA) return

        val fillCss: String
        val strokeCss: String
        val wedgeCss: String
        if (scene.discTriad[slot] == Scene2d.TRIAD_THEME) {
            fillCss = themeFillCss
            strokeCss = themeStrokeCss
            wedgeCss = themeWedgeCss
        } else {
            if (discFillInt[slot] != scene.discFill[slot]) {
                discFillInt[slot] = scene.discFill[slot]
                discFillCss[slot] = Css.hex(scene.discFill[slot])
            }
            if (discStrokeInt[slot] != scene.discStroke[slot]) {
                discStrokeInt[slot] = scene.discStroke[slot]
                discStrokeCss[slot] = Css.hex(scene.discStroke[slot])
            }
            if (discWedgeInt[slot] != scene.discWedge[slot]) {
                discWedgeInt[slot] = scene.discWedge[slot]
                discWedgeCss[slot] = Css.hex(scene.discWedge[slot])
            }
            fillCss = discFillCss[slot]
            strokeCss = discStrokeCss[slot]
            wedgeCss = discWedgeCss[slot]
        }

        val c = ctx.asDynamic()
        // disc-local bloom units -> device px; the disc is drawn as the true affine image of a tilted circle
        ctx.setTransform(
            pr * scene.discA[slot], pr * scene.discB[slot],
            pr * scene.discC[slot], pr * scene.discD[slot],
            pr * scene.discE[slot], pr * scene.discF[slot],
        )
        ctx.globalCompositeOperation = "source-over"
        ctx.globalAlpha = alpha
        ctx.beginPath()
        ctx.arc(0.0, 0.0, r, 0.0, TWO_PI)
        c.fillStyle = fillCss
        ctx.fill()
        val stroke = r * SpinDiscGeometry.STROKE_FRACTION
        val minStroke = MIN_DISC_STROKE_PX / k
        ctx.lineWidth = if (stroke > minStroke) stroke else minStroke
        c.strokeStyle = strokeCss
        ctx.stroke()

        val angle = scene.discAngle[slot]
        val ca = cos(angle)
        val sa = sin(angle)
        c.fillStyle = wedgeCss
        ctx.beginPath()
        for (wedge in 0 until SpinDiscGeometry.WEDGE_COUNT) {
            val i0 = 2 * wedge
            val i1 = i0 + 1
            ctx.moveTo(0.0, 0.0)
            ctx.lineTo(SpinDiscGeometry.rimX(i0, r, ca, sa), SpinDiscGeometry.rimY(i0, r, ca, sa))
            ctx.lineTo(SpinDiscGeometry.rimX(i1, r, ca, sa), SpinDiscGeometry.rimY(i1, r, ca, sa))
            ctx.closePath()
        }
        ctx.fill()
        drawCalls += 3
        ctx.setTransform(pr, 0.0, 0.0, pr, 0.0, 0.0)
    }

    private fun glowStep(scene: Scene2d): Int {
        val step = scene.segments / GLOW_LAYER_POINTS
        return if (step < 1) 1 else step
    }

    /**
     * The louche pass into [ctx], whose base transform is a uniform [baseScale].
     * [behind] = true: composite under what is already there (`destination-over`, used when drawing straight
     * into the main canvas). false: plain `source-over` into an empty layer. [step]: use every n-th sample.
     */
    private fun drawGlow(
        ctx: CanvasRenderingContext2D,
        scene: Scene2d,
        proj: Projector,
        baseScale: Double,
        quality: Quality2d,
        g: Double,
        behind: Boolean,
        step: Int,
    ) {
        val c = ctx.asDynamic()
        val near = proj.near
        val sx = scene.sx
        val sy = scene.sy
        val sd = scene.sd
        ctx.globalCompositeOperation = if (behind) "destination-over" else "source-over"
        c.lineCap = "round"
        c.lineJoin = "round"
        ctx.setLineDash(noDash)

        // the haze is the rearmost layer: last when compositing behind, first when layering normally
        if (!behind) drawHaze(ctx, proj, baseScale, g)

        for (ring in 0 until RingIds.COUNT) {
            if (ring == RingIds.GHOST) continue
            val n = scene.ringPoints[ring]
            val ringAlpha = scene.ringAlpha[ring]
            if (n < 2 || ringAlpha <= MIN_ALPHA) continue
            val base = ring * scene.segments
            var pen = false
            var culled = false
            ctx.beginPath()
            var k = 0
            while (k < n) {
                val i = base + k
                k += step
                if (sd[i] <= near) {
                    pen = false
                    culled = true
                    continue
                }
                if (pen) ctx.lineTo(sx[i].toDouble(), sy[i].toDouble()) else ctx.moveTo(sx[i].toDouble(), sy[i].toDouble())
                pen = true
            }
            if (!culled) ctx.closePath() else if (pen && sd[base] > near) ctx.lineTo(sx[base].toDouble(), sy[base].toDouble())

            var boost = g
            if (highlight != RingIds.NONE) boost = if (ring == highlight) g * 1.6 + 0.2 else g * 0.75
            val a = ringAlpha * boost
            val w = ringWidth[ring]

            if (behind) {
                // straight into the main canvas: three layers, painted BEHIND, so tight first (it must end up on top)
                if (quality != Quality2d.LEAN) glowStroke(ctx, ringCss[ring], TIGHT_GLOW_ALPHA * a, w + 3.0)
                if (quality == Quality2d.RICH) {
                    glowBlurStroke(ctx, a, w, boost, baseScale)
                } else {
                    glowStroke(ctx, glowCss, 0.13 * a, w + 8.0 + 4.0 * boost)
                    if (quality == Quality2d.STANDARD) glowStroke(ctx, glowCss, 0.07 * a, w + 18.0 + 10.0 * boost)
                }
            } else {
                // glow layer: wide first. NO tight line-like stroke here - the layer shows through the gaps cut at
                // crossings, and anything that looks like the line itself would bridge them. Verified in a browser:
                // with a tight stroke in the layer the over / under of a link could not be read any more.
                if (quality == Quality2d.RICH) {
                    glowBlurStroke(ctx, a, w, boost, baseScale)
                } else if (quality == Quality2d.STANDARD) {
                    glowStroke(ctx, glowCss, 0.07 * a, w + 18.0 + 10.0 * boost)
                    glowStroke(ctx, ringCss[ring], 0.13 * a, w + 8.0 + 4.0 * boost)
                } else {
                    glowStroke(ctx, glowCss, 0.13 * a, w + 8.0 + 4.0 * boost)
                }
            }
        }

        if (behind) drawHaze(ctx, proj, baseScale, g)
    }

    private fun glowStroke(ctx: CanvasRenderingContext2D, style: String, alpha: Double, width: Double) {
        ctx.asDynamic().strokeStyle = style
        ctx.globalAlpha = cap(alpha)
        ctx.lineWidth = width
        ctx.stroke()
        drawCalls++
    }

    /** RICH: a real blur. `shadowBlur` is in DEVICE px of the canvas it is drawn into, hence [baseScale]. */
    private fun glowBlurStroke(ctx: CanvasRenderingContext2D, a: Double, w: Double, boost: Double, baseScale: Double) {
        val c = ctx.asDynamic()
        c.strokeStyle = glowCss
        c.shadowColor = glowCss
        ctx.shadowBlur = (8.0 + 22.0 * boost) * baseScale
        ctx.globalAlpha = cap(0.42 * a)
        ctx.lineWidth = w + 2.0
        ctx.stroke()
        drawCalls++
        ctx.shadowBlur = 0.0
        c.shadowColor = "rgba(0,0,0,0)"
    }

    /** Milky haze at the bloom centre. One cached unit gradient per context, placed with the transform. */
    private fun drawHaze(ctx: CanvasRenderingContext2D, proj: Projector, baseScale: Double, g: Double) {
        if (!proj.project(0.0, 0.0, 0.0)) return
        val radius = HAZE_RADIUS_UNITS * proj.outScale
        val devicePx = radius * baseScale
        if (devicePx < 2.0 || devicePx > 6000.0) return
        var gradient = haze
        if (gradient == null || hazeStale || hazeOwner !== ctx) {
            gradient = ctx.createRadialGradient(0.0, 0.0, 0.0, 0.0, 0.0, 1.0)
            gradient.addColorStop(0.0, hazeInner)
            gradient.addColorStop(0.45, hazeMid)
            gradient.addColorStop(1.0, hazeOuter)
            haze = gradient
            hazeOwner = ctx
            hazeStale = false
        }
        ctx.setTransform(baseScale * radius, 0.0, 0.0, baseScale * radius, baseScale * proj.outX, baseScale * proj.outY)
        ctx.globalAlpha = cap(hazeStrength * g)
        ctx.asDynamic().fillStyle = gradient
        ctx.fillRect(-1.0, -1.0, 2.0, 2.0)
        drawCalls++
        ctx.setTransform(baseScale, 0.0, 0.0, baseScale, 0.0, 0.0)
    }

    /** Drop the cached gradient (it belongs to one context). */
    fun releaseContext() {
        haze = null
        hazeOwner = null
        hazeStale = true
    }

    private fun cap(a: Double): Double = if (a > 1.0) 1.0 else if (a < 0.0) 0.0 else a

    companion object {
        const val MODEL_WIDTH: Double = 2.0
        const val VISITOR_WIDTH: Double = 2.8
        const val GHOST_WIDTH: Double = 1.1
        const val HIGHLIGHT_WIDTH: Double = 1.7
        const val HIGHLIGHT_DIM: Double = 0.7

        /** Gap cut on each side of a line where it passes over another: GAP_PX + GAP_PER_WIDTH * width. */
        const val GAP_PX: Double = 3.0
        const val GAP_PER_WIDTH: Double = 0.6

        /** Kept low on purpose: this stroke is line-shaped, and the glow is visible inside the crossing gaps. */
        const val TIGHT_GLOW_ALPHA: Double = 0.14
        const val MIN_ALPHA: Double = 0.004
        const val CULL_MARGIN: Double = 96.0
        const val MIN_DISC_PX: Double = 1.2
        const val MIN_DISC_STROKE_PX: Double = 0.75
        const val HAZE_RADIUS_UNITS: Double = 4.2
        const val BEAD_PERIOD_SECONDS: Double = 16.0

        /** Samples per ring used for glow paths in the low-resolution layer. */
        const val GLOW_LAYER_POINTS: Int = 64
        private const val TWO_PI: Double = 2.0 * PI
    }
}

/**
 * A second, low-resolution canvas that holds the louche pass. It sits in the DOM directly UNDER the main
 * canvas, stretched to the same CSS box, so the upscale costs this renderer nothing. [scale] = layer device
 * px per CSS px of the host. Made by [Canvas2dRenderer]; [Painter2d] falls back to drawing the glow straight
 * into the main canvas (`destination-over`) when there is none.
 */
class GlowLayer(val canvas: HTMLCanvasElement, val ctx: CanvasRenderingContext2D) {
    var scale: Double = DEFAULT_SCALE
        private set

    /** Redraw on the next frame (resize, palette, louche or highlight change). */
    var stale: Boolean = true

    /** Frames since the layer was last redrawn. */
    var age: Int = 0

    /** True while the layer holds no pixels (nothing to clear when the louche knob is at 0). */
    var blank: Boolean = true

    /** False until [resize] gave it a real size. */
    var usable: Boolean = false
        private set

    fun resize(cssWidth: Int, cssHeight: Int) {
        if (cssWidth <= 0 || cssHeight <= 0) {
            usable = false
            return
        }
        val w = maxOf(1, kotlin.math.ceil(cssWidth * DEFAULT_SCALE).toInt())
        val h = maxOf(1, kotlin.math.ceil(cssHeight * DEFAULT_SCALE).toInt())
        if (canvas.width != w) canvas.width = w
        if (canvas.height != h) canvas.height = h
        scale = DEFAULT_SCALE
        usable = true
        stale = true
        blank = true // resizing a canvas clears it
    }

    companion object {
        const val DEFAULT_SCALE: Double = 0.25
    }
}

/** CSS colour strings from 0xRRGGBB ints. Allocates; call on palette / colour CHANGE, never per frame. */
object Css {
    private const val DIGITS = "0123456789abcdef"

    fun hex(rgb: Int): String {
        val v = rgb and 0xffffff
        val sb = StringBuilder(7)
        sb.append('#')
        for (shift in 20 downTo 0 step 4) sb.append(DIGITS[(v shr shift) and 0xf])
        return sb.toString()
    }

    fun rgba(rgb: Int, alpha: Double): String {
        val v = rgb and 0xffffff
        val a = if (alpha < 0.0) 0.0 else if (alpha > 1.0) 1.0 else alpha
        return "rgba(${(v shr 16) and 0xff},${(v shr 8) and 0xff},${v and 0xff},$a)"
    }
}
