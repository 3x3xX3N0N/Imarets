"""Law-enforcement and legal-process guidelines + intake form (legal/law-enforcement.html).
Source of truth: contracts/legal-process.md in the verdantbloom super-repo. Every claim about what is held is
checked there against the live schema. The form posts to site-api POST /api/legal/request.
"""

BODY = """<div class="vb-wrap">
<article class="vb-prose">
<h1>Law enforcement and legal requests</h1>
<p>As of {asof}. These guidelines are for law-enforcement agencies, courts and lawyers asking verdantbloom.bar for
records about a customer. Customers: this page also tells you exactly what we could be made to hand over, which is
not much.</p>

<h2>1. What we have, and what we do not</h2>
<p><strong>We do not store the content of anything sent to or produced by the API.</strong> No prompts, no replies,
no images or audio, no part or summary of them (see <a href="api-privacy.html">API privacy</a>). No legal process can
get content from us, because none exists. We also do not store IP addresses, device or location data, or card
numbers.</p>
<p>What we can have about an account:</p>
<table class="vb-table">
<thead><tr><th>Records</th><th>What they are</th><th>Process needed (US)</th></tr></thead>
<tbody>
<tr><td>Subscriber</td><td>Email address, account id, level, account status, created and paid-through dates, last
login, payment-processor customer reference, and the keys on the account (first characters, label, created, last
used, revoked)</td><td>Subpoena</td></tr>
<tr><td>Payments</td><td>When the tab was paid, refunded or disputed, and the amounts</td><td>Subpoena</td></tr>
<tr><td>Usage meters</td><td>Per request: time, key, model, token counts, GPU time, charge. No content.</td>
<td>Court order (18 U.S.C. &sect; 2703(d)) or search warrant</td></tr>
<tr><td>Website sessions and login emails</td><td>Times only</td><td>Court order or search warrant</td></tr>
<tr><td>Network match</td><td>We keep a one-way keyed hash of the network an account was created from and that
login emails were requested from, never the address. Given an IP address in valid process, we can say whether it
matches.</td><td>Court order or search warrant</td></tr>
<tr><td>Content</td><td>None held</td><td>&mdash;</td></tr>
</tbody>
</table>
<p>Card details are held by our payment processor, Stripe. Requests for them go to Stripe.</p>

<h2>2. What we need</h2>
<ul>
<li><strong>Valid legal process</strong> issued under US law, matching what is asked for (table above). We narrow or
refuse requests that are broader than the process allows.</li>
<li><strong>Specific identifiers:</strong> the account's email address, or a key's first characters, and a date range.
We cannot search by content (we have none) and we do not search by name.</li>
<li><strong>Who you are:</strong> your agency, name, official email and a phone number. We verify every request by
calling the agency on a number we find ourselves.</li>
<li><strong>Outside the US:</strong> through a Mutual Legal Assistance Treaty or letters rogatory, or a US court
order. We may answer a genuine emergency directly (section 4).</li>
<li><strong>Civil cases:</strong> a subpoena reaches subscriber records only, and we tell the customer first.</li>
</ul>

<h2>3. Preservation</h2>
<p>On a preservation request under 18 U.S.C. &sect; 2703(f) we take a copy of the records we hold for the identifiers
named and keep it for 90 days, renewable once by a further request, while you obtain process.</p>

<h2>4. Emergencies</h2>
<p>If you believe there is an imminent danger of death or serious physical injury, choose &ldquo;Emergency&rdquo; below
and tell us the facts. We may disclose what we hold without process when we believe, in good faith, that the emergency
is real (18 U.S.C. &sect; 2702(c)(4)). Emergency requests reach the operator first in the queue.</p>

<h2>5. Telling the customer</h2>
<p>We tell customers about requests for their records before we answer, and give them 10 days to object, unless the
law or a court order forbids it, or there is an emergency (then we tell them once it is over). If you need us not to
notify, include the non-disclosure order.</p>

<h2>6. What you receive</h2>
<p>An electronic package: the records as JSON, a description of every field and of what is never held, SHA-256
checksums, and, on request, a signed business-records certificate (Federal Rule of Evidence 902(11)).</p>

<h2>7. Send a request</h2>
<p>Use this form, then email the signed legal document to <code>bloom@verdantbloom.bar</code> with the reference you
are given in the subject. The form says the same thing whether or not an account exists: it never looks one up.</p>

<form class="vb-form" method="post" action="/api/legal/request">
<div class="vb-field">
<label for="le-kind">Kind of request</label>
<select class="vb-select" id="le-kind" name="kind" required>
<option value="subpoena">Subpoena</option>
<option value="court_order">Court order (2703(d) or other)</option>
<option value="search_warrant">Search warrant</option>
<option value="preservation">Preservation request (2703(f))</option>
<option value="emergency">Emergency: imminent danger of death or serious injury</option>
<option value="civil">Civil subpoena</option>
<option value="other">Other</option>
</select>
</div>
<div class="vb-field">
<label for="le-agency">Agency or firm</label>
<input class="vb-input" id="le-agency" name="agency" maxlength="120" required autocomplete="organization">
</div>
<div class="vb-field">
<label for="le-name">Your name and title</label>
<input class="vb-input" id="le-name" name="name" maxlength="120" required autocomplete="name">
</div>
<div class="vb-field">
<label for="le-email">Official email</label>
<input class="vb-input" id="le-email" name="email" type="email" maxlength="254" required autocomplete="email" autocapitalize="none" spellcheck="false">
<p class="vb-field__help">We send the reference here. We verify every request by phone before answering.</p>
</div>
<div class="vb-field">
<label for="le-phone">Phone (optional)</label>
<input class="vb-input" id="le-phone" name="phone" maxlength="40" autocomplete="tel">
</div>
<div class="vb-field">
<label for="le-jurisdiction">Jurisdiction: court, state, country</label>
<input class="vb-input" id="le-jurisdiction" name="jurisdiction" maxlength="120" required>
</div>
<div class="vb-field">
<label for="le-case">Case or docket number (optional)</label>
<input class="vb-input" id="le-case" name="case_ref" maxlength="120">
</div>
<div class="vb-field">
<label for="le-ids">Identifiers</label>
<textarea class="vb-textarea" id="le-ids" name="identifiers" rows="3" maxlength="1000" required></textarea>
<p class="vb-field__help">Account email addresses or key prefixes, one per line.</p>
</div>
<div class="vb-field">
<label for="le-from">From (optional)</label>
<input class="vb-input" id="le-from" name="date_from" type="date">
</div>
<div class="vb-field">
<label for="le-to">To (optional)</label>
<input class="vb-input" id="le-to" name="date_to" type="date">
</div>
<div class="vb-field">
<label for="le-details">What you are asking for</label>
<textarea class="vb-textarea" id="le-details" name="details" rows="6" maxlength="4000" required></textarea>
<p class="vb-field__help">For an emergency, the facts that make it one.</p>
</div>
<div class="vb-hp" aria-hidden="true">
<label for="le-website">Leave this one empty</label>
<input id="le-website" name="website" type="text" tabindex="-1" autocomplete="off" value="">
</div>
<label class="vb-check"><input type="checkbox" name="attest" required> <span>I am authorised to make this request on behalf of the agency or firm named above.</span></label>
<p><button class="vb-btn vb-btn--primary" type="submit">SEND THE REQUEST</button></p>
</form>
<p class="vb-fine">What you type here is kept with the request for three years after it is closed, then deleted. It is
never linked to a customer's account unless we produce records in answer to it.</p>
</article>
</div>
"""
