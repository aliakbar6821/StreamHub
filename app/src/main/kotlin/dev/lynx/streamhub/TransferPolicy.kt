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
