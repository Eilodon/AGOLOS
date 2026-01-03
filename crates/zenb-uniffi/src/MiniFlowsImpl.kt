package com.pandora.app.flows

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import javax.inject.Inject
import javax.inject.Singleton
import com.pandora.app.logic.MiniFlows
import com.pandora.app.ui.BreathGuideActivity

/**
 * MiniFlowsImpl: Concrete implementation of MiniFlows interface.
 *
 * Integrates with Pandora's existing capabilities:
 * - Breath guidance UI
 * - Spotify integration
 * - Notification system
 * - BLE/NFC flows (future)
 *
 * This implementation provides the bridge between ActionDispatcher
 * and actual Android UI/system components.
 */
@Singleton
class MiniFlowsImpl @Inject constructor(
    private val context: Context,
    private val spotifyService: SpotifyService?,
    private val notificationManager: NotificationManager
) : MiniFlows {
    
    companion object {
        private const val TAG = "MiniFlowsImpl"
        private const val CHANNEL_ID_BREATH = "zenb_breath_guidance"
        private const val CHANNEL_ID_BREAK = "zenb_break_suggestions"
        private const val NOTIFICATION_ID_BREATH = 1001
        private const val NOTIFICATION_ID_BREAK = 1002
    }

    init {
        createNotificationChannels()
    }

    /**
     * Start breath guidance session.
     * Launches BreathGuideActivity with specified parameters.
     */
    override suspend fun startBreathGuidance(
        patternId: String,
        targetBpm: Float,
        durationSec: Int?
    ): Boolean = withContext(Dispatchers.Main) {
        try {
            Log.i(TAG, "Starting breath guidance: pattern=$patternId, bpm=$targetBpm")
            
            // Create intent to launch breath guide activity
            val intent = Intent(context, BreathGuideActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("pattern_id", patternId)
                putExtra("target_bpm", targetBpm)
                durationSec?.let { putExtra("duration_sec", it) }
            }
            
            context.startActivity(intent)
            
            // Show persistent notification during session
            showBreathGuidanceNotification(patternId, targetBpm, durationSec)
            
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start breath guidance", e)
            false
        }
    }

    /**
     * Play Spotify playlist or track.
     * Falls back gracefully if Spotify is unavailable.
     */
    override suspend fun playSpotifyPlaylist(
        playlistUri: String,
        volume: Float
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            if (spotifyService == null) {
                Log.w(TAG, "Spotify service not available")
                return@withContext false
            }
            
            if (!spotifyService.isConnected()) {
                Log.w(TAG, "Spotify not connected, attempting connection")
                val connected = spotifyService.connect()
                if (!connected) {
                    return@withContext false
                }
            }
            
            Log.i(TAG, "Playing Spotify: uri=$playlistUri, volume=$volume")
            
            // Set volume (0.0 - 1.0)
            spotifyService.setVolume(volume)
            
            // Play the playlist/track
            val success = spotifyService.play(playlistUri)
            
            if (success) {
                Log.i(TAG, "Spotify playback started successfully")
            } else {
                Log.w(TAG, "Spotify playback failed")
            }
            
            success
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play Spotify", e)
            false
        }
    }

    /**
     * Show break suggestion to user.
     * Uses notification with action buttons.
     */
    override suspend fun showBreakSuggestion(
        durationSec: Int,
        urgency: Float,
        message: String
    ): Boolean = withContext(Dispatchers.Main) {
        try {
            Log.i(TAG, "Showing break suggestion: duration=${durationSec}s, urgency=$urgency")
            
            // Create notification with action buttons
            val notification = createBreakSuggestionNotification(
                message = message,
                durationSec = durationSec,
                urgency = urgency
            )
            
            notificationManager.notify(NOTIFICATION_ID_BREAK, notification)
            
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show break suggestion", e)
            false
        }
    }

    // ============================================================================
    // Private Helper Methods
    // ============================================================================

    /**
     * Create notification channels for breath guidance and break suggestions.
     */
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Breath guidance channel
            val breathChannel = NotificationChannel(
                CHANNEL_ID_BREATH,
                "Breath Guidance",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Active breath guidance sessions"
                setShowBadge(true)
                enableVibration(false)
                setSound(null, null)
            }
            
            // Break suggestion channel
            val breakChannel = NotificationChannel(
                CHANNEL_ID_BREAK,
                "Break Suggestions",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Suggestions to take breaks for wellbeing"
                setShowBadge(true)
                enableVibration(true)
            }
            
            notificationManager.createNotificationChannel(breathChannel)
            notificationManager.createNotificationChannel(breakChannel)
        }
    }

    /**
     * Show persistent notification during breath guidance session.
     */
    private fun showBreathGuidanceNotification(
        patternId: String,
        targetBpm: Float,
        durationSec: Int?
    ) {
        val patternName = formatPatternName(patternId)
        val durationText = durationSec?.let { "${it / 60} minutes" } ?: "ongoing"
        
        // Intent to return to breath guide activity
        val intent = Intent(context, BreathGuideActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_BREATH)
            .setSmallIcon(android.R.drawable.ic_menu_compass) // Replace with your icon
            .setContentTitle("Breath Guidance Active")
            .setContentText("$patternName at ${targetBpm.toInt()} BPM • $durationText")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Pause",
                createPauseIntent()
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                createStopIntent()
            )
            .build()
        
        notificationManager.notify(NOTIFICATION_ID_BREATH, notification)
    }

    /**
     * Create break suggestion notification with action buttons.
     */
    private fun createBreakSuggestionNotification(
        message: String,
        durationSec: Int,
        urgency: Float
    ): Notification {
        val durationMin = durationSec / 60
        
        // Intent to start break activity
        val breakIntent = Intent(context, BreakActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("duration_sec", durationSec)
        }
        val breakPendingIntent = PendingIntent.getActivity(
            context,
            0,
            breakIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Dismiss action
        val dismissIntent = Intent(context, DismissBreakReceiver::class.java)
        val dismissPendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Snooze action
        val snoozeIntent = Intent(context, SnoozeBreakReceiver::class.java)
        val snoozePendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            snoozeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Priority based on urgency
        val priority = when {
            urgency >= 0.8f -> NotificationCompat.PRIORITY_HIGH
            urgency >= 0.5f -> NotificationCompat.PRIORITY_DEFAULT
            else -> NotificationCompat.PRIORITY_LOW
        }
        
        return NotificationCompat.Builder(context, CHANNEL_ID_BREAK)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("Time for a Break")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "$message\n\nTaking regular breaks helps maintain focus and reduces stress."
            ))
            .setPriority(priority)
            .setAutoCancel(true)
            .setContentIntent(breakPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_view,
                "Take $durationMin min break",
                breakPendingIntent
            )
            .addAction(
                android.R.drawable.ic_menu_recent_history,
                "Snooze 15 min",
                snoozePendingIntent
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Dismiss",
                dismissPendingIntent
            )
            .build()
    }

    /**
     * Format pattern ID to human-readable name.
     */
    private fun formatPatternName(patternId: String): String {
        return when (patternId) {
            "box_breathing" -> "Box Breathing"
            "4_7_8" -> "4-7-8 Breathing"
            "resonance_breathing" -> "Resonance Breathing"
            "coherent_breathing" -> "Coherent Breathing"
            "bellows_breathing" -> "Bellows Breathing"
            else -> patternId.replace("_", " ").capitalize()
        }
    }

    private fun createPauseIntent(): PendingIntent {
        val intent = Intent(context, BreathControlReceiver::class.java).apply {
            action = "ACTION_PAUSE"
        }
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createStopIntent(): PendingIntent {
        val intent = Intent(context, BreathControlReceiver::class.java).apply {
            action = "ACTION_STOP"
        }
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

// ============================================================================
// Spotify Service Interface (Stub)
// ============================================================================

/**
 * SpotifyService: Interface to Spotify SDK.
 * Implementation should use Spotify Android SDK or Spotify Web API.
 */
interface SpotifyService {
    suspend fun connect(): Boolean
    fun isConnected(): Boolean
    suspend fun play(uri: String): Boolean
    suspend fun pause(): Boolean
    suspend fun setVolume(volume: Float): Boolean
}

/**
 * Example Spotify implementation using Spotify Android SDK.
 */
@Singleton
class SpotifyServiceImpl @Inject constructor(
    private val context: Context
) : SpotifyService {
    
    companion object {
        private const val TAG = "SpotifyServiceImpl"
        private const val CLIENT_ID = "YOUR_SPOTIFY_CLIENT_ID"
        private const val REDIRECT_URI = "zenb://callback"
    }
    
    // TODO: Initialize Spotify SDK
    // private val spotifyAppRemote: SpotifyAppRemote?
    
    override suspend fun connect(): Boolean {
        // TODO: Implement Spotify connection
        Log.w(TAG, "Spotify connection not yet implemented")
        return false
    }
    
    override fun isConnected(): Boolean {
        // TODO: Check Spotify connection status
        return false
    }
    
    override suspend fun play(uri: String): Boolean {
        // TODO: Play Spotify URI
        Log.w(TAG, "Spotify playback not yet implemented")
        return false
    }
    
    override suspend fun pause(): Boolean {
        // TODO: Pause Spotify playback
        return false
    }
    
    override suspend fun setVolume(volume: Float): Boolean {
        // TODO: Set Spotify volume
        return false
    }
}

// ============================================================================
// Broadcast Receivers for Notification Actions
// ============================================================================

class BreathControlReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "ACTION_PAUSE" -> {
                // TODO: Pause breath guidance
                Log.d("BreathControl", "Pause requested")
            }
            "ACTION_STOP" -> {
                // TODO: Stop breath guidance
                Log.d("BreathControl", "Stop requested")
                
                // Cancel notification
                val notificationManager = context.getSystemService(NotificationManager::class.java)
                notificationManager.cancel(1001)
            }
        }
    }
}

class DismissBreakReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("BreakSuggestion", "Break dismissed by user")
        
        // Cancel notification
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.cancel(1002)
        
        // TODO: Report dismissal to Rust for learning
    }
}

class SnoozeBreakReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("BreakSuggestion", "Break snoozed for 15 minutes")
        
        // Cancel current notification
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.cancel(1002)
        
        // TODO: Schedule reminder in 15 minutes
        // TODO: Report snooze to Rust for learning
    }
}

// ============================================================================
// Stub Activities (to be implemented)
// ============================================================================

/**
 * BreathGuideActivity: Full-screen breath guidance UI.
 * Should display animated breathing guide with audio/haptic feedback.
 */
class BreathGuideActivity : android.app.Activity() {
    // TODO: Implement breath guidance UI
}

/**
 * BreakActivity: Break timer and suggestions.
 * Should provide guided break activities (stretch, walk, meditate).
 */
class BreakActivity : android.app.Activity() {
    // TODO: Implement break activity UI
}
