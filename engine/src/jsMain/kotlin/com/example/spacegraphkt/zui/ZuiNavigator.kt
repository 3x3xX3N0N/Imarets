package com.example.spacegraphkt.zui

import com.example.spacegraphkt.core.SpaceGraph
import kotlinx.browser.window
import org.w3c.dom.events.Event

/**
 * DOM side of the hash router: keeps a [HashState] in step with `location.hash`.
 *
 * - [navigate] writes the URL with `history.pushState` / `replaceState` (so the browser's Back button walks the
 *   same trail as Esc) and does NOT call [onChange]: the caller already knows where it went.
 * - Back / Forward / a hand-edited hash / a clicked `<a href="#pour/mini">` arrive as `hashchange` or `popstate`
 *   and DO call [onChange], once per real change.
 * - `t=<unix>` and unknown `key=value` parameters survive every navigation.
 */
class HashRouterBinding(private val onChange: (HashState) -> Unit) {
    var state: HashState = HashState()
        private set

    private var started = false
    private val listener: (Event) -> Unit = { read(true) }

    /** Reads the current hash, starts listening and reports the initial state through [onChange]. */
    fun start() {
        if (started) return
        started = true
        window.addEventListener("hashchange", listener)
        window.addEventListener("popstate", listener)
        state = HashRouter.parse(window.location.hash)
        onChange(state)
    }

    private fun read(notify: Boolean) {
        val next = HashRouter.parse(window.location.hash)
        if (next == state) return
        state = next
        if (notify) onChange(next)
    }

    /** Shows [route] in the URL. @param replace true = do not add a browser history entry. */
    fun navigate(route: Route, replace: Boolean = false) {
        if (route == state.route) return
        state = state.withRoute(route)
        val hash = HashRouter.emit(state)
        val url = window.location.pathname + window.location.search + hash
        if (replace) window.history.replaceState(null, "", url) else window.history.pushState(null, "", url)
    }

    fun dispose() {
        if (!started) return
        started = false
        window.removeEventListener("hashchange", listener)
        window.removeEventListener("popstate", listener)
    }
}

/**
 * Glue between a [SpaceGraph], the hash router and a [Tour]; everything a DOM tour bar needs is on [tour]
 * (`next()`, `prev()`, `current`, `positionLabel`, `addListener`).
 *
 * Tour stops are the single source of the node <-> route mapping: `TourStop(id = <engine node id>, route = Route.Pour("mini"))`.
 *
 *  - tour moves            -> camera flies to the stop's node, URL shows its route
 *  - click / tap on a node -> (engine flies) tour position and URL follow
 *  - deep link, Back / Forward -> camera flies to that node, tour position follows
 *  - Esc back to the overview -> URL loses its route (`t=` stays)
 */
class ZuiNavigator(val graph: SpaceGraph, val tour: Tour) {
    val router = HashRouterBinding(::onHashChanged)
    private val disposers = ArrayList<() -> Unit>()
    private var applying = false

    private fun stopForRoute(route: Route) = tour.stops.firstOrNull { it.route == route }
    private fun stopForNode(nodeId: String?) = if (nodeId == null) null else tour.stops.firstOrNull { it.id == nodeId }

    /** Call once the nodes exist. Applies the deep link the page was opened with (flying from the overview, so Esc goes back there). */
    fun start() {
        disposers.add(tour.addListener { stop, _ ->
            if (!applying) {
                if (stop == null) home() else {
                    graph.flyTo(stop.id)
                    stop.route?.let { router.navigate(it) }
                }
            }
        })
        disposers.add(graph.cameraController.addTargetListener { nodeId ->
            if (!applying) {
                val stop = stopForNode(nodeId)
                if (stop != null) {
                    tour.sync(stop.id)
                    stop.route?.let { router.navigate(it) }
                } else if (nodeId == null && !graph.cameraController.canGoBack) {
                    tour.sync(null)
                    router.navigate(Route.Home, replace = true)
                }
            }
        })
        router.start()
    }

    private fun onHashChanged(state: HashState) {
        applying = true
        try {
            val stop = stopForRoute(state.route)
            if (stop != null && graph.getNodeById(stop.id) != null) {
                tour.sync(stop.id)
                graph.flyTo(stop.id)
            } else if (state.route == Route.Home) {
                tour.sync(null)
                graph.reset()
            }
        } finally {
            applying = false
        }
    }

    /** Overview: camera reset, tour at "no stop", route removed from the URL. */
    fun home() {
        applying = true
        try {
            tour.leave()
            graph.reset()
        } finally {
            applying = false
        }
        router.navigate(Route.Home)
    }

    fun dispose() {
        disposers.forEach { it() }
        disposers.clear()
        router.dispose()
    }
}
