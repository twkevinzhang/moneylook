package tw.kevinzhang.extension_runtime.browser

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.ByteString.Companion.decodeBase64
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale

internal data class BrowserOpenRequest(
    val url: String,
    val timeoutMs: Long,
    val settleMs: Long,
)

internal data class BrowserOpenResponse(
    val url: String,
    val origin: String,
)

internal data class BrowserFormPostRequest(
    val url: String,
    val body: ByteArray,
    val timeoutMs: Long,
    val settleMs: Long,
)

internal data class BrowserXhrRequest(
    val url: String,
    val method: String,
    val headers: Map<String, List<String>>,
    val body: String?,
    val bodyEncoding: String,
    val responseEncoding: String,
    val timeoutMs: Long,
    val withCredentials: Boolean,
)

internal data class BrowserXhrResponse(
    val status: Int,
    val statusText: String,
    val headers: Map<String, List<String>>,
    val body: String,
    val bodyEncoding: String,
    val url: String,
)

internal class SafeBrowserException(
    val code: String,
    message: String,
) : Exception(message)

internal object BrowserBridgeRequestValidator {
    fun validate(json: String) {
        if (json.length > MAX_REQUEST_JSON_CHARS) {
            throw SafeBrowserException("REQUEST_TOO_LARGE", "browser request is too large")
        }
    }

    const val MAX_REQUEST_JSON_CHARS = 4 * 1024 * 1024
}

internal object BrowserResponseValidator {
    fun validate(response: BrowserXhrResponse): BrowserXhrResponse {
        val bodySize = when (response.bodyEncoding) {
            "base64" -> response.body.decodeBase64()?.size
                ?: throw SafeBrowserException("BROWSER_ERROR", "browser response encoding is invalid")
            "text" -> response.body.toByteArray(Charsets.UTF_8).size
            else -> throw SafeBrowserException("BROWSER_ERROR", "browser response encoding is invalid")
        }
        if (bodySize > MAX_RESPONSE_BODY_BYTES) {
            throw SafeBrowserException("RESPONSE_TOO_LARGE", "browser response exceeds size limit")
        }
        return response
    }

    const val MAX_RESPONSE_BODY_BYTES = 10 * 1024 * 1024
}

internal object BrowserRequestJsonParser {
    fun parseOpen(json: String, gson: Gson): BrowserOpenRequest {
        val root = parseObject(json, gson)
        val url = root.string("url") ?: throw invalid("url is required")
        validateAbsoluteHttpUrl(url)
        return BrowserOpenRequest(
            url = url,
            timeoutMs = root.long("timeoutMs", DEFAULT_TIMEOUT_MS, 1, MAX_TIMEOUT_MS),
            settleMs = root.long("settleMs", 0, 0, MAX_SETTLE_MS),
        )
    }

    fun parsePost(json: String, gson: Gson): BrowserFormPostRequest {
        val root = parseObject(json, gson)
        val url = root.string("url") ?: throw invalid("url is required")
        validateAbsoluteHttpUrl(url)
        val body = root.string("body") ?: throw invalid("body is required")
        return BrowserFormPostRequest(
            url = url,
            body = encodeUtf8Body(body),
            timeoutMs = root.long("timeoutMs", DEFAULT_TIMEOUT_MS, 1, MAX_TIMEOUT_MS),
            settleMs = root.long("settleMs", DEFAULT_POST_SETTLE_MS, 0, MAX_SETTLE_MS),
        )
    }

    fun parseRequest(json: String, gson: Gson): BrowserXhrRequest {
        val root = parseObject(json, gson)
        val url = root.string("url") ?: throw invalid("url is required")
        validateAbsoluteHttpUrl(url)
        val method = (root.string("method") ?: "GET").uppercase(Locale.US)
        if (!METHOD_PATTERN.matches(method)) {
            throw SafeBrowserException("INVALID_METHOD", "HTTP method is invalid")
        }
        val bodyEncoding = root.string("bodyEncoding") ?: "utf8"
        val responseEncoding = root.string("responseEncoding") ?: "text"
        if (bodyEncoding !in BODY_ENCODINGS || responseEncoding !in RESPONSE_ENCODINGS) {
            throw SafeBrowserException("INVALID_ENCODING", "request encoding is invalid")
        }
        val body = root.string("body")
        validateBody(body, bodyEncoding)
        return BrowserXhrRequest(
            url = url,
            method = method,
            headers = parseHeaders(root.get("headers")),
            body = body,
            bodyEncoding = bodyEncoding,
            responseEncoding = responseEncoding,
            timeoutMs = root.long("timeoutMs", DEFAULT_TIMEOUT_MS, 1, MAX_TIMEOUT_MS),
            withCredentials = root.boolean("withCredentials", true),
        )
    }

    private fun parseObject(json: String, gson: Gson): JsonObject {
        return try {
            gson.fromJson(json, JsonObject::class.java)
        } catch (e: Exception) {
            throw invalid("request must be valid JSON")
        } ?: throw invalid("request must be an object")
    }

    private fun validateAbsoluteHttpUrl(url: String) {
        if (url.length > MAX_URL_CHARS) {
            throw SafeBrowserException("URL_TOO_LARGE", "URL exceeds size limit")
        }
        val parsed = url.toHttpUrlOrNull()
            ?: throw SafeBrowserException("INVALID_URL", "URL is invalid")
        if (parsed.scheme != "http" && parsed.scheme != "https") {
            throw SafeBrowserException("INVALID_URL", "only HTTP and HTTPS URLs are supported")
        }
    }

    private fun validateBody(body: String?, encoding: String) {
        val size = when (encoding) {
            "base64" -> body?.decodeBase64()?.size
                ?: if (body == null) 0 else throw SafeBrowserException("INVALID_BODY", "body is not valid base64")
            else -> body?.toByteArray(Charsets.UTF_8)?.size ?: 0
        }
        if (size > MAX_REQUEST_BODY_BYTES) {
            throw SafeBrowserException("BODY_TOO_LARGE", "request body exceeds size limit")
        }
    }

    private fun encodeUtf8Body(body: String): ByteArray {
        val buffer = try {
            StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(body))
        } catch (e: CharacterCodingException) {
            throw SafeBrowserException("INVALID_BODY", "body is not valid UTF-8 text")
        }
        if (buffer.remaining() > MAX_REQUEST_BODY_BYTES) {
            throw SafeBrowserException("BODY_TOO_LARGE", "request body exceeds size limit")
        }
        return ByteArray(buffer.remaining()).also(buffer::get)
    }

    private fun parseHeaders(element: JsonElement?): Map<String, List<String>> {
        if (element == null || element.isJsonNull) return emptyMap()
        if (!element.isJsonObject) throw invalid("headers must be an object")
        val headers = element.asJsonObject
        if (headers.size() > MAX_HEADER_COUNT) {
            throw SafeBrowserException("HEADERS_TOO_LARGE", "too many request headers")
        }
        return headers.entrySet().associate { (name, value) ->
            if (name.length > MAX_HEADER_NAME_CHARS) {
                throw SafeBrowserException("HEADERS_TOO_LARGE", "request header is too large")
            }
            val values = when {
                value.isJsonPrimitive && value.asJsonPrimitive.isString -> listOf(value.asString)
                value.isJsonArray -> value.asJsonArray.map {
                    if (!it.isJsonPrimitive || !it.asJsonPrimitive.isString) {
                        throw invalid("header values must be strings")
                    }
                    it.asString
                }
                else -> throw invalid("header values must be strings")
            }
            if (values.isEmpty() || values.any { it.length > MAX_HEADER_VALUE_CHARS }) {
                throw SafeBrowserException("HEADERS_TOO_LARGE", "request header is too large")
            }
            name to values
        }
    }

    private fun JsonObject.string(name: String): String? = get(name)?.takeUnless { it.isJsonNull }?.let {
        if (!it.isJsonPrimitive || !it.asJsonPrimitive.isString) {
            throw invalid("$name must be a string")
        }
        it.asString
    }

    private fun JsonObject.boolean(name: String, default: Boolean): Boolean =
        get(name)?.takeUnless { it.isJsonNull }?.let {
            if (!it.isJsonPrimitive || !it.asJsonPrimitive.isBoolean) {
                throw invalid("$name must be a boolean")
            }
            it.asBoolean
        } ?: default

    private fun JsonObject.long(name: String, default: Long, min: Long, max: Long): Long =
        get(name)?.takeUnless { it.isJsonNull }?.let {
            if (!it.isJsonPrimitive || !it.asJsonPrimitive.isNumber) {
                throw invalid("$name must be a number")
            }
            val value = try {
                it.asLong
            } catch (e: NumberFormatException) {
                throw invalid("$name must be a number")
            }
            if (value !in min..max) {
                throw SafeBrowserException("INVALID_TIMEOUT", "$name is outside the allowed range")
            }
            value
        } ?: default

    private fun invalid(message: String) = SafeBrowserException("INVALID_REQUEST", message)

    private val METHOD_PATTERN = Regex("^[!#$%&'*+.^_`|~0-9A-Z-]+$")
    private val BODY_ENCODINGS = setOf("utf8", "base64")
    private val RESPONSE_ENCODINGS = setOf("text", "base64")
    private const val DEFAULT_TIMEOUT_MS = 30_000L
    private const val DEFAULT_POST_SETTLE_MS = 500L
    private const val MAX_TIMEOUT_MS = 30_000L
    private const val MAX_SETTLE_MS = 5_000L
    private const val MAX_URL_CHARS = 16_384
    private const val MAX_REQUEST_BODY_BYTES = 2 * 1024 * 1024
    private const val MAX_HEADER_COUNT = 64
    private const val MAX_HEADER_NAME_CHARS = 256
    private const val MAX_HEADER_VALUE_CHARS = 16_384
}

internal object BrowserXhrScriptBuilder {
    fun start(request: BrowserXhrRequest, slot: String, gson: Gson): String {
        val requestLiteral = gson.toJson(request)
        val slotLiteral = gson.toJson(slot)
        return """
            (function() {
                'use strict';
                const slot = $slotLiteral;
                const options = $requestLiteral;
                const maxResponseBytes = ${BrowserResponseValidator.MAX_RESPONSE_BODY_BYTES};
                let completed = false;
                let responseTooLarge = false;
                const finish = function(value, body, bodyBytes) {
                    if (completed) return;
                    completed = true;
                    window[slot] = {
                        state: 'done',
                        metadata: JSON.stringify(value),
                        body: body || '',
                        bodyBytes: bodyBytes || 0
                    };
                };
                try {
                    const xhr = new window.XMLHttpRequest();
                    window[slot] = { state: 'pending', payload: null, xhr: xhr };
                    xhr.open(options.method, options.url, true);
                    xhr.timeout = options.timeoutMs;
                    xhr.withCredentials = options.withCredentials;
                    xhr.responseType = options.responseEncoding === 'base64' ? 'arraybuffer' : 'text';
                    Object.keys(options.headers || {}).forEach(function(name) {
                        options.headers[name].forEach(function(value) {
                            xhr.setRequestHeader(name, value);
                        });
                    });
                    const abortOversizedResponse = function() {
                        if (responseTooLarge || completed) return;
                        responseTooLarge = true;
                        xhr.abort();
                    };
                    xhr.onprogress = function(event) {
                        if ((event.lengthComputable && event.total > maxResponseBytes) || event.loaded > maxResponseBytes) {
                            abortOversizedResponse();
                            return;
                        }
                        if (options.responseEncoding === 'text') {
                            try {
                                if (new Blob([xhr.responseText || '']).size > maxResponseBytes) {
                                    abortOversizedResponse();
                                }
                            } catch (_) {}
                        }
                    };
                    xhr.onload = function() {
                        const headers = {};
                        xhr.getAllResponseHeaders().trim().split(/[\r\n]+/).forEach(function(line) {
                            if (!line) return;
                            const separator = line.indexOf(':');
                            if (separator <= 0) return;
                            const name = line.slice(0, separator).trim();
                            const value = line.slice(separator + 1).trim();
                            (headers[name] || (headers[name] = [])).push(value);
                        });
                        let body;
                        if (options.responseEncoding === 'base64') {
                            const bytes = new Uint8Array(xhr.response || new ArrayBuffer(0));
                            let binary = '';
                            const chunkSize = 0x8000;
                            for (let offset = 0; offset < bytes.length; offset += chunkSize) {
                                binary += String.fromCharCode.apply(null, bytes.subarray(offset, offset + chunkSize));
                            }
                            body = btoa(binary);
                        } else {
                            body = xhr.responseText || '';
                        }
                        const bodyBytes = options.responseEncoding === 'base64'
                            ? (xhr.response ? xhr.response.byteLength : 0)
                            : new Blob([body]).size;
                        if (bodyBytes > maxResponseBytes) {
                            finish({ ok: false, code: 'RESPONSE_TOO_LARGE' }, '', bodyBytes);
                            return;
                        }
                        finish({
                            ok: true,
                            response: {
                                status: xhr.status,
                                statusText: xhr.statusText || '',
                                headers: headers,
                                bodyEncoding: options.responseEncoding,
                                url: xhr.responseURL || options.url
                            }
                        }, body, bodyBytes);
                    };
                    xhr.onerror = function() { finish({ ok: false, code: 'BROWSER_NETWORK' }, '', 0); };
                    xhr.ontimeout = function() { finish({ ok: false, code: 'BROWSER_TIMEOUT' }, '', 0); };
                    xhr.onabort = function() {
                        finish({
                            ok: false,
                            code: responseTooLarge ? 'RESPONSE_TOO_LARGE' : 'BROWSER_ABORTED'
                        }, '', responseTooLarge ? maxResponseBytes + 1 : 0);
                    };
                    let body = null;
                    if (options.body !== null) {
                        if (options.bodyEncoding === 'base64') {
                            const binary = atob(options.body);
                            const bytes = new Uint8Array(binary.length);
                            for (let index = 0; index < binary.length; index += 1) {
                                bytes[index] = binary.charCodeAt(index);
                            }
                            body = bytes;
                        } else {
                            body = options.body;
                        }
                    }
                    xhr.send(body);
                    return JSON.stringify({ started: true });
                } catch (_) {
                    finish({ ok: false, code: 'BROWSER_REQUEST' }, '', 0);
                    return JSON.stringify({ started: false });
                }
            })();
        """.trimIndent()
    }

    fun poll(slot: String, gson: Gson): String {
        val slotLiteral = gson.toJson(slot)
        return """
            (function() {
                const value = window[$slotLiteral];
                if (!value) return JSON.stringify({ state: 'missing' });
                return JSON.stringify({
                    state: value.state,
                    metadataLength: value.metadata ? value.metadata.length : 0,
                    bodyLength: value.body ? value.body.length : 0,
                    bodyBytes: value.bodyBytes || 0
                });
            })();
        """.trimIndent()
    }

    fun readChunk(slot: String, field: String, offset: Int, length: Int, gson: Gson): String {
        val slotLiteral = gson.toJson(slot)
        val fieldLiteral = gson.toJson(field)
        return """
            (function() {
                const value = window[$slotLiteral];
                if (!value || value.state !== 'done' || typeof value[$fieldLiteral] !== 'string') return null;
                return value[$fieldLiteral].slice($offset, ${offset + length});
            })();
        """.trimIndent()
    }

    fun cleanup(slot: String, gson: Gson): String {
        val slotLiteral = gson.toJson(slot)
        return """
            (function() {
                const value = window[$slotLiteral];
                if (value && value.xhr) {
                    value.xhr.onload = null;
                    value.xhr.onerror = null;
                    value.xhr.ontimeout = null;
                    value.xhr.onabort = null;
                    value.xhr.onprogress = null;
                    try { value.xhr.abort(); } catch (_) {}
                }
                try { delete window[$slotLiteral]; } catch (_) { window[$slotLiteral] = undefined; }
                return null;
            })();
        """.trimIndent()
    }
}
