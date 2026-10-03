"""Acceptable-use policy (legal/acceptable-use.html). Replaces the old Kotlin site's copy.

Positioning (operator decision 2026-10-03): verdantbloom.bar is a developer API for fiction and writing tools.
It is not, and may not be used as, the engine of an adult-content service. That is the line Stripe's
restricted-business list draws ("designed for the purpose of sexual gratification"), and section 3 says so in
plain words. check.py fails the build if section 3 or the footer line goes missing.
"""

BODY = """<div class="vb-wrap">
<article class="vb-prose">
<h1>Acceptable use</h1>
<p>As of {asof}. Short on purpose. It applies to every account and every key, and it is part of the
<a href="terms.html">terms</a>.</p>
<p>verdantbloom.bar is a developer API for fiction and writing tools: an open-weight model behind an
OpenAI-compatible endpoint, for people building and writing software. It is not an adult-content service, and it is not
sold, described or promoted as one.</p>

<h2>1. Who</h2>
<p>18 or older only.</p>

<h2>2. Never</h2>
<ul>
<li>No sexual content involving minors, or involving anyone presented as a minor. Not in fiction, not in roleplay, not
&ldquo;aged up&rdquo;, not as a test.</li>
<li>No sexual content about real people, and nothing made to harass, threaten or impersonate a real person.</li>
<li>Nothing illegal where you are, or where we are.</li>
</ul>
<p>Accounts that do this are closed without refund.</p>

<h2>3. Not for adult-content services</h2>
<p>You may not use the API to build, run or power a product or service whose purpose is sexual content or sexual
gratification. That includes adult or erotic chat and companion apps, erotic story or roleplay generators, and anything
sold or promoted as adult or pornographic content. If that is what you are building, this is the wrong service.</p>

<h2>4. Yours</h2>
<p>You are responsible for what you generate and for what you do with it. The model is tuned for fiction and will write
dark themes, such as horror and violence, when a story calls for them. What you publish, send or act on is on you.</p>

<h2>5. The service itself</h2>
<ul>
<li>Do not keep a model running without using it: no keep-alive pinging, no timed empty requests.</li>
<li>Do not try to read, probe or interfere with another account's traffic, or with the models, the gateway or the
website.</li>
<li>One account, one person. Do not resell or share a key.</li>
<li>Each level has request limits. They are part of the level, not a challenge.</li>
</ul>

<h2>6. What we do about it</h2>
<p>We do not read your requests; we do not store them (see <a href="api-privacy.html">API privacy</a>). We act on
reports, on what we can see of an account (who it is, what it is connected to, how it is used), and on what is public,
such as an app that says it runs on this API. We may suspend an account while we look into a suspected breach, and close
it if the breach is real. An account closed for section 2 or 3 is closed without refund. We report what the law requires
us to report.</p>

<h2>7. Telling us</h2>
<p>If you see this service being used to break section 2 or 3, write to <code>bloom@verdantbloom.bar</code>.</p>
<p>This page will change. The date at the top changes with it.</p>
</article>
</div>
"""
