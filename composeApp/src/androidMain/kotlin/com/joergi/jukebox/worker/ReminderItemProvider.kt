package com.joergi.jukebox.worker

/**
 * Holds the lambda that supplies a random (artist, title) pair at reminder time.
 *
 * WorkManager workers are instantiated by the OS with no constructor arguments,
 * so we bridge the gap with this in-process singleton.  The ViewModel sets the
 * provider when scheduling a reminder and clears it on cancellation.
 *
 * The provider is deliberately kept as a plain `@Volatile` reference rather than
 * a coroutine channel so it is safe to read from the WorkManager thread pool.
 */
object ReminderItemProvider {

    @Volatile
    private var provider: (() -> Pair<String, String>?)? = null

    /** Called by [NotificationService] when a reminder is scheduled. */
    fun setProvider(block: () -> Pair<String, String>?) {
        provider = block
    }

    /** Returns the current random item, or null if no provider is set / collection is empty. */
    fun getRandomItem(): Pair<String, String>? = provider?.invoke()

    /** Called by [NotificationService] when the reminder is cancelled. */
    fun clear() {
        provider = null
    }
}
