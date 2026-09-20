package bar.verdantbloom.site

import bar.verdantbloom.bloom.api.BloomWorld
import bar.verdantbloom.bloom.api.PerturbKind
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import kotlin.math.abs

/** Day / night by the visitor's LOCAL clock (night 19:00-07:00), with the HUD switch as the override. */
internal class ThemeControl(private val onChanged: () -> Unit) {
    private var override: Boolean? = null

    private fun nightByClock(): Boolean {
        val hour = kotlin.js.Date().getHours()
        return hour >= 19 || hour < 7
    }

    val night: Boolean get() = override ?: nightByClock()

    fun apply() {
        val theme = if (night) "absinthe-abyss" else "nightshade-herbarium"
        val root = document.documentElement ?: return
        if (root.getAttribute("data-theme") != theme) {
            root.setAttribute("data-theme", theme)
            document.first("meta[name=\"theme-color\"]")?.setAttribute("content", if (night) "#020a07" else "#efe8d8")
            onChanged()
        }
    }

    fun setOverride(nightWanted: Boolean?) {
        override = nightWanted
        apply()
    }

    /** Re-check the clock now and then so a page left open crosses 07:00 / 19:00 by itself. */
    fun watchClock(onTick: () -> Unit) {
        window.setInterval({
            if (override == null) {
                apply()
                onTick()
            }
        }, 60_000)
    }
}

/** The controls HUD: three real knobs, the service bell, the switches (DOM-CONTRACT 3.5). Nothing here is inert. */
internal class Hud(
    private val world: BloomWorld,
    private val stage: BloomStage,
    private val theme: ThemeControl,
    private val status: StatusBoard,
    private val resetVisitor: () -> Unit,
) {
    private val rho = byId("knob-rho") as? HTMLInputElement
    private val drift = byId("knob-drift") as? HTMLInputElement
    private val loucheKnob = byId("knob-louche") as? HTMLInputElement
    private val swDayNight = byId("sw-daynight") as? HTMLInputElement
    private val swTilt = byId("sw-tilt") as? HTMLInputElement
    private val swSlow = byId("sw-slow") as? HTMLInputElement
    private val unmarked = (1..4).mapNotNull { byId("sw-u$it") as? HTMLInputElement }

    private var tiltListener: ((Event) -> Unit)? = null
    private var lastBeta = 0.0
    private var lastGamma = 0.0

    fun wire() {
        wirePanel()
        wireKnob(rho, 1) { world.rho = it }
        wireKnob(drift, 2) { world.driftRate = it }
        wireKnob(loucheKnob, 2) { stage.setLouche(it) }
        wireBell()
        wireSwitches()
        byId("hud-reset")?.addEventListener("click", { reset() })
    }

    private fun wirePanel() {
        val toggle = byId("hud-toggle") ?: return
        val panel = byId("hud-panel") ?: return
        val hud = byId("hud")
        toggle.addEventListener("click", {
            val open = panel.hidden
            panel.hidden = !open
            toggle.setAttribute("aria-expanded", if (open) "true" else "false")
            hud?.setAttribute("data-open", if (open) "true" else "false")
        })
    }

    private fun paintKnob(input: HTMLInputElement, decimals: Int) {
        val v = input.value.toDoubleOrNull() ?: return
        val lo = input.min.toDoubleOrNull() ?: 0.0
        val hi = input.max.toDoubleOrNull() ?: 1.0
        val turn = if (hi > lo) ((v - lo) / (hi - lo)).coerceIn(0.0, 1.0) else 0.0
        (input.closest(".vb-knob") as? org.w3c.dom.HTMLElement)?.style?.setProperty("--vb-knob-turn", turn.asDynamic().toFixed(3) as String)
        setText(byId("${input.id}-out"), v.asDynamic().toFixed(decimals) as String)
    }

    private fun wireKnob(input: HTMLInputElement?, decimals: Int, set: (Double) -> Unit) {
        input ?: return
        var last = input.value.toDoubleOrNull() ?: 0.0
        paintKnob(input, decimals)
        input.addEventListener("input", {
            val v = input.value.toDoubleOrNull()
            if (v != null) {
                set(v)
                val span = (input.max.toDoubleOrNull() ?: 1.0) - (input.min.toDoubleOrNull() ?: 0.0)
                world.perturb(PerturbKind.KNOB, if (span > 0.0) abs(v - last) / span else 0.0)
                last = v
                paintKnob(input, decimals)
            }
        })
    }

    private fun wireBell() {
        val bell = byId("bell") ?: return
        val out = byId("bell-status")
        bell.addEventListener("click", {
            if (bell.getAttribute("data-state") != "ringing") {
                bell.setAttribute("data-state", "ringing")
                setText(out, bell.data("msg-ringing") ?: "")
                status.refresh { latencyMs ->
                    bell.setAttribute("data-state", "idle")
                    if (latencyMs >= 0.0) {
                        // reply latency = kick size: 1 s reaches the world's clamp; the ripple shows the same number
                        world.perturb(PerturbKind.BELL, latencyMs)
                        stage.ripple((latencyMs / 1000.0).coerceIn(0.05, 1.0))
                        setText(out, (bell.data("msg-answered") ?: "").replace("{ms}", latencyMs.toInt().toString()))
                    } else {
                        world.perturb(PerturbKind.BELL, 1000.0)
                        stage.ripple(1.0)
                        setText(out, bell.data("msg-no-answer") ?: "")
                    }
                }
            }
        })
    }

    private fun applySlow() {
        val on = swSlow?.checked == true
        world.reducedMotion = on
        stage.reducedMotion = on
        val root = document.documentElement
        if (on) root?.setAttribute("data-reduced-motion", "on") else root?.removeAttribute("data-reduced-motion")
    }

    private fun wireSwitches() {
        swDayNight?.checked = theme.night
        swDayNight?.addEventListener("change", {
            flip()
            theme.setOverride(swDayNight.checked)
        })
        swSlow?.checked = prefersReducedMotion()
        applySlow()
        swSlow?.addEventListener("change", {
            flip()
            applySlow()
        })
        swTilt?.addEventListener("change", {
            flip()
            if (swTilt.checked) startTilt() else stopTilt()
        })
        for ((index, sw) in unmarked.withIndex()) {
            sw.addEventListener("change", {
                flip()
                if (index == GHOST_INDEX) world.ghostEnabled = sw.checked
            })
        }
    }

    /** Every flip of every switch injects the fixed epsilon (SPEC 3.3). */
    private fun flip() = world.perturb(PerturbKind.SWITCH, 1.0)

    private fun startTilt() {
        val begin = {
            val listener: (Event) -> Unit = { e ->
                val d = e.asDynamic()
                val beta = (d.beta as? Double) ?: 0.0
                val gamma = (d.gamma as? Double) ?: 0.0
                val moved = abs(beta - lastBeta) + abs(gamma - lastGamma)
                lastBeta = beta
                lastGamma = gamma
                if (moved > 0.05) world.perturb(PerturbKind.TILT, moved)
            }
            tiltListener = listener
            window.addEventListener("deviceorientation", listener)
        }
        // iOS wants a permission prompt from a user gesture; this runs inside the switch's change event
        val ctor: dynamic = window.asDynamic().DeviceOrientationEvent
        if (ctor != null && jsTypeOf(ctor.requestPermission) == "function") {
            try {
                val asking: dynamic = ctor.requestPermission()
                asking.then({ answer: dynamic ->
                    if (answer == "granted") begin() else swTilt?.checked = false
                    null
                }, { _: dynamic ->
                    swTilt?.checked = false
                    null
                })
            } catch (e: Throwable) {
                swTilt?.checked = false
            }
        } else {
            begin()
        }
    }

    private fun stopTilt() {
        val listener = tiltListener ?: return
        window.removeEventListener("deviceorientation", listener)
        tiltListener = null
    }

    private fun resetKnob(input: HTMLInputElement?, decimals: Int, set: (Double) -> Unit) {
        input ?: return
        input.value = input.defaultValue
        val v = input.value.toDoubleOrNull() ?: return
        set(v)
        paintKnob(input, decimals)
    }

    /** "LET GO": knobs, switches and the theme return to their defaults and the visitor rejoins the world. */
    private fun reset() {
        resetKnob(rho, 1) { world.rho = it }
        resetKnob(drift, 2) { world.driftRate = it }
        resetKnob(loucheKnob, 2) { stage.setLouche(it) }
        theme.setOverride(null)
        swDayNight?.checked = theme.night
        swSlow?.checked = prefersReducedMotion()
        applySlow()
        if (swTilt?.checked == true) {
            swTilt.checked = false
            stopTilt()
        }
        for (sw in unmarked) sw.checked = false
        world.ghostEnabled = false
        resetVisitor()
        stage.flyHome()
    }

    private companion object {
        const val GHOST_INDEX = 2
    }
}

/**
 * Everything else that leaks into the visitor's Lorenz state (SPEC 3.3). All local: nothing here is sent anywhere,
 * and nothing asks for a permission. Raw units follow bloom-core's `CoreTuning.scaleFor` table.
 */
internal class Sensors(private val world: BloomWorld) {
    private var lastPointerX = 0.0
    private var lastPointerY = 0.0
    private var lastPointerAt = 0.0
    private var lastScrollY = 0.0
    private var hiddenAt = 0.0
    private var gamepadSeen = false
    private var gamepadAccumulator = 0.0

    fun start() {
        window.addEventListener("pointermove", { e ->
            val d = e.asDynamic()
            val now = perfNow()
            val x = (d.clientX as? Double) ?: 0.0
            val y = (d.clientY as? Double) ?: 0.0
            val dt = now - lastPointerAt
            if (dt >= 120.0) {
                if (lastPointerAt > 0.0 && dt < 1000.0) {
                    val dx = x - lastPointerX
                    val dy = y - lastPointerY
                    world.perturb(PerturbKind.POINTER, kotlin.math.sqrt(dx * dx + dy * dy) / (dt / 1000.0))
                }
                lastPointerX = x
                lastPointerY = y
                lastPointerAt = now
            }
        }, passive())
        window.addEventListener("pointerdown", { e ->
            val d = e.asDynamic()
            if (d.pointerType == "touch") {
                val w = (d.width as? Double) ?: 0.0
                val h = (d.height as? Double) ?: 0.0
                world.perturb(PerturbKind.TOUCH, (w + h) / 2.0)
            }
        }, passive())
        lastScrollY = window.scrollY
        window.addEventListener("scroll", {
            val y = window.scrollY
            world.perturb(PerturbKind.SCROLL, abs(y - lastScrollY))
            lastScrollY = y
        }, passive())
        window.addEventListener("wheel", { e ->
            world.perturb(PerturbKind.WHEEL, abs((e.asDynamic().deltaY as? Double) ?: 0.0))
        }, passive())
        document.addEventListener("visibilitychange", {
            if (document.asDynamic().visibilityState == "hidden") {
                hiddenAt = perfNow()
            } else if (hiddenAt > 0.0) {
                world.perturb(PerturbKind.VISIBILITY, (perfNow() - hiddenAt) / 1000.0)
                hiddenAt = 0.0
            }
        })
        window.addEventListener("gamepadconnected", { gamepadSeen = true })
        startBattery()
        startNetwork()
    }

    /** Call from the frame loop; polls a connected gamepad twice a second. */
    fun frame(dtSeconds: Double) {
        if (!gamepadSeen) return
        gamepadAccumulator += dtSeconds
        if (gamepadAccumulator < 0.5) return
        gamepadAccumulator = 0.0
        try {
            val pads: dynamic = window.navigator.asDynamic().getGamepads()
            val n = (pads.length as? Int) ?: 0
            var sum = 0.0
            for (i in 0 until n) {
                val pad = pads[i]
                if (pad == null) continue
                val axes = (pad.axes.length as? Int) ?: 0
                for (a in 0 until axes) sum += abs((pad.axes[a] as? Double) ?: 0.0)
            }
            if (sum > 0.02) world.perturb(PerturbKind.GAMEPAD, sum)
        } catch (e: Throwable) {
            gamepadSeen = false
        }
    }

    private fun startBattery() {
        try {
            val nav = window.navigator.asDynamic()
            if (jsTypeOf(nav.getBattery) != "function") return
            val asking: dynamic = nav.getBattery()
            asking.then({ battery: dynamic ->
                val read = { world.perturb(PerturbKind.BATTERY, (battery.level as? Double) ?: 0.0) }
                read()
                battery.addEventListener("levelchange", { read() })
                null
            }, { _: dynamic -> null })
        } catch (e: Throwable) {
            // not exposed here
        }
    }

    private fun startNetwork() {
        try {
            val connection: dynamic = window.navigator.asDynamic().connection ?: return
            val read = {
                val rtt = (connection.rtt as? Double) ?: 0.0
                val downlink = (connection.downlink as? Double) ?: 0.0
                world.perturb(PerturbKind.NETWORK, rtt + downlink)
            }
            read()
            connection.addEventListener("change", { read() })
        } catch (e: Throwable) {
            // not exposed here
        }
    }

    private fun passive(): dynamic {
        val o = newObject()
        o.passive = true
        return o
    }
}
