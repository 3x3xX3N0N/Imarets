package com.example.spacegraphkt.external

import kotlin.js.Date
import kotlin.random.Random

// Engine-local helpers that used to live in threejs_interop.kt. The three.js externals themselves
// moved to the :three-externals module (package bar.verdantbloom.three).

const val DEG2RAD_KT: Double = kotlin.math.PI / 180.0

fun generateId(prefix: String = "id-kt"): String {
    val randomPart = Random.nextInt(1_000_000)
    return "$prefix-${Date.now().toLong()}-$randomPart"
}

fun isNaN_KT(value: Any?): Boolean {
    if (value is Number) return value.toDouble().isNaN()
    return true
}
