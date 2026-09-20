package bar.verdantbloom.bloom.api

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Ring ids. Model rings are 0..9 in pour-list order; the two extra rings follow. */
object RingIds {
    const val NONE: Int = -1

    /** The eleventh ring: the visitor (SPEC 3.3). */
    const val VISITOR: Int = 10

    /** Hidden ghost ring: the unperturbed world trajectory (SPEC 3.3 easter egg). */
    const val GHOST: Int = 11

    /** Size of any per-ring array (models + visitor + ghost). */
    const val COUNT: Int = 12
}

/**
 * One model = one Hopf fiber over a FIXED base point on S2.
 * @property index ring id, 0..9
 * @property id public model id, also the deep link `#pour/<id>`
 * @property orderCode short mono code shown at far zoom, e.g. W01
 * @property latitude base point latitude on S2, radians
 * @property longitude base point longitude on S2, radians
 */
data class ModelRing(
    val index: Int,
    val id: String,
    val orderCode: String,
    val shelf: Shelf,
    val latitude: Double,
    val longitude: Double,
    val guestTap: Boolean = false,
) {
    /** Base point (a, b, c) on S2. (Layout only, not inside the integrator, so trig is fine.) */
    val base: Vec3
        get() = Vec3(cos(latitude) * cos(longitude), cos(latitude) * sin(longitude), sin(latitude))
}

/** The ten pourable models in pour-list order (SPEC 3.6 / 5). `horror` is on order: no ring. */
object PourList {
    /** id to shelf, in ring-index order. */
    val MODELS: List<Pair<String, Shelf>> = listOf(
        "mini" to Shelf.WELL,
        "write" to Shelf.WELL,
        "vision" to Shelf.WELL,
        "dark" to Shelf.WELL,
        "fast" to Shelf.WELL,
        "reason" to Shelf.WELL,
        "standard" to Shelf.CALL,
        "creative" to Shelf.CALL,
        "coder" to Shelf.CALL,
        "flagship" to Shelf.TOP_SHELF,
    )
    const val GUEST_TAP_ID: String = "vision"
    const val ON_ORDER_ID: String = "horror"

    /** Order code = shelf initial + 2-digit position in shelf: W01..W06, C01..C03, T01. */
    fun rings(config: BloomConfig = BloomConfig()): List<ModelRing> {
        val perShelf = MODELS.groupBy { it.second }
        val seen = HashMap<Shelf, Int>()
        return MODELS.mapIndexed { i, (id, shelf) ->
            val k = seen[shelf] ?: 0
            seen[shelf] = k + 1
            val n = perShelf.getValue(shelf).size
            ModelRing(
                index = i,
                id = id,
                orderCode = shelf.label.substring(0, 1) + (k + 1).toString().padStart(2, '0'),
                shelf = shelf,
                latitude = config.shelfLatitudes.getValue(shelf),
                longitude = (config.shelfLongitudeOffsets[shelf] ?: 0.0) + 2.0 * PI * k / n,
                guestTap = id == GUEST_TAP_ID,
            )
        }
    }
}
