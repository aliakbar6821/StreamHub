package dev.lynx.streamhub

/** Never append a full response or a range for a different offset/entity. */
internal fun validResumeRange(offset: Long, code: Int, contentRange: String?): Boolean {
    if (offset <= 0L) return code == 200
    if (code != 206) return false
    val start = Regex("bytes (\\d+)-\\d+/(?:\\d+|\\*)", RegexOption.IGNORE_CASE)
        .matchEntire(contentRange.orEmpty().trim())?.groupValues?.get(1)?.toLongOrNull()
    return start == offset
}

internal fun retryableHttp(code: Int): Boolean = code == 408 || code == 429 || code in 500..599

/** More time for temporary DNS/connectivity outages; never loop indefinitely. */
internal fun downloadRetryDelay(error: java.io.IOException, failures: Int, httpCode: Int? = null): Long? {
    if(error is java.net.UnknownHostException) {
        return listOf(5_000L,10_000L,20_000L,40_000L,60_000L,60_000L).getOrNull(failures)
    }
    if(httpCode != null && !retryableHttp(httpCode))return null
    return if(failures in 0..2)1_000L * (1L shl failures) else null
}
