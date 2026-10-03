"""Terms of service (legal/terms.html). Written from what the running system does (contracts/levels.md,
contracts/payments.md, contracts/keys.md in the verdantbloom super-repo), not from plans. Every number is
filled in by build.py from the live sources. Not reviewed by a lawyer: see the note in TODO.md I-3.

Rules for editing: describe only what is built. If the code changes a rule stated here (renewal, refunds,
lapse, limits), this page changes in the same deploy. No braces in the text except the placeholders.
"""

BODY = """<div class="vb-wrap">
<article class="vb-prose">
<h1>Terms</h1>
<p>As of {asof}. These are the terms for using verdantbloom.bar and its API at <code>api.verdantbloom.bar</code>.
By opening an account, using a key, or paying for a level, you agree to them. The
<a href="acceptable-use.html">acceptable-use policy</a> and the <a href="api-privacy.html">API privacy page</a> are part
of these terms. Questions: <code>bloom@verdantbloom.bar</code>.</p>

<h2>1. Who and what</h2>
<p>verdantbloom.bar (&ldquo;we&rdquo;) is a business{address_clause}. We sell metered access to an OpenAI-compatible text
API running open-weight language models on rented GPUs. It is a fiction-writing and developer API. Today it serves one
model, <code>{tap}</code>, plus free text-to-speech. We may add, replace or retire models; what is served is always listed
on the <a href="../pricing.html">prices page</a>.</p>

<h2>2. Age</h2>
<p>You must be 18 or older to open an account or use a key.</p>

<h2>3. Your account and keys</h2>
<ul>
<li>You log in with a link we email to you. One account per person: we key accounts by mailbox, so variants of the same
address are the same account, and we limit how many new accounts one network can open per day.</li>
<li>Your keys are yours to keep secret. Anything done with a key on your account is your responsibility, and is charged
to your tab, until you revoke the key on your account page. Revoking takes effect at once.</li>
<li>Do not share, sell or resell keys or access.</li>
</ul>

<h2>4. Levels and the tab</h2>
<ul>
<li>A level is bought for a <strong>calendar month</strong> (UTC). Paying puts money on your <strong>tab</strong> for that
month, and the requests you make that month are charged against it. Level prices: {level_prices}. Walk-In is free.</li>
<li>Bought partway through a month, a level costs the remaining days' share of the month's price (a one-off payment is at
least $0.50, the card processor's minimum), and the tab for that month is what you paid.</li>
<li>Each request is charged its tokens at the rates on the <a href="../pricing.html">prices page</a> (today
{rate_in} per million input tokens and {rate_out} per million output tokens), or a minimum for the GPU time it holds,
whichever is more (today about {min_warm} a request, about {min_cold} for the request that wakes a cold model). Your
whole conversation is sent and billed as input on every request. Thinking tokens are billed as output.</li>
<li>Before a request runs we hold an estimate of its cost on your tab, then charge what it actually used. When your tab
cannot cover a request, the request is refused (HTTP 402).</li>
<li><strong>Unused tab does not roll over</strong> and is not paid back. It expires at the end of its month.</li>
<li>When the month you paid for ends and the next one is not paid, your account continues as Walk-In until you pay
again. Nothing is deleted.</li>
<li>Each level has limits (requests per minute, at once, per day; context; reply length), listed on the prices page.
Requests over a limit are refused, not charged.</li>
<li>Prices exclude any sales tax or VAT. If we must collect tax, it is shown at checkout before you pay.</li>
</ul>

<h2>5. Automatic renewal</h2>
<p>Renewal is <strong>off</strong> unless you turn it on when you pay for a level. If you turn it on, your level renews on the
<strong>1st of every month</strong> and your card is charged the level's full monthly price ({level_prices}) until you turn
renewal off. Turn it off any time on your account page; the month already paid runs to its end, and you are not charged
again. Payments are processed by Stripe; we never see your card number.</p>

<h2>6. Refunds and chargebacks</h2>
<ul>
<li>Payments are not refundable once a month's tab is credited, except where the law requires a refund or where we
choose to give one. If something went wrong on our side, write to <code>bloom@verdantbloom.bar</code> and we will look
at it.</li>
<li>A refund takes its amount back off the tab it paid for. A full refund of a month means that month is no longer paid,
and the account continues as Walk-In.</li>
<li>If you dispute a payment with your bank (a chargeback), the disputed amount is taken off your tab and the account is
suspended while the dispute is open. Please write to us first; most problems are quicker to fix that way.</li>
</ul>

<h2>7. What we do not promise</h2>
<ul>
<li><strong>No uptime or speed guarantee.</strong> Models run on GPUs we rent by the minute. When nobody has used a model
for {idle_min} minutes it is shut down; the next request wakes it, which takes about {boot_med} seconds and sometimes
{boot_p90} or more. Walk-In requests are served only while a model is already running.</li>
<li>GPU servers are shared between accounts, and rented capacity can be unavailable. A request may wait, fail, or be
refused while a model starts.</li>
<li>Models make mistakes. Output can be wrong, offensive, or not what you asked for. Do not rely on it for medical, legal,
financial or safety decisions.</li>
</ul>

<h2>8. Your content</h2>
<ul>
<li>What you send and what the model writes back are yours, as far as anyone owns them. We claim no rights in them,
and we do not store or log them (see <a href="api-privacy.html">API privacy</a>).</li>
<li>You are responsible for what you generate and for what you publish, send or do with it, including following the
law where you are.</li>
<li>The models are made by third parties and published under their own licences. Their makers are not party to these
terms and have not endorsed this service.</li>
</ul>

<h2>9. Suspension and closing</h2>
<ul>
<li>We may suspend an account while we look into a suspected breach of these terms or the acceptable-use policy, and
close it if the breach is real. An account closed for a breach of the acceptable-use policy's &ldquo;Never&rdquo; section
gets no refund.</li>
<li>You can stop at any time: turn off renewal, revoke your keys, or write to us to close your account.</li>
<li>If we ever stop offering the service, we will tell account holders by email first and refund the unused part of any
month already paid.</li>
</ul>

<h2>10. Changes</h2>
<p>We may change prices, limits and these terms. A price increase never applies to a month you have already paid for,
and for renewing accounts we give at least 30 days' notice by email before it applies. Other changes take effect when
they are posted here, and the date at the top changes with them. If you do not agree to a change, stop using the
service and turn off renewal.</p>

<h2>11. Disclaimer</h2>
<p>The service is provided <strong>as is</strong> and <strong>as available</strong>, without warranties of any kind,
express or implied, including merchantability, fitness for a particular purpose and non-infringement, to the extent the
law allows.</p>

<h2>12. Limit of liability</h2>
<p>To the extent the law allows, we are not liable for indirect, incidental, special, consequential or punitive damages,
or for lost profits, data or goodwill. Our total liability for any claim about the service is limited to what you paid
us in the three months before the claim arose.</p>

<h2>13. Your responsibility to us</h2>
<p>You agree to cover our reasonable costs, including legal fees, from claims by others that arise from your use of the
service, your content, or your breach of these terms or the acceptable-use policy.</p>

<h2>14. Law</h2>
<p>These terms are governed by the laws of the Commonwealth of Pennsylvania, USA, without regard to its conflict-of-law
rules. Disputes go to the state or federal courts for Montgomery County, Pennsylvania, unless the law where you live
gives you the right to bring them elsewhere.</p>

<h2>15. The rest</h2>
<p>If part of these terms cannot be enforced, the rest still applies. Not enforcing a term is not giving it up. These
terms, with the acceptable-use policy and the API privacy page, are the whole agreement between you and us about the
service.</p>
</article>
</div>
"""
