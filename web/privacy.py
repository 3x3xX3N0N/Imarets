"""Website privacy page (legal/privacy.html). Replaces the old Kotlin-era page (waitlist, bloom animation,
status sign) on 2026-10-03. Covers the website and the account pages; the API has its own page (api_privacy.py).

Every sentence was checked against the running code on 2026-10-03: site-api (host/site, tables in migrations
001-007), the Caddyfile (no access log), and the static pages (no scripts, nothing from third-party hosts).
If the code changes what it stores, this file changes FIRST.
"""

BODY = """<div class="vb-wrap">
<article class="vb-prose">
<h1>Privacy</h1>
<p>As of {asof}. This page covers the website <code>verdantbloom.bar</code>: its pages, opening an account,
logging in, paying, and the forms. The API at <code>api.verdantbloom.bar</code> has its own page,
<a href="api-privacy.html">API privacy</a>. In one line: we do not store or log what you write to the model, or
what it writes back.</p>

<h2>1. Who</h2>
<p>{operator_sentence} Contact: <a href="mailto:bloom@verdantbloom.bar">bloom@verdantbloom.bar</a>.</p>

<h2>2. Browsing the site</h2>
<p>The pages are plain HTML and CSS. There are no scripts, no cookies, no analytics, no advertising, and nothing is
loaded from anyone else's servers. Our web server keeps no access log, so it does not write down your IP address
or which pages you read.</p>

<h2>3. Your account</h2>
<p>To open a tab you give your email address and we send a link that logs you in. We store:</p>
<ul>
<li>your email address, an account id, and when the account was made and when you last logged in;</li>
<li>each login link, as a one-way hash, with when it was sent and used: the link itself works once, for
{login_min} minutes;</li>
<li>your login session, as a one-way hash, which lasts {session_days} days unless you log out;</li>
<li>a keyed one-way hash of the network you came from when the account was made and when a login link is asked for.
It is not your IP address and cannot be turned back into one. We use it to limit how many free accounts one network
can open.</li>
</ul>
<p>Logging in sets one cookie, <code>vb_session</code>, which only keeps you logged in. It is not used for anything
else and is never shared.</p>
<p>Your keys, your tab and your usage are covered on the <a href="api-privacy.html">API privacy</a> page.</p>

<h2>4. Paying</h2>
<p>Payments go through <strong>Stripe</strong>, on Stripe's own checkout page. Stripe collects your card details
under its own privacy policy; we never see or store your card number. We keep Stripe's customer and subscription
references, whether renewal is on, and the payments and refunds on your tab.</p>

<h2>5. The forms</h2>
<ul>
<li><strong>Law-enforcement and legal requests</strong> (<a href="law-enforcement.html">this form</a>): what the
requester types is kept with the request. It is never linked to a customer's account unless we produce records in
answer to it.</li>
<li><strong>Reduced-fee applications</strong>, when that form is open: the contact you give, the level and rate you ask
for, what you write about why, your country if you give it, and when it arrived.</li>
</ul>
<p>Every form, and the login form, also uses a keyed hash of your network to limit how fast one network can send.</p>
<p><strong>The old waitlist.</strong> Before accounts opened, the site had a waitlist. The few addresses that
confirmed it are kept, with the level they said they wanted, and are on a mailing list at SendGrid so we can write
to them about the opening. Unconfirmed sign-ups are deleted after 30 days. The waitlist is closed; ask and we remove
you.</p>

<h2>6. What the server writes in its logs</h2>
<p>One line per request to the account and form pages: the time, which page, the answer code, how long it took, and
the first few characters of the keyed network hash from section 3. Never what you typed, never your email address,
never your IP address. Those logs are rotated and overwritten after about 30 MB.</p>

<h2>7. Who else handles it</h2>
<ul>
<li><strong>Stripe</strong> processes payments (section 4).</li>
<li><strong>SendGrid (Twilio)</strong> delivers login links and account mail, so it handles your email address.</li>
<li><strong>Vultr</strong> rents us the server the site and its database run on.</li>
<li><strong>Cloudflare</strong> answers for our domain names and forwards mail sent to our address to a
<strong>Google</strong> (Gmail) mailbox, where we read it.</li>
</ul>
<p>We do not sell any of it, share it for advertising, or use it to train models.</p>

<h2>8. How long</h2>
<p>Account records last while your account exists. Billing records (payments, refunds, charges) are kept for at
least seven years, because tax and accounting rules require it. Old login-link and session records are not yet
deleted automatically; we will delete yours if you ask. Legal requests are kept as long as the matter needs.</p>

<h2>9. Your choices</h2>
<p>Write to <a href="mailto:bloom@verdantbloom.bar">bloom@verdantbloom.bar</a> from your account's address to see
what we hold about you, correct it, or close your account. You can log out and turn off renewal yourself on your
<a href="/api/account">account page</a>.</p>

<h2>10. Age</h2>
<p>This site and the service behind it are for people 18 or older.</p>

<h2>11. Changes</h2>
<p>If we change what we collect, this page changes first and the date at the top changes with it. A change that
would collect more about account holders will be announced to them by email.</p>
</article>
</div>
"""
