package com.sal7one.transiber.translation

import com.sal7one.common_jni.speech.TranslationDirection
import com.sal7one.common_jni.translation.CancellableTextTranslator
import com.sal7one.transiber.byok.ByokPolicy
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One cancellable owner per check/turn. No redirects, retries, logging or source-text fallback. */
internal class TranslationHttpTransport(
    private val enabled: Boolean = ByokPolicy.FEATURE_BYOK,
    private val client: OkHttpClient = sharedClient,
) : AutoCloseable {
    private val calls = mutableSetOf<Call>()
    private var closed = false
    suspend fun execute(request: Request, secret: String = ""): String = suspendCancellableCoroutine { continuation ->
        if (!enabled) { continuation.resumeWithException(IllegalStateException("Cloud translation is unavailable in the offline build.")); return@suspendCancellableCoroutine }
        val call = client.newCall(request)
        synchronized(calls) {
            if (closed) { continuation.resumeWithException(IllegalStateException("Cloud translation connection is closed")); return@suspendCancellableCoroutine }
            calls += call
        }
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                synchronized(calls) { calls -= call }
                if (continuation.isActive) continuation.resumeWithException(IOException(redact(e.message ?: e.toString(), secret)))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val value = response.use {
                        val body = it.body ?: error("HTTP ${it.code}: empty response body")
                        val bytes = body.byteStream().use { stream ->
                            val out = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = stream.read(buffer)
                                if (count < 0) break
                                check(out.size() + count <= 2 * 1024 * 1024) { "Cloud translation response exceeds 2 MiB" }
                                out.write(buffer, 0, count)
                            }
                            out.toByteArray()
                        }
                        val text = bytes.toString(Charsets.UTF_8)
                        check(it.isSuccessful) { "HTTP ${it.code}: ${redact(text, secret)}" }
                        redact(text, secret)
                    }
                    if (continuation.isActive) continuation.resume(value)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException(redact(e.message ?: e.toString(), secret), e))
                } finally { synchronized(calls) { calls -= call } }
            }
        })
    }
    override fun close() { synchronized(calls) { closed = true; calls.forEach { it.cancel() }; calls.clear() } }
    companion object {
        private val sharedClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).connectTimeout(10, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS).build()
        private fun redact(value: String, secret: String): String = if (secret.isBlank()) value else value.replace(secret, "[redacted key]")
    }
}

private const val MAX_RETRY_WAIT_MS = 8_000L

internal class CloudTextTranslator(
    private val connection: CloudTranslationConnection,
    private val languages: CloudTranslationLanguages,
    private val transport: TranslationHttpTransport = TranslationHttpTransport(),
) : CancellableTextTranslator {
    init { check(ByokPolicy.FEATURE_BYOK) { "Cloud translation is unavailable in the offline build." } }
    override val id: String get() = connection.provider.label
    override val directions: Set<TranslationDirection> = languages.sourceLanguages.flatMap { from ->
        languages.targetLanguages.filter { to -> languages.supports(from, to) && from != to }.map { TranslationDirection(from, it) }
    }.toSet()
    override suspend fun translate(text: String, direction: TranslationDirection): String {
        val request = CloudTranslationProtocol.translateRequest(connection, text, direction.source, direction.target, languages)
        val raw = try { transport.execute(request, connection.key) }
        catch (e: IllegalStateException) {
            val message = e.message.orEmpty()
            // Low account tiers allow a few requests a minute while captions
            // change every few seconds: wait the hinted time once, not drop the line.
            val wait = retryDelayMs(message) ?: throw IllegalStateException(friendly(message), e)
            kotlinx.coroutines.delay(wait)
            try { transport.execute(request, connection.key) }
            catch (again: IllegalStateException) { throw IllegalStateException(friendly(again.message.orEmpty()), again) }
        }
        val parsed = CloudTranslationProtocol.parseTranslation(connection.provider, raw)
        val translated = if (connection.provider == TextTranslationProvider.GOOGLE)
            androidx.core.text.HtmlCompat.fromHtml(parsed, androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim()
        else parsed
        return translated.also { check(it.isNotBlank()) { "${connection.provider.label} returned empty translation" } }
    }
    /** Only a rate limit is worth waiting for; an empty balance or a bad key is not. */
    private fun retryDelayMs(message: String): Long? {
        if (!message.startsWith("HTTP 429") || outOfCredit(message)) return null
        val seconds = Regex("try again in ([0-9.]+)\\s*(ms|s)").find(message)?.let { match ->
            match.groupValues[1].toDoubleOrNull()?.let { if (match.groupValues[2] == "ms") it / 1000 else it }
        } ?: 2.0
        return (seconds * 1000).toLong().coerceIn(250, MAX_RETRY_WAIT_MS)
    }

    /** A rate-limit body also links to billing, so match the error codes, not prose. */
    private fun outOfCredit(message: String) =
        "insufficient_quota" in message || "credit_balance" in message || "credit balance is too low" in message

    /** Provider bodies are long JSON; the overlay has room for one short line. */
    private fun friendly(message: String): String {
        val name = connection.provider.label
        return when {
            message.startsWith("HTTP 401") || message.startsWith("HTTP 403") -> "$name rejected the API key. Check it in Translation settings."
            outOfCredit(message) -> "$name: no credit left on this API account."
            message.startsWith("HTTP 429") -> "$name: rate limit reached; some lines stay untranslated."
            message.startsWith("HTTP 5") || message.startsWith("HTTP 529") -> "$name is having trouble right now."
            else -> message.lineSequence().first().take(160)
        }
    }

    override fun cancel() = transport.close()
    override fun close() = transport.close()
}
