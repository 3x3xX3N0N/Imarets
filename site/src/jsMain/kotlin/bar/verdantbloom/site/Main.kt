package bar.verdantbloom.site

import bar.verdantbloom.bloom.api.ClockSource
import bar.verdantbloom.bloom.api.PaletteDom
import bar.verdantbloom.bloom.api.PerturbKind
import bar.verdantbloom.bloom.api.RendererKind
import bar.verdantbloom.bloom.api.TimeOverride
import bar.verdantbloom.bloom.core.BloomCore
import com.example.spacegraphkt.zui.HashRouter
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.url.URLSearchParams
import kotlin.js.Date

/**
 * verdantbloom.bar - the integrator's wiring. The pages are complete documents without this script
 * (DOM-CONTRACT.md); everything here ENHANCES them and nothing here creates copy.
 *
 *   index   : bloom stage (renderer ladder), HUD, tour / deep links, status sign, waitlist form
 *   pricing : reduced-fee form, New Calendar dates, member counter. No bloom.
 */
fun main() {
    if (document.readyState.toString() == "loading") {
        document.addEventListener("DOMContentLoaded", { boot() })
    } else {
        boot()
    }
}

private fun boot() {
    val root = document.documentElement ?: return
    root.setAttribute("data-js", "on")
    try {
        when (root.getAttribute("data-page")) {
            "index" -> startIndex()
            "pricing" -> startPricing()
        }
    } catch (e: Throwable) {
        // the document underneath still works; say what happened and stop
        console.error("[site] start-up failed", e)
    }
}

private val wallClock = ClockSource { Date.now() / 1000.0 }

private fun startPricing() {
    val theme = ThemeControl {}
    theme.apply()
    theme.watchClock {}
    paintDates()
    Forms.wireReducedFee()
    StatusBoard().start(pollSeconds = 60)
}

private fun startIndex() {
    val requested = RendererKind.fromQuery(URLSearchParams(window.location.search).get("renderer"))
    val world = BloomCore.createWorld()
    val pinned = TimeOverride.parse(window.location.hash)
    val stage = BloomStage(world, TimeOverride.clockFor(window.location.hash, wallClock), requested)
    world.advanceTo(stage.clock.nowUnixSeconds())

    val theme = ThemeControl { stage.setPalette(PaletteDom.read()) }
    theme.apply()
    theme.watchClock {}
    paintDates()

    val sensors = Sensors(world)
    val status = StatusBoard { _, jitterMs -> world.perturb(PerturbKind.PING_JITTER, jitterMs) }

    val hud = Hud(world, stage, theme, status, resetVisitor = {
        // a backwards jump of more than 5 s makes the world hand the visitor a fresh copy of itself
        val now = stage.clock.nowUnixSeconds()
        world.advanceTo(now - 10.0)
        world.advanceTo(now)
    })
    hud.wire()

    wireClock(stage, pinned)
    var lastSecond = Long.MIN_VALUE
    stage.onFrame = { now, dt ->
        sensors.frame(dt)
        val second = kotlin.math.floor(now).toLong()
        if (second != lastSecond) {
            lastSecond = second
            paintClock(second)
        }
    }
    paintClock(kotlin.math.floor(stage.clock.nowUnixSeconds()).toLong())

    stage.start(PaletteDom.read())
    Navigator(stage).start()
    sensors.start()
    Forms.wireWaitlist { intervalMs -> world.perturb(PerturbKind.KEY_TIMING, intervalMs) }
    status.start()
}

// ---------------------------------------------------------------------------------------------- clock

private fun paintClock(unixSecond: Long) {
    val calendar = BloomCore.newCalendar
    val el = byId("clock-newcal")
    setText(el, calendar.format(calendar.fromUnix(unixSecond)) + "  " + calendar.formatTime(unixSecond))
    setAttr(el, "datetime", Date(unixSecond.toDouble() * 1000.0).toISOString())
    setText(byId("clock-unix"), unixSecond.toString())
}

private fun wireClock(stage: BloomStage, pinned: Double?) {
    val clock = byId("clock") ?: return
    val note = byId("clock-note")
    val backToNow = byId("clock-now")
    if (pinned != null) {
        val origin = pinned == 0.0
        clock.setAttribute("data-clock-state", if (origin) "origin" else "pinned")
        if (note != null) {
            note.textContent = if (origin) {
                (note.data("origin-sign") ?: "") + " " + (note.data("origin-note") ?: "")
            } else {
                note.data("pinned-note") ?: ""
            }
            note.hidden = false
        }
        backToNow?.hidden = false
    }
    backToNow?.addEventListener("click", {
        stage.clock = wallClock
        clock.setAttribute("data-clock-state", "now")
        note?.hidden = true
        backToNow.hidden = true
        // drop t= from the address, keep the route; hashchange keeps the router's own state in step
        val state = HashRouter.parse(window.location.hash)
        val hash = HashRouter.emit(state.copy(t = null))
        window.location.hash = hash
    })
}

/** `time[data-newcal="date"][datetime="YYYY-MM-DD"]` -> the same day in The New Calendar (DOM-CONTRACT 3.7). */
private fun paintDates() {
    val calendar = BloomCore.newCalendar
    for (el in document.all("time[data-newcal=\"date\"][datetime]")) {
        val ms = Date.parse((el.getAttribute("datetime") ?: continue) + "T00:00:00Z")
        if (ms.isNaN()) continue
        setText(el, calendar.format(calendar.fromUnix((ms / 1000.0).toLong())))
    }
}
