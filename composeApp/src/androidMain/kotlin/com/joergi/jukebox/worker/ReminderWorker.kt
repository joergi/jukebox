package com.joergi.jukebox.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.joergi.jukebox.model.CollectionItem
import com.joergi.jukebox.service.NotificationService
import com.joergi.jukebox.storage.SecureStorage
import com.joergi.jukebox.storage.StorageKeys
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * WorkManager worker that fires a single random-record reminder notification.
 *
 * Scheduled as a [androidx.work.PeriodicWorkRequest] by [ReminderScheduler].
 * Each execution asks [ReminderItemProvider] for a random (artist, title, thumbnailUrl, instanceId) quadruple
 * and delegates to [NotificationService.showRandomRecordNotification].
 *
 * Additionally, this worker persists the selected item to the history storage so that
 * it appears in the "Selected Records History" section when the app is opened.
 *
 * If the collection is empty (provider returns null) the worker exits silently —
 * it will be retried at the next scheduled interval without any user-visible error.
 * 
 * **Cron-like Scheduling Note:**
 * This worker is designed to fire at cron-aligned slots (e.g., :00/:15/:30/:45 for 15-minute interval).
 * However, due to WorkManager's internal batching and the device's doze/throttling behavior,
 * the actual execution time may vary. The worker currently executes whenever WorkManager
 * dispatches it, which should generally be within a few minutes of the intended slot.
 * For stricter timing requirements, consider using exact alarms (if permitted) or
 * moving to a different scheduling mechanism.
 */
class ReminderWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private val json = Json { ignoreUnknownKeys = true }
    
    companion object {
        private const val TAG = "ReminderWorker"
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "doWork() called - requesting random item from provider")
        var item = ReminderItemProvider.getRandomItem()
        
        // If provider is null (app was killed), try to load collection from storage cache
        if (item == null) {
            Log.w(TAG, "Provider is null - attempting to load collection from cache")
            item = loadRandomItemFromCache()
        }
        
        if (item == null) {
            Log.w(TAG, "No items available from provider or cache - exiting silently")
            return Result.success()
        }
        
        val (artist, title, thumbnailUrl, instanceId) = item
        Log.d(TAG, "Received random item: artist='$artist', title='$title', thumbnailUrl='$thumbnailUrl', instanceId=$instanceId")
        
        // Show the notification
        Log.d(TAG, "Calling NotificationService.showRandomRecordNotification with instanceId=$instanceId, thumbnail=$thumbnailUrl")
        NotificationService.showRandomRecordNotification(artist, title, thumbnailUrl, instanceId)
        
        // Persist to history for display in app
        Log.d(TAG, "Adding to history: title='$title', instanceId=$instanceId")
        addToHistory(artist, title, thumbnailUrl, instanceId)
        
        return Result.success()
    }
    
    /**
     * Loads a random item from the cached collection if the provider is unavailable.
     * This handles the case where the app was killed before the notification fired.
     */
    private suspend fun loadRandomItemFromCache(): com.joergi.jukebox.service.Quadruple<String, String, String?, Int>? {
        try {
            val username = ReminderItemProvider.getUsername()
            if (username == null) {
                Log.w(TAG, "Username not set - cannot load from cache")
                return null
            }
            
            val storage = SecureStorage(applicationContext)
            val collectionJson = storage.read(StorageKeys.collectionCache(username))
            if (collectionJson.isNullOrEmpty()) {
                Log.w(TAG, "No cached collection found for user '$username'")
                return null
            }
            
            val items = runCatching {
                json.decodeFromString<List<CollectionItem>>(collectionJson)
            }.getOrNull()
            
            if (items.isNullOrEmpty()) {
                Log.w(TAG, "Cached collection is empty for user '$username'")
                return null
            }
            
            val picked = items.random()
            val artist = picked.artists.joinToString(", ").ifBlank { "Unknown Artist" }
            Log.d(TAG, "Loaded random item from cache: artist='$artist', title='${picked.title}', thumb='${picked.thumb}'")
            return com.joergi.jukebox.service.Quadruple(artist, picked.title, picked.thumb, picked.instanceId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load random item from cache: ${e.message}", e)
            return null
        }
    }
    
    /**
     * Adds the selected record to the history storage.
     * This ensures the item appears in the "Selected Records History" section.
     */
    private suspend fun addToHistory(
        artist: String,
        title: String,
        thumbnailUrl: String?,
        instanceId: Int
    ) {
        try {
            val username = ReminderItemProvider.getUsername() ?: run {
                // Username not set - can't save history
                return
            }
            
            val storage = SecureStorage(applicationContext)
            
            // Read current history from storage
            val historyJson = storage.read(StorageKeys.selectedRecordsHistory(username))
            val currentHistory = historyJson?.let {
                runCatching {
                    json.decodeFromString<List<CollectionItem>>(it)
                }.getOrNull() ?: emptyList()
            } ?: emptyList()
            
            // Create a minimal CollectionItem for the picked record
            // We only have basic info, but that's enough for history display
            val pickedItem = CollectionItem(
                instanceId = instanceId,
                id = 0, // We don't have the collection ID, but instanceId is the key identifier
                title = title,
                artists = listOf(artist),
                formats = emptyList(),
                thumb = thumbnailUrl,
                year = null,
                label = null,
            )
            Log.d(TAG, "Created CollectionItem for history: instanceId=${pickedItem.instanceId}, title='${pickedItem.title}'")
            
            // Prepend new item and deduplicate by instanceId
            val updatedHistory = (listOf(pickedItem) + currentHistory).distinctBy { it.instanceId }
            Log.d(TAG, "Updated history size: ${updatedHistory.size} (was ${currentHistory.size})")
            
            // Persist updated history
            val updatedJson = json.encodeToString(updatedHistory)
            storage.write(StorageKeys.selectedRecordsHistory(username), updatedJson)
            Log.d(TAG, "History successfully persisted to storage for user '$username'")
            
        } catch (e: Exception) {
            // Fail silently - history tracking is nice-to-have, not critical
            // The notification will still be shown successfully
        }
    }
}
