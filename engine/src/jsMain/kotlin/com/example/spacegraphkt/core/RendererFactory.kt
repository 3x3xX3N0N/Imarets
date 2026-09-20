package com.example.spacegraphkt.core

import bar.verdantbloom.three.THREE
import bar.verdantbloom.three.jsObject
import kotlinx.browser.window
import org.w3c.dom.HTMLCanvasElement
import kotlin.js.Promise

/** The two GPU rungs of SPEC section 4. The 2d rung is not the engine's business. */
enum class GpuRung(val queryValue: String) {
    WEBGPU("webgpu"),
    WEBGL("webgl");

    companion object {
        fun fromQuery(value: String?, default: GpuRung = WEBGL): GpuRung =
            entries.firstOrNull { it.queryValue == value?.trim()?.lowercase() } ?: default
    }
}

/**
 * Outcome of bringing a GPU renderer up. Never an exception: a rung that cannot start is `ok = false`, which is
 * the clean "fall to the next rung" signal.
 *
 * @property backend "webgpu", "webgl2" or "none"
 * @property fellBack true when WEBGPU was requested but three started its WebGL2 backend instead (no adapter).
 *   That is still `ok = true`: there is a working renderer and ONE code path, it just is not WebGPU.
 */
class GpuInitResult(
    val ok: Boolean,
    val requested: GpuRung,
    val backend: String,
    val fellBack: Boolean = false,
    val reason: String? = null,
    val error: Throwable? = null,
) {
    override fun toString(): String =
        "GpuInitResult(ok=$ok, requested=${requested.queryValue}, backend=$backend, fellBack=$fellBack, reason=$reason)"
}

/**
 * Renderer factory (SPEC 4): ALWAYS three's `WebGPURenderer`; the webgl rung is the same class with
 * `forceWebGL = true`, so there is one code path and one copy of three (`three/webgpu`, see CONTRACTS 4).
 */
object RendererFactory {
    /** How long `init()` may take before the rung is declared dead (a lost adapter can leave the promise pending). */
    var initTimeoutMs: Int = 10_000

    fun webGpuAvailable(): Boolean = window.navigator.asDynamic().gpu != null

    /**
     * Constructs (does not initialise) a renderer on [canvas]. @return null when three throws in the constructor.
     * The caller must not draw before [init] resolved with `ok = true`.
     */
    fun create(canvas: HTMLCanvasElement, rung: GpuRung, antialias: Boolean = true, alpha: Boolean = true): THREE.WebGPURenderer? =
        try {
            THREE.WebGPURenderer(jsObject {
                this.canvas = canvas
                this.antialias = antialias
                this.alpha = alpha
                this.forceWebGL = rung == GpuRung.WEBGL
            })
        } catch (e: Throwable) {
            console.warn("RendererFactory: could not construct the ${rung.queryValue} renderer", e)
            null
        }

    /** Awaits `renderer.init()`. Resolves (never rejects) with what actually started. */
    fun init(renderer: THREE.WebGPURenderer?, rung: GpuRung): Promise<GpuInitResult> = Promise { resolve, _ ->
        if (renderer == null) {
            resolve(GpuInitResult(false, rung, "none", reason = "renderer could not be constructed"))
            return@Promise
        }
        var settled = false
        val timer = window.setTimeout({
            if (!settled) {
                settled = true
                resolve(GpuInitResult(false, rung, "none", reason = "init timed out after $initTimeoutMs ms"))
            }
        }, initTimeoutMs)
        val started: Promise<dynamic> = try {
            renderer.init()
        } catch (e: Throwable) {
            Promise.reject(e)
        }
        started.then<Unit>(
            { _: dynamic ->
                if (!settled) {
                    settled = true
                    window.clearTimeout(timer)
                    val isWebGpu = renderer.backend != null && renderer.backend.isWebGPUBackend == true
                    resolve(GpuInitResult(true, rung, if (isWebGpu) "webgpu" else "webgl2", fellBack = rung == GpuRung.WEBGPU && !isWebGpu))
                }
            },
            { err: Throwable ->
                if (!settled) {
                    settled = true
                    window.clearTimeout(timer)
                    resolve(GpuInitResult(false, rung, "none", reason = "init rejected: ${err.asDynamic().message ?: err}", error = err))
                }
            },
        )
    }
}
