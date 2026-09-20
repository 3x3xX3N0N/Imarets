package bar.verdantbloom.bloom.canvas2d

import bar.verdantbloom.bloom.api.BloomWorld
import bar.verdantbloom.bloom.api.RingIds
import bar.verdantbloom.bloom.api.TriadKind
import kotlin.math.max
import kotlin.math.min

/**
 * Everything the painter and the picker need for one frame, in flat preallocated arrays.
 *
 * Rings are sampled from the world, optionally displaced by the bell ripple, projected to CSS px, and
 * cut into CHUNKS of a few consecutive segments. Chunks (and spin discs) are the items of the painter's
 * algorithm: sorted far to near, so where two rings cross on screen the nearer one is painted last.
 * A chunk is short compared to a ring, so its mean depth is a good stand-in for the depth at a crossing.
 *
 * Item index layout: `ring * chunksPerRing + chunk` for ring chunks, then `discItemBase + slot` for discs.
 * [update] allocates nothing; only [configure] (segment count change) does.
 */
class Scene2d(segments: Int = DEFAULT_SEGMENTS, val maxDiscs: Int = MAX_DISCS) {
    var segments: Int = 0
        private set
    var chunkSize: Int = 0
        private set
    var chunksPerRing: Int = 0
        private set

    /** First item index that is a disc slot. */
    var discItemBase: Int = 0
        private set
    var itemCount: Int = 0
        private set

    /** Bloom-space samples, xyz, ring r starts at float index `r * segments * 3`. */
    var pts: FloatArray = FloatArray(0)
        private set

    /** Projected CSS px and camera depth, ring r starts at index `r * segments`. */
    var sx: FloatArray = FloatArray(0)
        private set
    var sy: FloatArray = FloatArray(0)
        private set
    var sd: FloatArray = FloatArray(0)
        private set

    /** Points the world wrote for each ring this frame (0 = ring disabled). */
    val ringPoints: IntArray = IntArray(RingIds.COUNT)
    val ringAlpha: DoubleArray = DoubleArray(RingIds.COUNT)

    var itemDepth: FloatArray = FloatArray(0)
        private set
    var itemVisible: BooleanArray = BooleanArray(0)
        private set

    /** Drawing order, far to near. Persistent between frames (see [DepthSort]). */
    var order: IntArray = IntArray(0)
        private set

    /**
     * True for a ring chunk that comes close to ANOTHER ring on screen, i.e. one that may be part of a crossing
     * and therefore needs its gap ("casing") cut. Everywhere else the casing stroke would erase nothing, and
     * skipping it saves roughly half of the fill work of a frame. Conservative: never false at a real crossing.
     */
    var itemCasing: BooleanArray = BooleanArray(0)
        private set

    // coarse screen grid of ring bit masks, and the cell rectangle each chunk touches
    private var grid = IntArray(0)
    private var gridW = 0
    private var gridH = 0
    private var cellX0 = IntArray(0)
    private var cellY0 = IntArray(0)
    private var cellX1 = IntArray(0)
    private var cellY1 = IntArray(0)

    // --- spin disc slots -------------------------------------------------------------------------
    var discCount: Int = 0
        private set

    /** Affine map disc-local bloom units -> CSS px: x' = a*x + c*y + e, y' = b*x + d*y + f. */
    val discA = DoubleArray(maxDiscs)
    val discB = DoubleArray(maxDiscs)
    val discC = DoubleArray(maxDiscs)
    val discD = DoubleArray(maxDiscs)
    val discE = DoubleArray(maxDiscs)
    val discF = DoubleArray(maxDiscs)
    val discRadius = DoubleArray(maxDiscs)
    val discAngle = DoubleArray(maxDiscs)
    val discAlpha = DoubleArray(maxDiscs)

    /** CSS px per bloom unit at the disc centre. */
    val discScale = DoubleArray(maxDiscs)
    val discTriad = IntArray(maxDiscs)
    val discFill = IntArray(maxDiscs)
    val discStroke = IntArray(maxDiscs)
    val discWedge = IntArray(maxDiscs)

    /** Frames built so far; 0 means [update] has never run and the arrays hold nothing useful. */
    var frames: Int = 0
        private set

    init {
        configure(segments)
    }

    /**
     * (Re)allocate for [newSegments] samples per ring cut into about [targetChunks] chunks. Not for the hot path.
     * More chunks = finer depth ordering at crossings, more stroke calls.
     */
    fun configure(newSegments: Int, targetChunks: Int = TARGET_CHUNKS_PER_RING) {
        val seg = newSegments.coerceIn(MIN_SEGMENTS, MAX_SEGMENTS)
        val size = maxOf(2, seg / targetChunks.coerceIn(4, 128))
        if (seg == segments && size == chunkSize) return
        segments = seg
        chunkSize = size
        chunksPerRing = (seg + chunkSize - 1) / chunkSize
        discItemBase = RingIds.COUNT * chunksPerRing
        itemCount = discItemBase + maxDiscs
        pts = FloatArray(RingIds.COUNT * seg * 3)
        sx = FloatArray(RingIds.COUNT * seg)
        sy = FloatArray(RingIds.COUNT * seg)
        sd = FloatArray(RingIds.COUNT * seg)
        itemDepth = FloatArray(itemCount)
        itemVisible = BooleanArray(itemCount)
        itemCasing = BooleanArray(itemCount)
        cellX0 = IntArray(itemCount)
        cellY0 = IntArray(itemCount)
        cellX1 = IntArray(itemCount)
        cellY1 = IntArray(itemCount)
        order = IntArray(itemCount)
        DepthSort.reset(order)
        for (i in 0 until RingIds.COUNT) ringPoints[i] = 0
        frames = 0
    }

    /**
     * Sample, ripple, project, chunk, sort. The caller has already advanced [world] and [Projector.set] the view.
     */
    fun update(world: BloomWorld, proj: Projector, ripples: RippleField, unixSeconds: Double) {
        val seg = segments
        val near = proj.near
        for (ring in 0 until RingIds.COUNT) {
            val base = ring * seg
            var n = world.sampleRing(ring, pts, base * 3, seg)
            if (n > seg) n = seg
            if (n < 2) n = 0
            ringPoints[ring] = n
            ringAlpha[ring] = if (n > 0) clamp01(world.ringAlpha(ring)) else 0.0
            if (n > 0) {
                if (ripples.active) ripples.displace(pts, base * 3, n)
                proj.projectArray(pts, base * 3, n, sx, sy, sd, base)
            }
            val itemBase = ring * chunksPerRing
            for (c in 0 until chunksPerRing) {
                val item = itemBase + c
                val start = c * chunkSize
                if (start >= n) {
                    itemVisible[item] = false
                    itemDepth[item] = HIDDEN_DEPTH
                    continue
                }
                var end = start + chunkSize
                if (end > n) end = n
                var sum = 0.0
                var ok = true
                for (k in start..end) {
                    val d = sd[base + (if (k == n) 0 else k)]
                    if (!(d > near)) ok = false
                    sum += d
                }
                itemVisible[item] = ok
                // two separate stores, not `if (ok) mean else Infinity`: see DepthSort for why that would box
                if (ok) itemDepth[item] = (sum / (end - start + 1)).toFloat() else itemDepth[item] = HIDDEN_DEPTH
            }
        }
        markCrossings(proj)
        updateDiscs(world, proj, unixSeconds)
        DepthSort.sortFarToNear(order, itemDepth, itemCount)
        frames++
    }

    /**
     * Fill [itemCasing]. Every visible chunk ORs its ring's bit into the grid cells its (padded) bounding box
     * touches; a chunk needs a casing when one of its cells also carries another ring's bit. Two padded boxes
     * that overlap always share a cell, so nothing that really crosses is missed. The ghost takes no part:
     * it cuts nothing and nothing is cut for it.
     */
    private fun markCrossings(proj: Projector) {
        var gw = (proj.width / GRID_CELL_PX).toInt() + 3
        var gh = (proj.height / GRID_CELL_PX).toInt() + 3
        if (gw > MAX_GRID) gw = MAX_GRID
        if (gh > MAX_GRID) gh = MAX_GRID
        if (gw != gridW || gh != gridH) {
            grid = IntArray(gw * gh) // only when the viewport changes
            gridW = gw
            gridH = gh
        } else {
            grid.fill(0)
        }
        val maxX = (gw - 1).toDouble()
        val maxY = (gh - 1).toDouble()
        val seg = segments
        for (ring in 0 until RingIds.COUNT) {
            val n = ringPoints[ring]
            val itemBase = ring * chunksPerRing
            if (ring == RingIds.GHOST || n == 0) {
                for (c in 0 until chunksPerRing) itemCasing[itemBase + c] = false
                continue
            }
            val bit = 1 shl ring
            val base = ring * seg
            for (c in 0 until chunksPerRing) {
                val item = itemBase + c
                itemCasing[item] = false
                if (!itemVisible[item]) continue
                val start = c * chunkSize
                var end = start + chunkSize
                if (end > n) end = n
                var x0 = Float.MAX_VALUE
                var y0 = Float.MAX_VALUE
                var x1 = -Float.MAX_VALUE
                var y1 = -Float.MAX_VALUE
                for (k in start..end) {
                    val i = base + (if (k == n) 0 else k)
                    val x = sx[i]
                    val y = sy[i]
                    if (x < x0) x0 = x
                    if (x > x1) x1 = x
                    if (y < y0) y0 = y
                    if (y > y1) y1 = y
                }
                // cell index = floor(px / cell) + 1: one border column / row catches everything off screen
                // clamped with min / max (pure float64 operations) rather than if-assignments of constants
                val cx0 = min(max((x0 - GRID_PAD_PX) / GRID_CELL_PX + 1.0, 0.0), maxX)
                val cy0 = min(max((y0 - GRID_PAD_PX) / GRID_CELL_PX + 1.0, 0.0), maxY)
                val cx1 = min(max((x1 + GRID_PAD_PX) / GRID_CELL_PX + 1.0, 0.0), maxX)
                val cy1 = min(max((y1 + GRID_PAD_PX) / GRID_CELL_PX + 1.0, 0.0), maxY)
                // Double -> Int through a typed-array store (the engine truncates natively; Kotlin/JS `toInt()` is a
                // stdlib call). The values are already clamped to 0..grid size, so truncation == floor.
                val ax0: dynamic = cellX0
                val ay0: dynamic = cellY0
                val ax1: dynamic = cellX1
                val ay1: dynamic = cellY1
                ax0[item] = cx0
                ay0[item] = cy0
                ax1[item] = cx1
                ay1[item] = cy1
                val ix0 = cellX0[item]
                val iy0 = cellY0[item]
                val ix1 = cellX1[item]
                val iy1 = cellY1[item]
                for (gy in iy0..iy1) {
                    val row = gy * gw
                    for (gx in ix0..ix1) grid[row + gx] = grid[row + gx] or bit
                }
            }
        }
        for (ring in 0 until RingIds.COUNT) {
            if (ring == RingIds.GHOST || ringPoints[ring] == 0) continue
            val others = (1 shl ring).inv()
            val itemBase = ring * chunksPerRing
            for (c in 0 until chunksPerRing) {
                val item = itemBase + c
                if (!itemVisible[item]) continue
                var hit = false
                var gy = cellY0[item]
                while (gy <= cellY1[item] && !hit) {
                    val row = gy * gw
                    var gx = cellX0[item]
                    while (gx <= cellX1[item]) {
                        if (grid[row + gx] and others != 0) {
                            hit = true
                            break
                        }
                        gx++
                    }
                    gy++
                }
                itemCasing[item] = hit
            }
        }
    }

    private fun updateDiscs(world: BloomWorld, proj: Projector, unixSeconds: Double) {
        val discs = world.spinDiscs
        val count = if (discs.size < maxDiscs) discs.size else maxDiscs
        discCount = count
        for (slot in 0 until maxDiscs) {
            val item = discItemBase + slot
            if (slot >= count) {
                itemVisible[item] = false
                itemDepth[item] = HIDDEN_DEPTH
                continue
            }
            val disc = discs[slot]
            val p = disc.position
            val visible = proj.project(p.x, p.y, p.z) && disc.alpha > 0.0
            if (!visible) {
                itemVisible[item] = false
                itemDepth[item] = HIDDEN_DEPTH
                continue
            }
            val k = proj.outScale
            // disc-local X and Y axes in bloom space: columns of the orientation's rotation matrix
            val q = disc.orientation
            val w = q.w
            val x = q.x
            val y = q.y
            val z = q.z
            val axX = 1 - 2 * (y * y + z * z)
            val axY = 2 * (x * y + w * z)
            val axZ = 2 * (x * z - w * y)
            val ayX = 2 * (x * y - w * z)
            val ayY = 1 - 2 * (x * x + z * z)
            val ayZ = 2 * (y * z + w * x)
            // into camera space (weak perspective: one scale for the whole disc), screen y grows down
            discA[slot] = k * (axX * proj.rx + axY * proj.ry + axZ * proj.rz)
            discB[slot] = -k * (axX * proj.ux + axY * proj.uy + axZ * proj.uz)
            discC[slot] = k * (ayX * proj.rx + ayY * proj.ry + ayZ * proj.rz)
            discD[slot] = -k * (ayX * proj.ux + ayY * proj.uy + ayZ * proj.uz)
            discE[slot] = proj.outX
            discF[slot] = proj.outY
            discScale[slot] = k
            discRadius[slot] = disc.radius
            discAngle[slot] = SpinDiscGeometry.angle(disc, unixSeconds)
            discAlpha[slot] = clamp01(disc.alpha)
            discTriad[slot] = if (disc.triad == TriadKind.THEME) TRIAD_THEME else TRIAD_CARRIED
            discFill[slot] = disc.discColor
            discStroke[slot] = disc.strokeColor
            discWedge[slot] = disc.wedgeColor
            itemVisible[item] = true
            itemDepth[item] = proj.outDepth.toFloat()
        }
    }

    private fun clamp01(v: Double): Double = if (v != v || v < 0.0) 0.0 else if (v > 1.0) 1.0 else v

    companion object {
        /** The rung's target load: 11 rings x 256 samples. */
        const val DEFAULT_SEGMENTS: Int = 256
        const val MIN_SEGMENTS: Int = 16
        const val MAX_SEGMENTS: Int = 1024
        const val TARGET_CHUNKS_PER_RING: Int = 32
        const val MAX_DISCS: Int = 8
        /** Screen grid for [itemCasing]: cell size, and the padding that covers half a casing plus half a line. */
        const val GRID_CELL_PX: Double = 24.0
        const val GRID_PAD_PX: Double = 13.0
        const val MAX_GRID: Int = 256
        const val TRIAD_CARRIED: Int = 0
        const val TRIAD_THEME: Int = 1

        /** Depth given to hidden items: they sort to the front of the far-to-near order and are skipped. */
        const val HIDDEN_DEPTH: Float = Float.POSITIVE_INFINITY
    }
}
