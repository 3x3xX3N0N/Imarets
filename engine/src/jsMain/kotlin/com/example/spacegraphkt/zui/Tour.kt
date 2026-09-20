package com.example.spacegraphkt.zui

/**
 * One stop of the guided tour.
 * @property id stable id of the stop (normally the engine node id it flies to)
 * @property title text a tour bar shows (always set it through `textContent`)
 * @property route deep link that represents this stop, or null when the stop has none
 */
data class TourStop(val id: String, val title: String = id, val route: Route? = null)

/**
 * Ordered stops with next / prev / current, so a DOM "tour bar" (prev / title / next buttons) can reach every
 * node without free exploration (SPEC 3.1). Pure Kotlin: it knows nothing about cameras. Wire it with
 * `tour.addListener { stop, index -> graph.flyTo(stop.id) }` or use [ZuiNavigator].
 *
 * The tour starts with NO current stop (index -1): the visitor is at the overview. `next()` then goes to the
 * first stop, `prev()` to the last one.
 */
class Tour(stops: List<TourStop> = emptyList(), var wrap: Boolean = true) {
    private var _stops: List<TourStop> = stops.toList()
    private val listeners = ArrayList<(TourStop?, Int) -> Unit>()
    private val changeListeners = ArrayList<(TourStop?, Int) -> Unit>()

    init {
        requireUniqueIds(_stops)
    }

    val stops: List<TourStop> get() = _stops
    val size: Int get() = _stops.size

    /** Index of the current stop, or -1 when the tour is at the overview. */
    var index: Int = -1
        private set

    val current: TourStop? get() = _stops.getOrNull(index)

    val hasNext: Boolean get() = _stops.isNotEmpty() && (wrap || index < _stops.lastIndex)
    val hasPrev: Boolean get() = _stops.isNotEmpty() && (wrap || index > 0)

    /** "3 / 10" style label for the bar; "- / 10" at the overview. */
    val positionLabel: String get() = (if (index < 0) "-" else (index + 1).toString()) + " / " + _stops.size

    private fun requireUniqueIds(list: List<TourStop>) {
        require(list.map { it.id }.toSet().size == list.size) { "Tour: stop ids must be unique" }
    }

    /** Replaces the stops. The current stop is kept when a stop with the same id still exists, else the tour resets. */
    fun setStops(newStops: List<TourStop>) {
        requireUniqueIds(newStops)
        val currentId = current?.id
        _stops = newStops.toList()
        val newIndex = if (currentId == null) -1 else _stops.indexOfFirst { it.id == currentId }
        if (newIndex != index) {
            index = newIndex
            notifyListeners()
        }
    }

    fun next(): TourStop? {
        if (_stops.isEmpty()) return null
        val target = when {
            index < _stops.lastIndex -> index + 1
            wrap -> 0
            else -> return current
        }
        return moveTo(target)
    }

    fun prev(): TourStop? {
        if (_stops.isEmpty()) return null
        val target = when {
            index > 0 -> index - 1
            index == 0 && !wrap -> return current
            else -> if (wrap || index < 0) _stops.lastIndex else return current
        }
        return moveTo(target)
    }

    /** @return the stop, or null (and no change) when [stopIndex] is out of range. */
    fun goTo(stopIndex: Int): TourStop? {
        if (stopIndex !in _stops.indices) return null
        return moveTo(stopIndex)
    }

    /** @return the stop, or null (and no change) when no stop has [id]. */
    fun goTo(id: String): TourStop? = goTo(_stops.indexOfFirst { it.id == id })

    /**
     * Moves the tour position WITHOUT notifying listeners: use it when the visitor got somewhere by other
     * means (clicked a ring, followed a deep link) and the bar only has to catch up. Unknown id -> overview.
     */
    fun sync(id: String?) {
        val newIndex = if (id == null) -1 else _stops.indexOfFirst { it.id == id }
        if (newIndex == index) return
        index = newIndex
        notifyChanged()
    }

    /** Back to the overview (no current stop). Notifies with (null, -1). */
    fun leave() {
        if (index == -1) return
        index = -1
        notifyListeners()
    }

    private fun moveTo(target: Int): TourStop {
        if (target != index) {
            index = target
            notifyListeners()
        }
        return _stops[target]
    }

    private fun notifyListeners() {
        val stop = current
        for (l in listeners.toList()) l(stop, index)
        notifyChanged()
    }

    private fun notifyChanged() {
        val stop = current
        for (l in changeListeners.toList()) l(stop, index)
    }

    /**
     * DISPLAY listener: fires on EVERY change of the current stop, including [sync]. This is what a tour bar
     * uses to repaint its label and enable / disable its buttons. It must not move the camera.
     * @return a function that removes the listener again.
     */
    fun addChangeListener(listener: (stop: TourStop?, index: Int) -> Unit): () -> Unit {
        changeListeners.add(listener)
        return { changeListeners.remove(listener) }
    }

    /**
     * NAVIGATION listener: fires when the tour itself moved ([next], [prev], [goTo], [leave], [setStops]) - not
     * on [sync]. This is where the camera flies.
     * @return a function that removes the listener again.
     */
    fun addListener(listener: (stop: TourStop?, index: Int) -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }
}
