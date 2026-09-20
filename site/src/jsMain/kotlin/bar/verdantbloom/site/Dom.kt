package bar.verdantbloom.site

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.ParentNode
import org.w3c.dom.asList

/** Small DOM helpers. CSP rule of the house: CSSOM and textContent only, never markup strings. */
internal fun byId(id: String): HTMLElement? = document.getElementById(id) as? HTMLElement

internal fun ParentNode.all(selector: String): List<HTMLElement> =
    querySelectorAll(selector).asList().mapNotNull { it as? HTMLElement }

internal fun ParentNode.first(selector: String): HTMLElement? = querySelector(selector) as? HTMLElement

internal fun Element.data(name: String): String? = getAttribute("data-$name")

internal fun setText(el: Element?, text: String) {
    if (el != null && el.textContent != text) el.textContent = text
}

internal fun setAttr(el: Element?, name: String, value: String) {
    if (el != null && el.getAttribute(name) != value) el.setAttribute(name, value)
}

internal fun perfNow(): Double = window.performance.now()

/** Length of a JS array that may be null / not an array. */
internal fun arrayLength(a: dynamic): Int = (js("Array.isArray")(a) as Boolean).let { ok -> if (ok) a.length as Int else 0 }

internal fun newObject(): dynamic = js("({})")

internal fun prefersReducedMotion(): Boolean = try {
    window.matchMedia("(prefers-reduced-motion: reduce)").matches
} catch (e: Throwable) {
    false
}

/**
 * JSON over fetch with a timeout. [done] gets (httpStatus, parsedBodyOrNull, elapsedMs); httpStatus 0 = network
 * failure / timeout / blocked. Never throws, never rejects.
 */
internal fun fetchJson(
    url: String,
    method: String = "GET",
    body: dynamic = null,
    timeoutMs: Int = 8000,
    done: (status: Int, body: dynamic, elapsedMs: Double) -> Unit,
) {
    val started = perfNow()
    var finished = false
    fun finish(status: Int, parsed: dynamic) {
        if (finished) return
        finished = true
        try {
            done(status, parsed, perfNow() - started)
        } catch (e: Throwable) {
            console.warn("[site] handler for $url failed", e)
        }
    }
    try {
        val init = newObject()
        init.method = method
        init.cache = "no-store"
        init.credentials = "same-origin"
        val headers = newObject()
        headers["Accept"] = "application/json"
        if (body != null) {
            headers["Content-Type"] = "application/json"
            init.body = JSON.stringify(body)
        }
        init.headers = headers
        val controller: dynamic = js("(typeof AbortController === 'function') ? new AbortController() : null")
        if (controller != null) init.signal = controller.signal
        val timer = window.setTimeout({
            if (controller != null) controller.abort()
            finish(0, null)
        }, timeoutMs)
        val promise: dynamic = window.asDynamic().fetch(url, init)
        promise.then({ response: dynamic ->
            val status = (response.status as? Int) ?: 0
            val parsed: dynamic = response.json()
            parsed.then({ value: dynamic ->
                window.clearTimeout(timer)
                finish(status, value)
                null
            }, { _: dynamic ->
                window.clearTimeout(timer)
                finish(status, null)
                null
            })
            null
        }, { _: dynamic ->
            window.clearTimeout(timer)
            finish(0, null)
            null
        })
    } catch (e: Throwable) {
        finish(0, null)
    }
}
