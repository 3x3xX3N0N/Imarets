package bar.verdantbloom.bloom.three

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Colour helpers. Palette values are 0xRRGGBB in sRGB; vertex attributes and TSL uniforms are in three's
 * LINEAR working space (the renderer encodes to sRGB on output), so everything written to a buffer goes
 * through [srgbToLinear] - the same transfer function three applies in Color.setHex.
 */
object ColorMath {
    fun srgbToLinear(c: Double): Double =
        if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    fun red(hex: Int): Double = ((hex shr 16) and 0xff) / 255.0
    fun green(hex: Int): Double = ((hex shr 8) and 0xff) / 255.0
    fun blue(hex: Int): Double = (hex and 0xff) / 255.0

    /** Mix two 0xRRGGBB colours in sRGB space, t = 0 gives [a]. */
    fun mixHex(a: Int, b: Int, t: Double): Int {
        val k = t.coerceIn(0.0, 1.0)
        fun ch(shift: Int): Int {
            val x = (a shr shift) and 0xff
            val y = (b shr shift) and 0xff
            return (x + (y - x) * k).roundToInt().coerceIn(0, 255)
        }
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Relative luminance (linear) of an sRGB hex colour, 0..1. */
    fun luminance(hex: Int): Double =
        0.2126 * srgbToLinear(red(hex)) + 0.7152 * srgbToLinear(green(hex)) + 0.0722 * srgbToLinear(blue(hex))
}
