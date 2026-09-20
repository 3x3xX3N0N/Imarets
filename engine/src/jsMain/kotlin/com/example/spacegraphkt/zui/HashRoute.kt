package com.example.spacegraphkt.zui

/**
 * Deep-link routes of the landing page (SPEC 3.1): `#pour/<id>`, `#tab`, `#list`, `#sign`, and no route (home).
 * Pure Kotlin; the DOM binding is [HashRouterBinding].
 */
sealed class Route {
    /** No deep link: the overview. */
    object Home : Route() { override fun toString() = "Home" }

    /** `#pour/<id>` - one model card. [id] is the public model id (`mini`, `flagship`, ...). */
    data class Pour(val id: String) : Route()

    /** `#tab` - OPEN A TAB (passes). */
    object Tab : Route() { override fun toString() = "Tab" }

    /** `#list` - the POUR LIST. */
    object PourList : Route() { override fun toString() = "PourList" }

    /** `#sign` - the status sign. */
    object Sign : Route() { override fun toString() = "Sign" }
}

/**
 * Everything the hash carries: the route, the optional `t=<unix>` clock override (SPEC 3.2) and any other
 * `key=value` parameters, which are preserved so that navigating never drops something another module put there.
 *
 * [t] is kept as the ORIGINAL TEXT (validated: a non-negative finite decimal number) so that re-emitting is lossless.
 * bloom-api's `TimeOverride.parse(hash)` reads the same syntax and stays the authority for the clock itself.
 */
data class HashState(
    val route: Route = Route.Home,
    val t: String? = null,
    val extras: List<Pair<String, String>> = emptyList(),
) {
    val tSeconds: Double? get() = t?.toDoubleOrNull()

    fun withRoute(newRoute: Route): HashState = copy(route = newRoute)
}

object HashRouter {
    private const val POUR_PREFIX = "pour/"

    /** Ids are restricted so a crafted hash can never smuggle markup or separators into the page. */
    fun isValidId(id: String): Boolean =
        id.isNotEmpty() && id.length <= 64 && id.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' || it == '.' }

    private fun isValidT(text: String): Boolean {
        if (text.isEmpty() || text.length > 32) return false
        var dots = 0
        var digits = 0
        for (c in text) {
            when {
                c in '0'..'9' -> digits++
                c == '.' -> dots++
                else -> return false
            }
        }
        if (dots > 1 || digits == 0) return false
        val v = text.toDoubleOrNull() ?: return false
        return v.isFinite() && v >= 0.0
    }

    private fun routeOf(segment: String): Route? = when {
        segment.isEmpty() -> Route.Home
        segment == "tab" -> Route.Tab
        segment == "list" -> Route.PourList
        segment == "sign" -> Route.Sign
        segment.startsWith(POUR_PREFIX) -> segment.removePrefix(POUR_PREFIX).takeIf(::isValidId)?.let { Route.Pour(it) }
        else -> null
    }

    /**
     * Parses `location.hash` (with or without the leading `#`). Segments are separated by `&`; the route may
     * be any segment (normally the first), `t=` may be first or later: `#t=0`, `#pour/mini&t=86400`, `#t=5&list`.
     * Unknown routes, bad ids and bad `t` values are ignored (-> Home / null), never thrown.
     */
    fun parse(hash: String): HashState {
        val body = hash.trim().removePrefix("#")
        if (body.isEmpty()) return HashState()
        var route: Route? = null
        var t: String? = null
        val extras = ArrayList<Pair<String, String>>()
        for (segment in body.split('&')) {
            if (segment.isEmpty()) continue
            val eq = segment.indexOf('=')
            if (eq > 0) {
                val key = segment.substring(0, eq)
                val value = segment.substring(eq + 1)
                if (key == "t") {
                    if (t == null && isValidT(value)) t = value
                } else if (isValidId(key) && value.length <= 128 && value.none { it == '#' || it == '&' }) {
                    extras.add(key to value)
                }
            } else if (route == null) {
                route = routeOf(segment)
            }
        }
        return HashState(route ?: Route.Home, t, extras)
    }

    fun routeSegment(route: Route): String = when (route) {
        Route.Home -> ""
        is Route.Pour -> POUR_PREFIX + route.id
        Route.Tab -> "tab"
        Route.PourList -> "list"
        Route.Sign -> "sign"
    }

    /** The canonical hash for [state], WITH the leading `#`, or "" when there is nothing to say. Route first, then `t`, then extras. */
    fun emit(state: HashState): String {
        val parts = ArrayList<String>()
        val segment = routeSegment(state.route)
        if (segment.isNotEmpty()) parts.add(segment)
        state.t?.let { parts.add("t=$it") }
        for ((k, v) in state.extras) parts.add("$k=$v")
        return if (parts.isEmpty()) "" else "#" + parts.joinToString("&")
    }

    /** Convenience: the hash that shows [route] while keeping whatever `t` / extras [currentHash] carries. */
    fun navigate(currentHash: String, route: Route): String = emit(parse(currentHash).withRoute(route))
}
