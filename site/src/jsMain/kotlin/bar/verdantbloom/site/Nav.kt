package bar.verdantbloom.site

import com.example.spacegraphkt.zui.HashRouterBinding
import com.example.spacegraphkt.zui.HashState
import com.example.spacegraphkt.zui.Route
import com.example.spacegraphkt.zui.Tour
import com.example.spacegraphkt.zui.TourStop
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.Element
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.events.KeyboardEvent

/**
 * Deep links, the tour bar, Esc / back. Uses the engine's pure ZUI pieces (`Tour`, `HashRouter`, `HashRouterBinding`):
 * the stop list in `ol#tour-stops` is the single map between stops, routes and rings.
 *
 * A stop with a ring flies the camera to that ring's card inside the stage; a stop without one (on order, passes,
 * list, sign) is a place in the document and is scrolled to. Either way every node is reachable with PREV / NEXT alone.
 */
internal class Navigator(private val stage: BloomStage) {
    private class Stop(val id: String, val title: String, val ring: Int, val route: Route)

    private val stops = ArrayList<Stop>()
    private val tour: Tour
    private val router = HashRouterBinding { state -> onHash(state) }
    private val trail = ArrayList<String>()
    private var currentId = "bloom"

    private val nav = byId("tour")
    private val backButton = byId("tour-back") as? HTMLButtonElement
    private val prevButton = byId("tour-prev") as? HTMLButtonElement
    private val nextButton = byId("tour-next") as? HTMLButtonElement

    init {
        for (li in document.all("#tour-stops > li[data-stop]")) {
            val id = li.data("stop") ?: continue
            val title = li.first("a")?.textContent ?: id
            val ring = li.data("ring-id")?.toIntOrNull() ?: -1
            stops.add(Stop(id, title, ring, routeOf(id)))
        }
        tour = Tour(stops.map { TourStop(it.id, it.title, it.route) }, wrap = false)
    }

    private fun routeOf(id: String): Route = when {
        id.startsWith("pour/") -> Route.Pour(id.removePrefix("pour/"))
        id == "tab" -> Route.Tab
        id == "list" -> Route.PourList
        id == "sign" -> Route.Sign
        else -> Route.Home
    }

    private fun stopOf(route: Route): Stop? = stops.firstOrNull { it.route == route }

    fun start() {
        tour.addChangeListener { _, _ -> paintBar() }
        tour.addListener { stop, _ -> if (stop != null) go(stop.id, record = true, fromUser = true) }
        prevButton?.addEventListener("click", { tour.prev() })
        nextButton?.addEventListener("click", { tour.next() })
        backButton?.addEventListener("click", { back() })
        window.addEventListener("keydown", { e ->
            if ((e as? KeyboardEvent)?.key == "Escape") back()
        })
        // in-page links become ZUI moves; anything else is left to the browser
        document.addEventListener("click", { event ->
            val link = (event.target as? Element)?.closest("a[href^=\"#\"]")
            if (link != null && !event.defaultPrevented) {
                val href = link.getAttribute("href") ?: ""
                val id = href.removePrefix("#").substringBefore("&")
                if (link.hasAttribute("data-pass")) {
                    preselectPass(link.getAttribute("data-pass") ?: "", link.getAttribute("data-source"))
                }
                if (stops.any { it.id == id }) {
                    event.preventDefault()
                    go(id, record = true, fromUser = true)
                }
            }
        })
        stage.onRingChosen = { ring ->
            val stop = stops.firstOrNull { it.ring == ring }
            if (stop != null) go(stop.id, record = true, fromUser = true)
        }
        tour.sync("bloom")
        router.start()
        paintBar()

        val pass = Regex("[?&]pass=(well|call|top)\\b").find(window.location.search)?.groupValues?.get(1)
        if (pass != null) preselectPass(pass, "pricing-$pass")
    }

    /** Back / Forward, a hand-edited hash, or the hash the page was opened with. */
    private fun onHash(state: HashState) {
        val stop = stopOf(state.route) ?: return
        if (stop.id == currentId) return
        go(stop.id, record = false, fromUser = false)
    }

    private fun go(id: String, record: Boolean, fromUser: Boolean) {
        val stop = stops.firstOrNull { it.id == id } ?: return
        if (record && id != currentId) {
            trail.add(currentId)
            if (trail.size > 20) trail.removeAt(0)
        }
        currentId = id
        tour.sync(id)
        if (fromUser) router.navigate(stop.route)
        when {
            stop.ring in 0..9 && stage.available -> {
                stage.flyToRing(stop.ring)
                scrollTo(byId("stage"), "center", fromUser)
            }
            id == "bloom" -> {
                stage.flyHome()
                scrollTo(byId("bloom"), "start", fromUser)
            }
            else -> {
                if (stage.focusRing >= 0) stage.flyHome()
                scrollTo(document.getElementById(id), "start", fromUser)
            }
        }
        paintBar()
    }

    private fun back() {
        if (trail.isEmpty()) {
            if (currentId != "bloom") go("bloom", record = false, fromUser = true)
            return
        }
        val previous = trail.removeAt(trail.size - 1)
        go(previous, record = false, fromUser = true)
    }

    private fun scrollTo(el: Element?, block: String, smooth: Boolean) {
        el ?: return
        if (document.readyState.toString() != "complete") {
            // opened on a deep link: the browser does its own jump to the fragment around load; go after it
            window.addEventListener("load", { window.setTimeout({ scrollTo(el, block, false) }, 60) })
            return
        }
        val options = newObject()
        options.behavior = if (!smooth || stage.reducedMotion || prefersReducedMotion()) "auto" else "smooth"
        options.block = block
        try {
            el.asDynamic().scrollIntoView(options)
        } catch (e: Throwable) {
            el.scrollIntoView()
        }
    }

    private fun paintBar() {
        val index = tour.index
        nav?.setAttribute("data-index", (if (index < 0) 0 else index).toString())
        setText(byId("tour-stop"), tour.current?.title ?: (nav?.data("msg-start") ?: ""))
        val pos = byId("tour-pos")
        val template = pos?.data("template") ?: "{i} / {count}"
        setText(pos, template.replace("{i}", (if (index < 0) 0 else index + 1).toString()).replace("{count}", tour.size.toString()))
        prevButton?.disabled = !tour.hasPrev
        nextButton?.disabled = !tour.hasNext
        backButton?.disabled = trail.isEmpty() && currentId == "bloom"
    }

    /** DOM-CONTRACT 3.2: a pass button means "waitlist, with this pass noted". */
    private val PASS_LABELS = mapOf(
        "walkin" to "Walk-In", "well" to "Well", "call" to "Call",
        "top" to "Top Shelf", "reserve" to "Reserve", "private" to "Private Stock",
    )

    private fun preselectPass(pass: String, source: String?) {
        val select = byId("wl-pass") as? HTMLSelectElement ?: return
        if (pass !in PASS_LABELS) return
        select.value = pass
        if (source != null) (byId("wl-source") as? HTMLInputElement)?.value = source
        val note = byId("wl-pass-note") ?: return
        val label = PASS_LABELS[pass] ?: pass
        note.textContent = (note.data("template") ?: "{pass}").replace("{pass}", label)
        note.hidden = false
    }
}
