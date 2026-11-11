package local.ringleader.core

import java.time.Instant

/**
 * One read-only view: the last good value plus the outcome of the latest attempt.
 *
 * - A failed refresh keeps [value] and sets [stale], so the screen keeps showing what it had and says so.
 * - HTTP 403 sets [denied] and keeps the session. HTTP 401 never reaches a section: it ends the session.
 * - [sample] marks demonstration data. Nothing marked as a sample came from a server.
 */
data class ReadSection<T>(
    val value: T? = null,
    val error: String? = null,
    val denied: Boolean = false,
    val stale: Boolean = false,
    val loading: Boolean = false,
    val updatedAt: Instant? = null,
    val sample: Boolean = false,
) {
    /** A new attempt began. The previous value stays visible while it runs. */
    fun started(): ReadSection<T> = copy(loading = true, error = null, denied = false)

    /** The attempt failed. A section that already had a value keeps it and becomes stale. */
    fun failed(message: String, denied: Boolean = false): ReadSection<T> =
        copy(error = message, denied = denied, stale = value != null, loading = false)

    companion object {
        fun <T> loaded(value: T, at: Instant, sample: Boolean = false): ReadSection<T> =
            ReadSection(value = value, updatedAt = at, sample = sample)
    }
}
