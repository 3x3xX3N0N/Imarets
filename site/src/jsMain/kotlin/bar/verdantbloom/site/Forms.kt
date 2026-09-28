package bar.verdantbloom.site

import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLFormElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.HTMLTextAreaElement

/**
 * Waitlist + reduced-fee forms. Both are plain working POST forms with JS off; here they become JSON posts with
 * inline validation. Every string shown comes from the form's data attributes (DOM-CONTRACT 3.4 / 4).
 * The honeypot field is sent exactly as found and never touched.
 */
internal object Forms {
    private val EMAIL = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
    private val PASS_IDS = setOf("", "walkin", "well", "call", "top", "reserve", "private")
    private const val HOUSE_RATE = "On the house (Well only)"

    private class FormUi(val form: HTMLFormElement, val prefix: String) {
        val submit = byId("$prefix-submit") as? HTMLButtonElement
        val status = byId("$prefix-status")
        val success = byId("$prefix-success")
        var busy = false

        fun msg(key: String): String = form.getAttribute("data-$key") ?: ""

        fun clearErrors() {
            for (slot in form.all("[data-error-for]")) {
                slot.hidden = true
                slot.textContent = ""
            }
            for (field in form.all("[aria-invalid]")) field.removeAttribute("aria-invalid")
            setStatus("idle", "")
        }

        fun fieldError(name: String, key: String, focus: Boolean) {
            val slot = form.first("[data-error-for=\"$name\"]")
            if (slot != null) {
                slot.textContent = msg(key)
                slot.hidden = false
            }
            val field = form.elements.namedItem(name) as? HTMLElement
            field?.setAttribute("aria-invalid", "true")
            if (focus) field?.focus()
        }

        fun setStatus(state: String, text: String) {
            status?.setAttribute("data-state", state)
            status?.textContent = text
        }

        fun setBusy(on: Boolean) {
            busy = on
            submit?.disabled = on
            val label = msg(if (on) "label-submitting" else "label-submit")
            if (label.isNotEmpty()) submit?.textContent = label
            if (on) setStatus("busy", "") else if (status?.getAttribute("data-state") == "busy") setStatus("idle", "")
        }

        fun done() {
            form.setAttribute("data-state", "done")
            success?.hidden = false
            success?.focus()
        }

        fun transportError(status: Int) {
            val key = when (status) {
                0 -> "err-network"
                429 -> "err-rate-limited"
                413 -> "err-too-large"
                else -> "err-server"
            }
            setStatus("error", msg(key))
        }
    }

    private fun value(form: HTMLFormElement, name: String): String = when (val el = form.elements.namedItem(name)) {
        is HTMLInputElement -> el.value
        is HTMLSelectElement -> el.value
        is HTMLTextAreaElement -> el.value
        else -> ""
    }

    fun wireWaitlist(onKeyTiming: (Double) -> Unit) {
        val form = byId("waitlist-form") as? HTMLFormElement ?: return
        val ui = FormUi(form, "wl")
        form.noValidate = true

        // keystroke TIMING only (SPEC 3.3): the interval between key events, never the key or the value
        var lastKey = 0.0
        byId("wl-email")?.addEventListener("keydown", {
            val now = perfNow()
            if (lastKey > 0.0) onKeyTiming(now - lastKey)
            lastKey = now
        })

        form.addEventListener("submit", { event ->
            event.preventDefault()
            if (!ui.busy) {
                ui.clearErrors()
                val email = value(form, "email").trim()
                val pass = value(form, "passInterest")
                when {
                    email.isEmpty() -> ui.fieldError("email", "err-email-required", true)
                    email.length > 254 || !EMAIL.matches(email) -> ui.fieldError("email", "err-email-invalid", true)
                    pass !in PASS_IDS -> ui.fieldError("passInterest", "err-pass-invalid", true)
                    else -> {
                        val payload = newObject()
                        payload.email = email
                        if (pass.isNotEmpty()) payload.passInterest = pass
                        val source = value(form, "source").trim()
                        if (source.isNotEmpty()) payload.source = source
                        payload.website = value(form, "website")
                        ui.setBusy(true)
                        fetchJson("/api/subscribe", "POST", payload, 15000) { status, body, _ ->
                            ui.setBusy(false)
                            if (status == 202 || status == 200) {
                                ui.done()
                            } else if (status == 400 && body != null && body.fields != null) {
                                val fields: dynamic = body.fields
                                var shown = false
                                if (fields.email != null) { ui.fieldError("email", "err-email-invalid", true); shown = true }
                                if (fields.passInterest != null) { ui.fieldError("passInterest", "err-pass-invalid", !shown); shown = true }
                                if (!shown) ui.transportError(status)
                            } else {
                                ui.transportError(status)
                            }
                        }
                    }
                }
            }
        })
    }

    fun wireReducedFee() {
        val form = byId("reduced-fee-form") as? HTMLFormElement ?: return
        val ui = FormUi(form, "rf")
        form.noValidate = true

        val area = byId("rf-circumstances") as? HTMLTextAreaElement
        val counter = byId("rf-circumstances-count")
        val template = counter?.data("template") ?: "{n} / 500"
        fun count() = setText(counter, template.replace("{n}", (area?.value?.length ?: 0).toString()))
        area?.addEventListener("input", { count() })
        count()

        form.addEventListener("submit", { event ->
            event.preventDefault()
            if (!ui.busy) {
                ui.clearErrors()
                val contact = value(form, "contact").trim()
                val pass = value(form, "pass")
                val rate = value(form, "rate")
                val circumstances = value(form, "circumstances").trim()
                val country = value(form, "country").trim()
                val attest = (form.elements.namedItem("attest") as? HTMLInputElement)?.checked == true
                var bad = false
                fun fail(name: String, key: String) {
                    ui.fieldError(name, key, !bad)
                    bad = true
                }
                if (contact.length < 3) fail("contact", "err-contact-required")
                if (pass.isEmpty()) fail("pass", "err-pass-required")
                if (rate.isEmpty()) fail("rate", "err-rate-required")
                else if (rate == HOUSE_RATE && pass.isNotEmpty() && pass != "Well") fail("rate", "err-house-well-only")
                if (circumstances.isEmpty()) fail("circumstances", "err-circumstances-required")
                else if (circumstances.length > 500) fail("circumstances", "err-circumstances-too-long")
                if (!attest) fail("attest", "err-attest-required")
                if (!bad) {
                    val payload = newObject()
                    payload.contact = contact
                    payload.pass = pass
                    payload.rate = rate
                    payload.circumstances = circumstances
                    if (country.isNotEmpty()) payload.country = country
                    payload.attest = true
                    payload.website = value(form, "website")
                    ui.setBusy(true)
                    fetchJson("/api/reduced-fee", "POST", payload, 15000) { status, body, _ ->
                        ui.setBusy(false)
                        if (status == 202 || status == 200) {
                            ui.done()
                        } else if (status == 400 && body != null && body.fields != null) {
                            val fields: dynamic = body.fields
                            var shown = false
                            fun server(name: String, key: String) {
                                if (fields[name] != null) {
                                    ui.fieldError(name, key, !shown)
                                    shown = true
                                }
                            }
                            server("contact", "err-contact-required")
                            server("pass", "err-pass-required")
                            server("rate", if (rate == HOUSE_RATE) "err-house-well-only" else "err-rate-required")
                            server("circumstances", "err-circumstances-too-long")
                            server("attest", "err-attest-required")
                            if (!shown) ui.transportError(status)
                        } else {
                            ui.transportError(status)
                        }
                    }
                }
            }
        })
    }
}
