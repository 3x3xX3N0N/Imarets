package bar.verdantbloom.bloom.canvas2d

import bar.verdantbloom.bloom.api.BloomConfig
import bar.verdantbloom.bloom.api.BloomWorld
import bar.verdantbloom.bloom.api.LorenzState
import bar.verdantbloom.bloom.api.ModelRing
import bar.verdantbloom.bloom.api.PerturbKind
import bar.verdantbloom.bloom.api.PourList
import bar.verdantbloom.bloom.api.Quat
import bar.verdantbloom.bloom.api.RingIds
import bar.verdantbloom.bloom.api.SpinDisc
import bar.verdantbloom.bloom.api.Vec3
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLElement
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A minimal, fully controllable world: ring 0 and ring 1 form a Hopf LINK (two unit circles, each through
 * the centre of the other, in perpendicular planes), every other ring is off unless listed in [extra].
 * Ring 0: unit circle in the XY plane about the origin. Ring 1: unit circle in the XZ plane about (1, 0, 0).
 */
class LinkWorld(
    private val extra: Map<Int, (Double) -> Vec3> = emptyMap(),
    private val alphas: Map<Int, Double> = emptyMap(),
    override val spinDiscs: List<SpinDisc> = emptyList(),
) : BloomWorld {
    override val config = BloomConfig()
    override val rings: List<ModelRing> = PourList.rings(config)
    override var unixSeconds = 0.0
    override fun advanceTo(unixSeconds: Double) {
        this.unixSeconds = unixSeconds
    }

    override val worldState = LorenzState(1.0, 1.0, 1.0)
    override val visitorState = LorenzState(1.0, 1.0, 1.0)
    override val worldRotation = Quat.IDENTITY
    override val visitorBase = Vec3.UNIT_Z
    override var rho = 28.0
    override var driftRate = 1.0
    override var reducedMotion = false
    override var ghostEnabled = false
    override fun perturb(kind: PerturbKind, magnitude: Double) = Unit

    /** Moves every ring: lets tests check that a followed anchor is tracked. */
    var shift: Vec3 = Vec3.ZERO

    fun point(ringId: Int, theta: Double): Vec3? {
        val p = when (ringId) {
            0 -> Vec3(cos(theta), sin(theta), 0.0)
            1 -> Vec3(1.0 + cos(theta), 0.0, sin(theta))
            else -> extra[ringId]?.invoke(theta)
        } ?: return null
        return Vec3(p.x + shift.x, p.y + shift.y, p.z + shift.z)
    }

    override fun sampleRing(ringId: Int, out: FloatArray, offset: Int, segments: Int): Int {
        if (point(ringId, 0.0) == null) return 0
        var o = offset
        for (i in 0 until segments) {
            val p = point(ringId, 2.0 * PI * i / segments)!!
            out[o++] = p.x.toFloat()
            out[o++] = p.y.toFloat()
            out[o++] = p.z.toFloat()
        }
        return segments
    }

    override fun ringAlpha(ringId: Int): Double = if (point(ringId, 0.0) == null) 0.0 else alphas[ringId] ?: 1.0
    override fun ringAnchor(ringId: Int): Vec3 = point(ringId, 0.0) ?: Vec3.ZERO
    override val lobeSwitchCount = 0
}

/** A recording stand-in for CanvasRenderingContext2D: enough surface for [Painter2d], no DOM needed. */
fun fakeContext(): dynamic = js(
    """({
        ops: [],
        counts: { stroke: 0, fill: 0, fillRect: 0, clearRect: 0, setLineDash: 0, moveTo: 0, lineTo: 0, arc: 0, gradient: 0, drawImage: 0 },
        maxShadowBlur: 0,
        transform: [1, 0, 0, 1, 0, 0],
        globalAlpha: 1, globalCompositeOperation: 'source-over', lineWidth: 1, lineCap: 'butt', lineJoin: 'miter',
        strokeStyle: '#000', fillStyle: '#000', shadowBlur: 0, shadowColor: 'rgba(0,0,0,0)', dash: [],
        setTransform: function (a, b, c, d, e, f) { this.transform = [a, b, c, d, e, f]; },
        clearRect: function () { this.counts.clearRect++; },
        beginPath: function () {},
        closePath: function () {},
        moveTo: function (x, y) { this.counts.moveTo++; if (x !== x || y !== y) throw new Error('NaN moveTo'); },
        lineTo: function (x, y) { this.counts.lineTo++; if (x !== x || y !== y) throw new Error('NaN lineTo'); },
        arc: function () { this.counts.arc++; },
        stroke: function () {
            this.counts.stroke++;
            if (this.shadowBlur > this.maxShadowBlur) this.maxShadowBlur = this.shadowBlur;
            this.ops.push({ op: 'stroke', mode: this.globalCompositeOperation, width: this.lineWidth, cap: this.lineCap,
                            alpha: this.globalAlpha, style: this.strokeStyle, dashed: this.dash.length > 0 });
        },
        fill: function () { this.counts.fill++; this.ops.push({ op: 'fill', mode: this.globalCompositeOperation, style: this.fillStyle, alpha: this.globalAlpha }); },
        fillRect: function () { this.counts.fillRect++; this.ops.push({ op: 'fillRect', mode: this.globalCompositeOperation, alpha: this.globalAlpha }); },
        setLineDash: function (d) { this.counts.setLineDash++; this.dash = d; },
        drawImage: function (image, x, y, w, h) { this.counts.drawImage++; this.ops.push({ op: 'drawImage', mode: this.globalCompositeOperation, alpha: this.globalAlpha, image: image, w: w, h: h }); },
        createRadialGradient: function () { this.counts.gradient++; return { stops: 0, addColorStop: function () { this.stops++; } }; }
    })""",
)

fun ctxOf(fake: dynamic): CanvasRenderingContext2D = fake.unsafeCast<CanvasRenderingContext2D>()

/** A fake host element + document able to hand out a fake canvas whose 2D context is [context] (null = no Canvas2D). */
fun fakeHost(width: Int, height: Int, context: dynamic, devicePixelRatio: Double = 2.0): dynamic {
    val host: dynamic = js(
        """({
            children: [], firstChild: null, canvases: [],
            insertBefore: function (node, before) { var i = before ? this.children.indexOf(before) : -1; if (i < 0) this.children.push(node); else this.children.splice(i, 0, node); node.parentNode = this; this.firstChild = this.children[0]; return node; },
            appendChild: function (node) { this.children.push(node); node.parentNode = this; return node; },
            removeChild: function (node) { var i = this.children.indexOf(node); if (i >= 0) this.children.splice(i, 1); node.parentNode = null; this.firstChild = this.children[0] || null; return node; }
        })""",
    )
    host.clientWidth = width
    host.clientHeight = height
    val document: dynamic = js("({})")
    document.defaultView = js("({})")
    document.defaultView.devicePixelRatio = devicePixelRatio
    document.createElement = { _: String ->
        val canvas: dynamic = js("({ style: {}, attributes: {}, width: 300, height: 150, parentNode: null, className: '' })")
        canvas.setAttribute = { name: String, value: String -> canvas.attributes[name] = value }
        canvas.getContext = { _: String -> context }
        host.canvases.push(canvas)
        canvas
    }
    host.ownerDocument = document
    return host
}

fun elementOf(fake: dynamic): HTMLElement = fake.unsafeCast<HTMLElement>()
