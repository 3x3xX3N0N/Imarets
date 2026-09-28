package bar.verdantbloom.site

import bar.verdantbloom.bloom.core.BloomCore
import kotlinx.browser.document
import kotlinx.browser.window
import kotlin.js.Date

/**
 * /api/status -> the five-line sign ladder, the warm / cold dots, the member counter.
 * /api/models -> data-live on the pours.
 * When the API cannot be reached the sign goes DARK (or STALE for a short while after a good answer) and every
 * tap says "no word"; nothing throws and nothing is retried faster than the normal poll.
 */
internal class StatusBoard(private val onLatency: (latencyMs: Double, jitterMs: Double) -> Unit = { _, _ -> }) {
    private val board = byId("sign-board")
    private val pours = byId("pours")
    private var lastGoodAt = 0.0
    private var lastLatency = -1.0
    private var timer = 0
    private val scenario: String? = Regex("[?&]scenario=([a-z]+)").find(window.location.search)?.groupValues?.get(1)

    private fun url(path: String) = if (scenario != null) "$path?scenario=$scenario" else path

    fun start(pollSeconds: Int = 30) {
        if (board != null) paintLine("loading", board.data("sign-loading") ?: "", null)
        refresh()
        refreshModels()
        timer = window.setInterval({
            if (document.asDynamic().visibilityState != "hidden") refresh()
        }, pollSeconds * 1000)
    }

    /** One status request. [done] gets the round-trip in ms, or -1 when nothing answered. Used by the bell too. */
    fun refresh(done: ((latencyMs: Double) -> Unit)? = null) {
        fetchJson(url("/api/status"), timeoutMs = 6000) { status, body, elapsed ->
            val ok = status == 200 && body != null && body.gateway != null
            if (ok) {
                lastGoodAt = perfNow()
                val jitter = if (lastLatency >= 0.0) kotlin.math.abs(elapsed - lastLatency) else 0.0
                lastLatency = elapsed
                onLatency(elapsed, jitter)
                paint(body)
            } else {
                paintUnreachable()
            }
            done?.invoke(if (ok) elapsed else -1.0)
        }
    }

    private fun refreshModels() {
        fetchJson(url("/api/models"), timeoutMs = 6000) { status, body, _ ->
            if (status == 200 && body != null && body.models != null) {
                val models: dynamic = body.models
                val n = arrayLength(models)
                for (i in 0 until n) {
                    val id = models[i].id as? String ?: continue
                    val live = models[i].live == true
                    for (article in document.all("article.vb-pour[data-pour-id=\"$id\"]")) {
                        setAttr(article, "data-live", if (live) "true" else "false")
                    }
                }
            }
        }
    }

    private fun paint(body: dynamic) {
        val gateway = body.gateway as? String
        val fleet = body.fleet as? String
        val taps: dynamic = body.taps
        val tapCount = arrayLength(taps)
        var warm = 0
        for (i in 0 until tapCount) if (taps[i].state == "warm") warm++

        // gateway first: a dead gateway still reports a fleet (README, "Status-sign mapping")
        val state = when {
            gateway != "up" -> "down"
            fleet == "parked" -> "parked"
            fleet == "cold" -> "cold"
            fleet == "open" -> "open"
            fleet == "degraded" -> "degraded"
            else -> "down"
        }
        val total = board?.data("total") ?: "10"
        val line = when {
            state == "open" && warm == 1 -> board?.data("sign-open-one")
            else -> board?.data("sign-$state")
        } ?: ""
        val plain = board?.data("plain-$state") ?: ""
        paintLine(state, fill(line, warm, total), fill(plain, warm, total))

        for (i in 0 until tapCount) {
            val id = taps[i].id as? String ?: continue
            val tapState = taps[i].state as? String ?: "unknown"
            paintTap(id, if (state == "down") "unknown" else tapState)
        }

        val asOf = body.asOf as? String
        if (asOf != null) {
            val ms = Date.parse(asOf)
            for (el in document.all("[data-bind=\"sign.asOf\"]")) {
                el.setAttribute("datetime", asOf)
                setText(el, if (ms.isNaN()) asOf else BloomCore.newCalendar.formatTime((ms / 1000.0).toLong()) + " UTC")
            }
        }
        // A zero is not a number worth printing on the sign. Show the count once there is one.
        val members = body.activeMembers
        if (members != null && members > 0) paintMembers(members.toString())
    }

    private fun paintUnreachable() {
        val age = if (lastGoodAt > 0.0) (perfNow() - lastGoodAt) / 1000.0 else -1.0
        if (age in 0.0..120.0) {
            val text = (board?.data("sign-stale") ?: "").replace("{s}", age.toInt().toString())
            paintLine("stale", text, null)
        } else {
            paintLine("down", board?.data("sign-down") ?: "", board?.data("plain-down") ?: "")
            for (article in document.all("article.vb-pour[data-tap]")) {
                paintTap(article.data("pour-id") ?: continue, "unknown")
            }
        }
    }

    private fun fill(template: String, n: Int, total: String) =
        template.replace("{n}", n.toString()).replace("{total}", total)

    private fun paintLine(state: String, line: String, plain: String?) {
        setAttr(document.documentElement, "data-sign-state", state)
        if (line.isNotEmpty()) for (el in document.all("[data-bind=\"sign.line\"]")) setText(el, line)
        if (plain != null && plain.isNotEmpty()) for (el in document.all("[data-bind=\"sign.plain\"]")) setText(el, plain)
    }

    private fun paintTap(id: String, state: String) {
        val known = if (state == "warm" || state == "cold" || state == "parked") state else "unknown"
        val word = pours?.data("tap-$known") ?: ""
        val help = pours?.data("tap-$known-help") ?: ""
        // the list article AND any clone in the card layer
        for (article in document.all("article.vb-pour[data-pour-id=\"$id\"]")) {
            if (!article.hasAttribute("data-tap")) continue
            setAttr(article, "data-tap", known)
            setText(article.first("[data-slot=\"tap-word\"]"), word)
            setText(article.first("[data-slot=\"tap-help\"]"), help)
        }
    }

    private fun paintMembers(n: String) {
        for (el in document.all("[data-bind=\"sign.members\"]")) {
            val template = el.data("template") ?: "{n}"
            setText(el, template.replace("{n}", n))
            if (el.hidden) el.hidden = false
        }
    }
}
