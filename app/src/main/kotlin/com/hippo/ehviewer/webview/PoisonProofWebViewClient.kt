package com.hippo.ehviewer.webview

import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import com.google.accompanist.web.AccompanistWebViewClient
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.dns.DomainFrontingSslSocketFactory
import com.hippo.ehviewer.dns.PoisonProofDns
import com.hippo.ehviewer.ktor.platformTrustManager
import java.io.FilterInputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Replays the WebView's requests through the app's own client.
 *
 * The WebView resolves names with the system resolver, so on a poisoned
 * network every E-Hentai page it loads fails at the TLS handshake no matter
 * what [PoisonProofDns] would answer. Pulling the page through OkHttp, which
 * carries the built-in address table and DNS-over-HTTPS, is the only way to
 * reach it without a proxy.
 *
 * The WebView shares the app's cookies (both go through
 * `android.webkit.CookieManager`), so no cookie copying is needed; the cookie
 * is put on the replayed request explicitly because the WebView keeps it out
 * of [WebResourceRequest.getRequestHeaders].
 */
class PoisonProofWebViewClient(
    private val recorder: WebViewRequestRecorder,
) : AccompanistWebViewClient() {
    private val http: OkHttpClient by lazy { buildClient() }

    override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
        // Installed after the load on purpose: what has to be hooked is the
        // form the user submits, which cannot happen before the page is up.
        runCatching { view.evaluateJavascript(WebViewRequestInspector.JS, null) }
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        val host = url.host?.lowercase() ?: return null
        if (!isEhHost(host)) return null
        val method = request.method.uppercase()
        if (method != "GET" && method != "POST") return null

        val builder = Request.Builder().url(url.toString())
        request.requestHeaders.forEach { (name, value) ->
            if (name.lowercase() !in REQUEST_HEADERS_OWNED_BY_CLIENT) {
                runCatching { builder.header(name, value) }
            }
        }
        cookieHeader(url.toString())?.let { builder.header("Cookie", it) }

        if (method == "POST") {
            // shouldInterceptRequest cannot see the POST body, so replay it
            // from what the injected script recorded just before submit. With
            // nothing recorded the request is handed back to the WebView
            // rather than sent with a payload that would corrupt the form.
            val form = recorder.take(method, url.toString()) ?: return null
            builder.post(form.toRequestBody(FORM_CONTENT_TYPE))
        }

        val response = try {
            http.newCall(builder.build()).execute()
        } catch (e: IOException) {
            return null
        }
        val body = response.body
        if (body == null) {
            response.close()
            return null
        }

        for (setCookie in response.headers.values("Set-Cookie")) {
            runCatching { CookieManager.getInstance().setCookie(url.toString(), setCookie) }
        }

        val contentType = response.header("Content-Type").orEmpty()
        val mimeType = contentType.substringBefore(';').trim().ifEmpty { "text/html" }
        val charset = CHARSET.find(contentType)?.groupValues?.get(1)?.trim()?.ifEmpty { null } ?: "utf-8"
        val headers = HashMap<String, String>()
        for (name in response.headers.names()) {
            if (name.lowercase() in RESPONSE_HEADERS_DROPPED) continue
            headers[name] = response.headers.values(name).joinToString(", ")
        }

        // The stream is handed to the WebView, which reads it asynchronously:
        // close the OkHttp response with it instead of before returning.
        val stream = object : FilterInputStream(body.byteStream()) {
            override fun close() {
                runCatching { super.close() }
                response.close()
            }
        }
        return WebResourceResponse(
            mimeType,
            charset,
            response.code,
            response.message.ifBlank { "OK" },
            headers,
            stream,
        )
    }

    private fun cookieHeader(url: String): String? =
        runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()?.ifBlank { null }

    private fun isEhHost(host: String): Boolean = EH_HOSTS.any { host == it || host.endsWith(".$it") }

    private fun buildClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .apply {
            // Same resolution and fronting choices the browsing client makes,
            // so the page behaves the same inside the WebView.
            if (Settings.builtInHosts.value || Settings.doh.value) {
                dns(PoisonProofDns())
            }
            if (Settings.domainFronting.value) {
                val trustManager = platformTrustManager()
                sslSocketFactory(DomainFrontingSslSocketFactory(trustManager), trustManager)
            }
        }
        .build()

    private companion object {
        val FORM_CONTENT_TYPE = "application/x-www-form-urlencoded".toMediaType()
        val CHARSET = Regex("charset=([^;]+)", RegexOption.IGNORE_CASE)

        /** Hosts whose requests are worth replaying; every other host is left to the WebView. */
        val EH_HOSTS = listOf("e-hentai.org", "exhentai.org", "ehgt.org")

        /**
         * Headers OkHttp sets itself, or that describe the WebView's own
         * connection rather than the request.
         */
        val REQUEST_HEADERS_OWNED_BY_CLIENT = setOf(
            "cookie",
            "host",
            "content-length",
            "connection",
            "accept-encoding",
            "transfer-encoding",
            "upgrade",
            "proxy-connection",
        )

        /**
         * Response headers that would contradict the body handed to the
         * WebView: OkHttp has already decoded it, and cookies are written to
         * CookieManager instead of being replayed as a header.
         */
        val RESPONSE_HEADERS_DROPPED = setOf(
            "content-length",
            "content-encoding",
            "transfer-encoding",
            "connection",
            "keep-alive",
            "set-cookie",
            "alt-svc",
        )
    }
}

/**
 * Body store for requests the WebView cannot describe itself.
 *
 * The injected script calls [recordPayload] before a submit/XHR/fetch leaves
 * the page; [PoisonProofWebViewClient] reads the entry back a moment later.
 * A single JSON interface method keeps the bridge surface as small as
 * possible, and [JavascriptInterface] is the only annotation that exposes it.
 */
class WebViewRequestRecorder {
    private val bodies = ConcurrentHashMap<String, String>()

    @JavascriptInterface
    fun recordPayload(method: String, url: String, body: String) {
        if (body.isEmpty()) return
        // Nothing on a settings page legitimately needs more than a handful of
        // recorded bodies; clearing keeps a long-lived page from growing one.
        if (bodies.size > MAX_ENTRIES) bodies.clear()
        bodies[key(method, url)] = body
    }

    fun take(method: String, url: String): String? {
        bodies.remove(key(method, url))?.let { return it }
        // The action recorded in the page and the URL the WebView reports can
        // differ in trivial ways (a fragment, for instance), so retry without
        // it before giving up and letting the WebView send the request.
        return bodies.remove(key(method, url.substringBefore('#')))
    }

    private fun key(method: String, url: String) = method.uppercase() + " " + url

    private companion object {
        const val MAX_ENTRIES = 64
    }
}

/** Script that records request bodies the WebView would not expose to the client. */
object WebViewRequestInspector {
    val JS: String = """
        (function () {
            if (window.__ehInspectorInstalled) { return; }
            window.__ehInspectorInstalled = true;
            function absolute(url) {
                try { return new URL(url, document.baseURI).href; } catch (e) { return String(url); }
            }
            function record(method, url, body) {
                try {
                    if (body === null || body === undefined) { return; }
                    window.ehRecorder.recordPayload(String(method).toUpperCase(), absolute(url), String(body));
                } catch (e) { }
            }
            var xhrOpen = XMLHttpRequest.prototype.open;
            var xhrSend = XMLHttpRequest.prototype.send;
            XMLHttpRequest.prototype.open = function (method, url) {
                this.__ehMethod = method;
                this.__ehUrl = url;
                return xhrOpen.apply(this, arguments);
            };
            XMLHttpRequest.prototype.send = function (body) {
                record(this.__ehMethod || "GET", this.__ehUrl, body);
                return xhrSend.apply(this, arguments);
            };
            if (window.fetch) {
                var rawFetch = window.fetch;
                window.fetch = function (input, init) {
                    try {
                        var url = typeof input === "string" ? input : input.url;
                        var method = (init && init.method) || (input && input.method) || "GET";
                        record(method, url, init && init.body);
                    } catch (e) { }
                    return rawFetch.apply(this, arguments);
                };
            }
            window.addEventListener("submit", function (event) {
                try {
                    var form = event.target;
                    if (!form || !form.action) { return; }
                    var parts = [];
                    var data = new FormData(form);
                    data.forEach(function (value, key) {
                        parts.push(encodeURIComponent(key) + "=" + encodeURIComponent(String(value)));
                    });
                    // FormData omits the button that triggered the submit, but a
                    // browser sends it (the apply button carries a name here,
                    // and the server keys off it).
                    var submitter = event.submitter || null;
                    if (submitter && submitter.name) {
                        parts.push(encodeURIComponent(submitter.name) + "=" + encodeURIComponent(submitter.value || ""));
                    }
                    record(form.method || "POST", form.action, parts.join("&"));
                } catch (e) { }
            }, true);
        })();
    """.trimIndent()
}
