package bar.verdantbloom.bloom.canvas2d

import bar.verdantbloom.bloom.api.BloomConfig
import bar.verdantbloom.bloom.api.BloomView
import bar.verdantbloom.bloom.api.BloomWorld
import bar.verdantbloom.bloom.api.LorenzState
import bar.verdantbloom.bloom.api.ModelRing
import bar.verdantbloom.bloom.api.Palettes
import bar.verdantbloom.bloom.api.PerturbKind
import bar.verdantbloom.bloom.api.PourList
import bar.verdantbloom.bloom.api.Quat
import bar.verdantbloom.bloom.api.RingIds
import bar.verdantbloom.bloom.api.SpinDisc
import bar.verdantbloom.bloom.api.TriadKind
import bar.verdantbloom.bloom.api.Vec3
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * "No per-frame allocation in the hot path", MEASURED rather than asserted by reading the code: sample + ripple +
 * project + chunk + crossing grid + sort + paint + pick of 12 rings x 256 samples, thousands of frames, on the V8
 * that runs the tests. The world and the 2D context used here allocate nothing themselves, so what is
 * counted is bloom-2d's. This catches more than Kotlin objects: it caught V8 boxing doubles (see DepthSort).
 */
class HotPathAllocationTest {
    /** Twelve tilted circles written straight into the float array: no Vec3, no lists per call. */
    private class QuietWorld : BloomWorld {
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
        override var ghostEnabled = true
        override fun perturb(kind: PerturbKind, magnitude: Double) = Unit
        override fun sampleRing(ringId: Int, out: FloatArray, offset: Int, segments: Int): Int {
            val r = 0.8 + 0.17 * ringId
            val tilt = 0.26 * ringId + unixSeconds * 0.02
            val ct = cos(tilt)
            val st = sin(tilt)
            var o = offset
            for (i in 0 until segments) {
                val a = 6.283185307179586 * i / segments
                val x = r * cos(a)
                val y = r * sin(a)
                out[o] = x.toFloat()
                out[o + 1] = (y * ct).toFloat()
                out[o + 2] = (y * st).toFloat()
                o += 3
            }
            return segments
        }

        override fun ringAlpha(ringId: Int): Double = 1.0
        private val anchor = Vec3(1.0, 0.0, 0.0)
        override fun ringAnchor(ringId: Int): Vec3 = anchor
        override val spinDiscs: List<SpinDisc> = List(4) { i ->
            SpinDisc(
                i, Vec3(0.4 * i - 0.8, 0.3, 0.5), Quat.IDENTITY, 0.128, (7 + i) * 29.6, 0.0, 0.0, 1.0,
                TriadKind.entries[i % 3], 0x2040ff, 0xff2020, 0xffe020, i,
            )
        }
        override val lobeSwitchCount = 0
    }

    private fun quietContext(): dynamic = js(
        """({
            globalAlpha: 1, globalCompositeOperation: 'source-over', lineWidth: 1, lineCap: 'butt', lineJoin: 'miter',
            strokeStyle: '#000', fillStyle: '#000', shadowBlur: 0, shadowColor: '', imageSmoothingEnabled: true, n: 0,
            setTransform: function () {}, clearRect: function () {}, beginPath: function () {}, closePath: function () {},
            moveTo: function () { this.n++; }, lineTo: function () { this.n++; }, arc: function () {}, stroke: function () { this.n++; },
            fill: function () {}, fillRect: function () {}, setLineDash: function () {}, drawImage: function () {},
            createRadialGradient: function () { return { addColorStop: function () {} }; }
        })""",
    )

    private fun heapUsed(): Double = js("process.memoryUsage().heapUsed").unsafeCast<Double>()

    @Test
    fun thousandsOfFramesLeaveTheHeapFlat() {
        val world = QuietWorld()
        val proj = Projector()
        val scene = Scene2d(256)
        val ripples = RippleField()
        val painter = Painter2d()
        painter.setPalette(Palettes.ABSINTHE_ABYSS, world.rings)
        painter.louche = 0.8
        painter.highlight = 4
        val ctx = ctxOf(quietContext())
        val layerCanvas: dynamic = js("({ width: 0, height: 0 })")
        val layer = GlowLayer(layerCanvas.unsafeCast<org.w3c.dom.HTMLCanvasElement>(), ctxOf(quietContext()))
        layer.resize(1280, 720)
        val view = BloomView(orbit = QuatMath.axisAngle(1.0, 0.3, 0.0, 0.7), distance = 7.0)
        var t = 1_700_000_000.0

        fun frame() {
            t += 1.0 / 60.0
            world.advanceTo(t)
            proj.set(view, 1280.0, 720.0)
            ripples.advance(1.0 / 60.0)
            scene.update(world, proj, ripples, t)
            painter.paint(ctx, scene, proj, 2.0, Quality2d.STANDARD, t, ripples.glow, layer)
            Picker.pick(scene, proj.near, 640.0, 360.0, 12.0)
        }

        // Bytes allocated per frame = sum of the upward steps of heapUsed, sampled every 10 frames. (A scavenge in
        // between makes a step negative and is skipped, so this under-counts a little; it is a rate, not an audit.)
        fun bytesPerFrame(frames: Int): Double {
            var last = heapUsed()
            var allocated = 0.0
            repeat(frames / 10) {
                repeat(10) { frame() }
                val now = heapUsed()
                if (now > last) allocated += now - last
                last = now
            }
            return allocated / frames
        }

        ripples.kick(1.0) // the ripple path is part of the hot path too
        repeat(600) { frame() } // warm-up: JIT tiers, inline caches, first-use strings
        val quiet = bytesPerFrame(3000)
        ripples.kick(0.8)
        val ringing = bytesPerFrame(200) // 3.3 s with the bell ringing: displacement active on every sample
        println("bloom-2d hot path: " + quiet.toInt() + " B/frame steady, " + ringing.toInt() + " B/frame while rippling")

        // Scale: the measuring itself costs 100-250 B/frame (process.memoryUsage allocates). One stray 12-byte heap
        // number per CHUNK would be 4.6 KB/frame, one per SAMPLE 37 KB/frame; before the fixes noted in DepthSort
        // this test read 18.7 KB/frame.
        assertTrue(quiet < 1500.0, "steady frames allocate ${quiet.toInt()} B each")
        assertTrue(ringing < 3000.0, "rippling frames allocate ${ringing.toInt()} B each")
    }
}
