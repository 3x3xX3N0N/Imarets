package bar.verdantbloom.bloom.three

import bar.verdantbloom.bloom.api.BloomWorld
import bar.verdantbloom.bloom.api.CardAnchor
import bar.verdantbloom.bloom.api.Palette
import bar.verdantbloom.bloom.api.RingIds
import bar.verdantbloom.bloom.api.Shelf
import bar.verdantbloom.bloom.api.Vec3
import bar.verdantbloom.three.THREE
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.tan

/** Knobs of bloom-three that are not part of bloom-api. All have working defaults. */
data class BloomThreeOptions(
    /** Points per ring; null = world.config.fiberSegments. */
    val segments: Int? = null,
    /** MSAA on the renderer (standalone mode only). */
    val antialias: Boolean = true,
    val anchorMode: AnchorMode = AnchorMode.CAMERA_FACING,
    /** Clear gap either side of the NEAR thread where two fibers cross (px), so over/under is readable; 0 = off. */
    val casingPx: Double = 1.6,
    /** Louche at start, 0..1. */
    val louche: Double = 0.35,
    /** Spin disc pool size; null = world.config.spinDiscMax + 3. */
    val discCapacity: Int? = null,
)

/** Bloom-space position of a ring's card anchor, for callers that fly the camera to a card. */
interface BloomAnchorSource {
    fun anchorPoint(ringId: Int): Vec3
}

/**
 * Every scene object of the bloom under ONE [root] group, with no renderer, canvas or camera of its own.
 *
 * Standalone: [ThreeBloomRenderer] owns a WebGPURenderer and calls [update] with a projector set from the
 * BloomView. Embedded: a host that owns renderer, scene, camera and frame loop (the engine) adds [root] to
 * its scene and calls [updateFromCamera] once per frame BEFORE it renders.
 */
class BloomSceneGraph(
    private val world: BloomWorld,
    palette: Palette,
    private val options: BloomThreeOptions = BloomThreeOptions(),
) : BloomAnchorSource {
    val root = THREE.Group()
    val projector = Projector()

    val segments: Int = (options.segments ?: world.config.fiberSegments).coerceAtLeast(RibbonBuffers.MIN_SEGMENTS)
    val buffers = RibbonBuffers(RingIds.COUNT, segments)
    val ripples = RippleField()

    private val rings = RingLayer(buffers)
    private val veil = VeilLayer(rings.glowColor)
    private val discs = SpinDiscLayer(options.discCapacity ?: (world.config.spinDiscMax + 3))
    private val anchors = AnchorTracker(RingIds.COUNT, segments)

    private val styles = Array(RingIds.COUNT) { RingStyle() }
    private val ringHex = IntArray(RingIds.COUNT)
    private val pickable = BooleanArray(RingIds.COUNT) { it != RingIds.GHOST }
    private val highlightLevel = DoubleArray(RingIds.COUNT)
    private val home = IntArray(RingIds.COUNT) { -1 }

    private var palette: Palette = palette
    private var louche = options.louche.coerceIn(0.0, 1.0)
    private var highlight = RingIds.NONE
    private var frameCount = 0
    private var disposed = false

    // scratch for updateFromCamera
    private val tmpMatrix = THREE.Matrix4()
    private val tmpA = THREE.Vector3()
    private val tmpB = THREE.Vector3()
    private val tmpC = THREE.Vector3()
    private val tmpD = THREE.Vector3()

    /** Spin discs drawn in the last update. */
    val liveDiscCount: Int get() = discs.liveCount

    /** Ring colours actually used (0xRRGGBB), by ring id: the shelf colour with a small per-ring tint. */
    fun ringColorHex(ringId: Int): Int = if (ringId in 0 until RingIds.COUNT) ringHex[ringId] else palette.ink

    init {
        root.name = "vb-bloom"
        root.add(veil.mesh, rings.casingMesh, rings.haloMesh, discs.group, rings.coreMesh)
        rings.setCasing(options.casingPx.coerceIn(0.0, 6.0))
        for (ring in 0 until RingIds.COUNT) buffers.hideRing(ring)
        applyPalette()
    }

    // ------------------------------------------------------------------ look

    fun setPalette(palette: Palette) {
        this.palette = palette
        applyPalette()
    }

    fun setLouche(amount: Double) {
        louche = if (amount.isFinite()) amount.coerceIn(0.0, 1.0) else louche
    }

    fun setHighlight(ringId: Int) {
        highlight = if (ringId in 0 until RingIds.COUNT && ringId != RingIds.GHOST) ringId else RingIds.NONE
    }

    fun ripple(magnitude: Double) = ripples.kick(magnitude)

    private fun applyPalette() {
        val p = palette
        rings.glowColor.value.setHex(p.glow)
        rings.groundColor.value.setHex(p.ground)
        rings.setNight(p.night)
        veil.setNight(p.night)

        // shelf-tinted colours: the shelf colour, nudged towards the glow per position in the shelf so that the
        // six WELL rings are not one flat colour; the guest tap leans to accent-2.
        val models = world.rings
        var wellSeen = 0; var callSeen = 0; var topSeen = 0
        for (ring in 0 until RingIds.COUNT) {
            var hex = p.ringColor(ring, models)
            if (ring < models.size && ring < RingIds.VISITOR) {
                val model = models[ring]
                val shelfSize = models.count { it.shelf == model.shelf }
                val k = when (model.shelf) {
                    Shelf.WELL -> wellSeen++
                    Shelf.CALL -> callSeen++
                    Shelf.TOP_SHELF -> topSeen++
                }
                val t = if (shelfSize > 1) k.toDouble() / (shelfSize - 1) else 0.0
                hex = ColorMath.mixHex(hex, p.glow, SHELF_TINT * t)
                if (model.guestTap) hex = ColorMath.mixHex(hex, p.accent2, GUEST_TINT)
            }
            ringHex[ring] = hex
            val s = styles[ring]
            s.r = ColorMath.srgbToLinear(ColorMath.red(hex))
            s.g = ColorMath.srgbToLinear(ColorMath.green(hex))
            s.b = ColorMath.srgbToLinear(ColorMath.blue(hex))
            // "hotter" = lighter on the night ground, stronger ink on the paper ground
            val hot = if (p.night) ColorMath.mixHex(p.glow, 0xffffff, 0.6) else p.ink
            s.hotR = ColorMath.srgbToLinear(ColorMath.red(hot))
            s.hotG = ColorMath.srgbToLinear(ColorMath.green(hot))
            s.hotB = ColorMath.srgbToLinear(ColorMath.blue(hot))
        }
    }

    private fun restyle(dtSeconds: Double) {
        val ease = 1.0 - exp(-HIGHLIGHT_RATE * dtSeconds.coerceIn(0.0, 0.25))
        val night = palette.night
        rings.haloGain.value = if (night) 0.26 + 0.60 * louche else 0.14 + 0.40 * louche
        rings.glowMix.value = 0.30 + 0.45 * louche
        val haloBase = HALO_MIN_PX + HALO_LOUCHE_PX * louche
        for (ring in 0 until RingIds.COUNT) {
            val goal = if (ring == highlight) 1.0 else 0.0
            highlightLevel[ring] += (goal - highlightLevel[ring]) * ease
            val h = highlightLevel[ring]
            val s = styles[ring]
            when (ring) {
                RingIds.VISITOR -> { // distinct, brighter, slightly thicker
                    s.coreHalfPx = 1.8; s.haloHalfPx = haloBase * 1.35; s.alphaMul = 1.0
                    s.haloBoost = 1.7; s.dashCount = 0.0; s.heat = if (night) 0.25 else 0.0
                }
                RingIds.GHOST -> { // faint, dashed
                    s.coreHalfPx = 0.8; s.haloHalfPx = haloBase * 0.6; s.alphaMul = 0.45
                    s.haloBoost = 0.5; s.dashCount = GHOST_DASHES; s.heat = 0.0
                }
                else -> {
                    s.coreHalfPx = 1.15; s.haloHalfPx = haloBase; s.alphaMul = 1.0
                    s.haloBoost = 1.0; s.dashCount = 0.0; s.heat = 0.0
                }
            }
            s.coreHalfPx *= 1.0 + 0.7 * h
            s.haloHalfPx *= 1.0 + 0.5 * h
            s.haloBoost *= 1.0 + 0.8 * h
            s.heat = (s.heat + 0.55 * h).coerceIn(0.0, 1.0)
        }
    }

    // ------------------------------------------------------------------ frame

    /**
     * Embedded mode. [camera] belongs to the host; [root] may sit anywhere in the host scene with any uniform
     * scale. The camera pose is brought into bloom space, then [update] runs. [cssWidth]/[cssHeight] are the
     * size of the host renderer's viewport in CSS px.
     */
    fun updateFromCamera(camera: THREE.PerspectiveCamera, cssWidth: Int, cssHeight: Int, unixSeconds: Double, dtSeconds: Double) {
        if (disposed || cssWidth <= 0 || cssHeight <= 0) return
        root.updateWorldMatrix(true, false)
        camera.updateWorldMatrix(true, false)
        tmpMatrix.copy(root.matrixWorld).invert()
        val cam = camera.matrixWorld
        tmpA.setFromMatrixPosition(cam).applyMatrix4(tmpMatrix)
        tmpB.setFromMatrixColumn(cam, 0).transformDirection(tmpMatrix)
        tmpC.setFromMatrixColumn(cam, 1).transformDirection(tmpMatrix)
        tmpD.setFromMatrixColumn(cam, 2).transformDirection(tmpMatrix).negate()
        val zoom = camera.zoom.toDouble().let { if (it > 0.0) it else 1.0 }
        val tanHalf = tan(camera.fov.toDouble() * PI / 360.0) / zoom
        projector.setViewport(cssWidth.toDouble(), cssHeight.toDouble())
        // focus = depth of the bloom origin, so the depth cue is centred on the bloom
        val focus = -(tmpA.x * tmpD.x + tmpA.y * tmpD.y + tmpA.z * tmpD.z)
        projector.setFromBasis(
            tmpA.x, tmpA.y, tmpA.z,
            tmpB.x, tmpB.y, tmpB.z,
            tmpC.x, tmpC.y, tmpC.z,
            tmpD.x, tmpD.y, tmpD.z,
            tanHalf, focus,
        )
        update(unixSeconds, dtSeconds)
    }

    /**
     * Re-sample every ring from the world into the shared buffers, rebuild ribbons, anchors and discs.
     * [projector] must already hold this frame's camera and viewport. The caller has done world.advanceTo.
     */
    fun update(unixSeconds: Double, dtSeconds: Double) {
        if (disposed) return
        val dt = if (dtSeconds.isFinite() && dtSeconds > 0.0) dtSeconds else 0.0
        frameCount++
        ripples.advance(dt)
        restyle(dt)

        val cfg = world.config
        val displacement = RIPPLE_DISPLACEMENT * (if (world.reducedMotion) REDUCED_MOTION_RIPPLE else 1.0)
        var ghostDrawn = false
        for (ring in 0 until RingIds.COUNT) {
            val written = world.sampleRing(ring, buffers.centers, buffers.centerOffset(ring), segments)
            if (!buffers.normalizeCount(ring, written)) {
                if (buffers.enabled[ring]) {
                    buffers.hideRing(ring)
                    anchors.reset(ring)
                }
                continue
            }
            buffers.buildRing(
                ring, styles[ring], world.ringAlpha(ring), projector, ripples, ring,
                displacement, cfg.fadeStartRadius, cfg.maxRadius,
            )
            if (!buffers.enabled[ring]) {
                anchors.reset(ring)
                continue
            }
            if (ring == RingIds.GHOST) ghostDrawn = true
            if (options.anchorMode == AnchorMode.CAMERA_FACING) {
                if (home[ring] < 0 || (frameCount + ring) % HOME_REFRESH_FRAMES == 0) home[ring] = nearestSample(ring, world.ringAnchor(ring))
                anchors.update(
                    ring, home[ring], buffers.screen, buffers.fade,
                    projector.width, projector.height, projector.near, ANCHOR_EDGE_MARGIN_PX, dt,
                )
            }
        }
        // the ghost is the LAST slot: when it is off it is simply left out of the draw range
        rings.flush(if (ghostDrawn) RingIds.COUNT else RingIds.GHOST)

        discs.update(world.spinDiscs, unixSeconds, palette, projector, cfg.fadeStartRadius, cfg.maxRadius)
        val veilGain = louche * louche * (if (palette.night) VEIL_NIGHT else VEIL_DAY)
        veil.update(projector, cfg.fadeStartRadius * 1.5, veilGain)
    }

    private fun nearestSample(ring: Int, point: Vec3): Int {
        val c = buffers.centers
        val c0 = buffers.centerOffset(ring)
        var best = 0
        var bestD = Double.MAX_VALUE
        for (i in 0 until segments) {
            val dx = c[c0 + i * 3] - point.x
            val dy = c[c0 + i * 3 + 1] - point.y
            val dz = c[c0 + i * 3 + 2] - point.z
            val d = dx * dx + dy * dy + dz * dz
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return best
    }

    // ------------------------------------------------------------------ queries

    /** Ring id under ([screenX], [screenY]) (CSS px in the viewport given to the projector) or RingIds.NONE. */
    fun pick(screenX: Double, screenY: Double, slopPx: Double = 12.0): Int =
        if (disposed) RingIds.NONE else RingPicker.pick(
            screenX, screenY, slopPx, buffers.screen, buffers.fade, buffers.enabled, pickable,
            RingIds.COUNT, segments, projector.near,
        )

    override fun anchorPoint(ringId: Int): Vec3 {
        if (ringId !in 0 until RingIds.COUNT) return Vec3.ZERO
        if (options.anchorMode == AnchorMode.WORLD || !buffers.enabled[ringId]) return world.ringAnchor(ringId)
        return Vec3(
            anchors.sample(ringId, buffers.centers, 3, 0),
            anchors.sample(ringId, buffers.centers, 3, 1),
            anchors.sample(ringId, buffers.centers, 3, 2),
        )
    }

    fun cardAnchor(ringId: Int): CardAnchor {
        if (disposed || ringId !in 0 until RingIds.COUNT || !buffers.enabled[ringId]) {
            return CardAnchor(ringId, 0.0, 0.0, 0.0, 0.0, 0.0, false)
        }
        val p = anchorPoint(ringId)
        val alpha = if (options.anchorMode == AnchorMode.WORLD) world.ringAlpha(ringId) else anchors.sample(ringId, buffers.fade, 1, 0)
        return projected(ringId, p, alpha.coerceIn(0.0, 1.0))
    }

    fun project(point: Vec3): CardAnchor = projected(RingIds.NONE, point, 1.0)

    private fun projected(ringId: Int, p: Vec3, alpha: Double): CardAnchor {
        val front = projector.project(p.x, p.y, p.z)
        val x = projector.outX
        val y = projector.outY
        val onScreen = x >= -CARD_MARGIN_PX && x <= projector.width + CARD_MARGIN_PX &&
            y >= -CARD_MARGIN_PX && y <= projector.height + CARD_MARGIN_PX
        return CardAnchor(
            ringId = ringId,
            x = x,
            y = y,
            pxPerUnit = projector.outPxPerUnit,
            depth = projector.outDepth,
            alpha = alpha,
            visible = front && onScreen && alpha > MIN_VISIBLE_ALPHA,
        )
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        root.removeFromParent()
        rings.dispose()
        veil.dispose()
        discs.dispose()
    }

    companion object {
        const val SHELF_TINT = 0.28
        const val GUEST_TINT = 0.4
        const val HALO_MIN_PX = 5.0
        const val HALO_LOUCHE_PX = 13.0
        const val GHOST_DASHES = 48.0
        const val HIGHLIGHT_RATE = 9.0
        const val RIPPLE_DISPLACEMENT = 0.045
        const val REDUCED_MOTION_RIPPLE = 0.25
        const val HOME_REFRESH_FRAMES = 30
        const val ANCHOR_EDGE_MARGIN_PX = 24.0
        const val CARD_MARGIN_PX = 200.0
        const val MIN_VISIBLE_ALPHA = 0.02
        const val VEIL_NIGHT = 0.11
        const val VEIL_DAY = 0.22
    }
}
