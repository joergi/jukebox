package com.joergi.jukebox.worker

import android.util.Log
import com.joergi.jukebox.service.Quadruple

/**
 * Holds the lambda that supplies a random (artist, title, thumbnailUrl, instanceId) quadruple at reminder time.
 *
 * WorkManager workers are instantiated by the OS with no constructor arguments,
 * so we bridge the gap with this in-process singleton.  The ViewModel sets the
 * provider when scheduling a reminder and clears it on cancellation.
 *
 * The provider is deliberately kept as a plain `@Volatile` reference rather than
 * a coroutine channel so it is safe to read from the WorkManager thread pool.
 */
object ReminderItemProvider {

    private const val TAG = "ReminderItemProvider"

    @Volatile
    private var provider: (() -> Quadruple<String, String, String?, Int>?)? = null
    
    @Volatile
    private var username: String? = null

    /** Called by [NotificationService] when a reminder is scheduled. */
    fun setProvider(block: () -> Quadruple<String, String, String?, Int>?) {
        provider = block
        Log.d(TAG, "setProvider() called - provider is now ${if (block != null) "SET" else "NULL"}")
    }
    
    /** Sets the username for history tracking. Called by ViewModel when scheduling reminders. */
    fun setUsername(user: String) {
        username = user
        Log.d(TAG, "setUsername() called - username set to '$user'")
    }

    /** Returns the current random item, or null if no provider is set / collection is empty. */
    fun getRandomItem(): Quadruple<String, String, String?, Int>? {
        Log.d(TAG, "getRandomItem() called - provider is ${if (provider != null) "SET" else "NULL"}")
        val result = provider?.invoke()
        Log.d(TAG, "getRandomItem() result: ${if (result != null) "artist='${result.first}', title='${result.second}', instanceId=${result.fourth}" else "NULL"}")
        return result
    }
    
    /** Returns the current username, or null if not set. */
    fun getUsername(): String? = username

    /** Called by [NotificationService] when the reminder is cancelled. */
    fun clear() {
        provider = null
        username = null
        Log.d(TAG, "clear() called - provider and username cleared")
    }
}
