package com.pandora.app.logic

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ActionDispatcher: Executes ActionPolicy decisions from Rust ZenB core.
 *
 * Responsibilities:
 * 1. Parse ActionPolicy JSON from Rust
 * 2. Map policies to Android system actions
 * 3. Execute actions with safety mechanisms (debouncing, permission checks)
 * 4. Report execution outcomes back to Rust for reinforcement learning
 *
 * Safety Features:
 * - Debounce: Prevents duplicate heavy actions within 5 seconds
 * - Graceful degradation: Missing permissions don't crash the app
 * - Error handling: All system calls wrapped in try-catch
 * - Feedback loop: Reports success/failure to Rust core
 */
@Singleton
class ActionDispatcher @Inject constructor(
    private val context: Context,
    private val miniFlows: MiniFlows,
    private val zenbCoreApi: ZenbCoreApi,
    private val dispatcherScope: CoroutineScope
) {
    companion object {
        private const val TAG = "ActionDispatcher"
        private const val DEBOUNCE_WINDOW_MS = 5000L // 5 seconds
        private const val ACTION_TIMEOUT_MS = 10000L // 10 seconds max execution time
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // Debounce tracking: actionType -> last execution timestamp
    private val actionTimestamps = ConcurrentHashMap<String, Long>()
    
    // Action execution history for analytics
    private val executionHistory = mutableListOf<ActionExecutionRecord>()

    // System services (lazy initialization)
    private val notificationManager: NotificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }
    
    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    
    private val packageManager: PackageManager by lazy {
        context.packageManager
    }

    /**
     * Main dispatch entry point: Parse ActionPolicy JSON and execute.
     *
     * @param actionJson JSON string from Rust containing ActionPolicy
     * @return ActionResult indicating success/failure
     */
    suspend fun dispatch(actionJson: String): ActionResult = withContext(Dispatchers.Default) {
        val actionId = generateActionId()
        
        try {
            Log.d(TAG, "Dispatching action: ${actionJson.take(200)}")
            
            // Parse ActionPolicy from JSON
            val policy = parseActionPolicy(actionJson)
            
            // Check debounce
            if (shouldDebounce(policy)) {
                Log.w(TAG, "Action debounced: ${policy.type}")
                return@withContext ActionResult.Debounced(actionId, policy.type)
            }
            
            // Execute with timeout
            val result = withTimeoutOrNull(ACTION_TIMEOUT_MS) {
                executePolicy(policy, actionId)
            } ?: ActionResult.Timeout(actionId, policy.type)
            
            // Record execution
            recordExecution(actionId, policy, result)
            
            // Report back to Rust (async, non-blocking)
            reportOutcome(actionId, result)
            
            result
            
        } catch (e: SerializationException) {
            Log.e(TAG, "Failed to parse action JSON", e)
            val result = ActionResult.ParseError(actionId, e.message ?: "Unknown parse error")
            reportOutcome(actionId, result)
            result
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during dispatch", e)
            val result = ActionResult.UnexpectedError(actionId, e.message ?: "Unknown error")
            reportOutcome(actionId, result)
            result
        }
    }

    /**
     * Parse ActionPolicy from JSON string.
     * Handles different policy variants (NoAction, GuidanceBreath, DigitalIntervention).
     */
    private fun parseActionPolicy(jsonString: String): ActionPolicyData {
        return json.decodeFromString<ActionPolicyData>(jsonString)
    }

    /**
     * Check if action should be debounced based on recent execution history.
     */
    private fun shouldDebounce(policy: ActionPolicyData): Boolean {
        val actionKey = policy.debounceKey()
        val lastExecution = actionTimestamps[actionKey] ?: return false
        val elapsed = System.currentTimeMillis() - lastExecution
        
        return elapsed < DEBOUNCE_WINDOW_MS
    }

    /**
     * Execute the parsed ActionPolicy.
     * Routes to specific handlers based on policy type.
     */
    private suspend fun executePolicy(policy: ActionPolicyData, actionId: String): ActionResult {
        // Update debounce timestamp
        actionTimestamps[policy.debounceKey()] = System.currentTimeMillis()
        
        return when (policy.type) {
            "NoAction" -> {
                Log.d(TAG, "NoAction policy - passive observation")
                ActionResult.Success(actionId, "NoAction", "Passive observation mode")
            }
            
            "GuidanceBreath" -> {
                executeGuidanceBreath(actionId, policy.guidanceBreath)
            }
            
            "DigitalIntervention" -> {
                executeDigitalIntervention(actionId, policy.digitalIntervention)
            }
            
            else -> {
                Log.w(TAG, "Unknown policy type: ${policy.type}")
                ActionResult.UnsupportedAction(actionId, policy.type)
            }
        }
    }

    /**
     * Execute breath guidance intervention.
     * Triggers MiniFlows breath guidance UI.
     */
    private suspend fun executeGuidanceBreath(
        actionId: String,
        params: GuidanceBreathParams?
    ): ActionResult {
        if (params == null) {
            return ActionResult.InvalidParams(actionId, "GuidanceBreath", "Missing parameters")
        }
        
        return try {
            Log.i(TAG, "Executing GuidanceBreath: pattern=${params.patternId}, bpm=${params.targetBpm}")
            
            // Trigger MiniFlows breath guidance
            val success = miniFlows.startBreathGuidance(
                patternId = params.patternId,
                targetBpm = params.targetBpm,
                durationSec = params.durationSec
            )
            
            if (success) {
                ActionResult.Success(
                    actionId,
                    "GuidanceBreath",
                    "Started breath guidance: ${params.patternId} at ${params.targetBpm} BPM"
                )
            } else {
                ActionResult.ExecutionFailed(
                    actionId,
                    "GuidanceBreath",
                    "MiniFlows failed to start breath guidance"
                )
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "GuidanceBreath execution failed", e)
            ActionResult.ExecutionFailed(actionId, "GuidanceBreath", e.message ?: "Unknown error")
        }
    }

    /**
     * Execute digital intervention (notifications, soundscape, app launch, break suggestion).
     * Routes to specific handlers based on intervention type.
     */
    private suspend fun executeDigitalIntervention(
        actionId: String,
        params: DigitalInterventionParams?
    ): ActionResult {
        if (params == null) {
            return ActionResult.InvalidParams(actionId, "DigitalIntervention", "Missing parameters")
        }
        
        return when (params.action) {
            "BlockNotifications" -> executeBlockNotifications(actionId, params)
            "PlaySoundscape" -> executePlaySoundscape(actionId, params)
            "LaunchApp" -> executeLaunchApp(actionId, params)
            "SuggestBreak" -> executeSuggestBreak(actionId, params)
            else -> ActionResult.UnsupportedAction(actionId, params.action)
        }
    }

    /**
     * Block notifications using Do Not Disturb mode.
     * Requires NOTIFICATION_POLICY permission (API 23+).
     */
    private suspend fun executeBlockNotifications(
        actionId: String,
        params: DigitalInterventionParams
    ): ActionResult = withContext(Dispatchers.Main) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                return@withContext ActionResult.UnsupportedPlatform(
                    actionId,
                    "BlockNotifications",
                    "Requires Android M+"
                )
            }
            
            // Check if we have permission to modify Do Not Disturb
            if (!notificationManager.isNotificationPolicyAccessGranted) {
                Log.w(TAG, "Missing NOTIFICATION_POLICY permission")
                
                // Graceful degradation: prompt user instead of crashing
                promptForNotificationPolicyAccess()
                
                return@withContext ActionResult.PermissionDenied(
                    actionId,
                    "BlockNotifications",
                    "NOTIFICATION_POLICY permission required"
                )
            }
            
            // Set Do Not Disturb mode
            val filter = when (params.intensity ?: 1.0f) {
                in 0.0f..0.3f -> NotificationManager.INTERRUPTION_FILTER_PRIORITY // Allow priority
                in 0.3f..0.7f -> NotificationManager.INTERRUPTION_FILTER_ALARMS // Allow alarms only
                else -> NotificationManager.INTERRUPTION_FILTER_NONE // Block all
            }
            
            notificationManager.setInterruptionFilter(filter)
            
            Log.i(TAG, "Notifications blocked: filter=$filter, duration=${params.durationSec}s")
            
            // Schedule auto-restore if duration specified
            params.durationSec?.let { duration ->
                scheduleNotificationRestore(duration)
            }
            
            ActionResult.Success(
                actionId,
                "BlockNotifications",
                "DND enabled for ${params.durationSec ?: "indefinite"} seconds"
            )
            
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception blocking notifications", e)
            ActionResult.PermissionDenied(actionId, "BlockNotifications", e.message ?: "Security error")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to block notifications", e)
            ActionResult.ExecutionFailed(actionId, "BlockNotifications", e.message ?: "Unknown error")
        }
    }

    /**
     * Play calming soundscape via Spotify or local audio.
     */
    private suspend fun executePlaySoundscape(
        actionId: String,
        params: DigitalInterventionParams
    ): ActionResult {
        try {
            Log.i(TAG, "Playing soundscape: intensity=${params.intensity}")
            
            // Try Spotify first (via MiniFlows)
            val spotifySuccess = miniFlows.playSpotifyPlaylist(
                playlistUri = "spotify:playlist:37i9dQZF1DWZqd5JICZI0u", // Peaceful Piano
                volume = params.intensity ?: 0.5f
            )
            
            if (spotifySuccess) {
                return ActionResult.Success(
                    actionId,
                    "PlaySoundscape",
                    "Spotify soundscape started"
                )
            }
            
            // Fallback: Local audio playback
            val localSuccess = playLocalSoundscape(params.intensity ?: 0.5f)
            
            if (localSuccess) {
                ActionResult.Success(actionId, "PlaySoundscape", "Local soundscape started")
            } else {
                ActionResult.ExecutionFailed(
                    actionId,
                    "PlaySoundscape",
                    "No audio playback method available"
                )
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play soundscape", e)
            ActionResult.ExecutionFailed(actionId, "PlaySoundscape", e.message ?: "Unknown error")
        }
    }

    /**
     * Launch a specific app by package name or target app identifier.
     */
    private suspend fun executeLaunchApp(
        actionId: String,
        params: DigitalInterventionParams
    ): ActionResult = withContext(Dispatchers.Main) {
        try {
            val targetPackage = params.targetApp ?: return@withContext ActionResult.InvalidParams(
                actionId,
                "LaunchApp",
                "Missing target_app parameter"
            )
            
            Log.i(TAG, "Launching app: $targetPackage")
            
            // Get launch intent for package
            val launchIntent = packageManager.getLaunchIntentForPackage(targetPackage)
            
            if (launchIntent == null) {
                Log.w(TAG, "App not found: $targetPackage")
                return@withContext ActionResult.ExecutionFailed(
                    actionId,
                    "LaunchApp",
                    "App not installed: $targetPackage"
                )
            }
            
            // Add flags to start in new task
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            
            // Launch the app
            context.startActivity(launchIntent)
            
            ActionResult.Success(
                actionId,
                "LaunchApp",
                "Launched app: $targetPackage"
            )
            
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception launching app", e)
            ActionResult.PermissionDenied(actionId, "LaunchApp", e.message ?: "Security error")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch app", e)
            ActionResult.ExecutionFailed(actionId, "LaunchApp", e.message ?: "Unknown error")
        }
    }

    /**
     * Suggest a break to the user via notification or UI prompt.
     */
    private suspend fun executeSuggestBreak(
        actionId: String,
        params: DigitalInterventionParams
    ): ActionResult {
        try {
            val durationSec = params.durationSec ?: 600 // Default 10 minutes
            val urgency = params.intensity ?: 0.5f
            
            Log.i(TAG, "Suggesting break: duration=${durationSec}s, urgency=$urgency")
            
            // Show break suggestion via MiniFlows notification
            val success = miniFlows.showBreakSuggestion(
                durationSec = durationSec,
                urgency = urgency,
                message = "ZenB suggests a ${durationSec / 60}-minute break to restore balance"
            )
            
            if (success) {
                ActionResult.Success(
                    actionId,
                    "SuggestBreak",
                    "Break suggestion displayed"
                )
            } else {
                ActionResult.ExecutionFailed(
                    actionId,
                    "SuggestBreak",
                    "Failed to show break suggestion"
                )
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to suggest break", e)
            ActionResult.ExecutionFailed(actionId, "SuggestBreak", e.message ?: "Unknown error")
        }
    }

    // ============================================================================
    // Helper Functions
    // ============================================================================

    /**
     * Prompt user to grant notification policy access.
     */
    @RequiresApi(Build.VERSION_CODES.M)
    private fun promptForNotificationPolicyAccess() {
        try {
            val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open notification policy settings", e)
        }
    }

    /**
     * Schedule automatic restoration of notification settings.
     */
    private fun scheduleNotificationRestore(durationSec: Int) {
        dispatcherScope.launch {
            delay(durationSec * 1000L)
            
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    notificationManager.setInterruptionFilter(
                        NotificationManager.INTERRUPTION_FILTER_ALL
                    )
                    Log.i(TAG, "Notifications restored after ${durationSec}s")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore notifications", e)
            }
        }
    }

    /**
     * Play local soundscape audio file.
     */
    private fun playLocalSoundscape(volume: Float): Boolean {
        // TODO: Implement local audio playback
        // This would use MediaPlayer or ExoPlayer to play local audio files
        Log.w(TAG, "Local soundscape playback not yet implemented")
        return false
    }

    /**
     * Generate unique action ID for tracking.
     */
    private fun generateActionId(): String {
        return "action_${System.currentTimeMillis()}_${(0..9999).random()}"
    }

    /**
     * Record action execution for analytics and debugging.
     */
    private fun recordExecution(actionId: String, policy: ActionPolicyData, result: ActionResult) {
        val record = ActionExecutionRecord(
            actionId = actionId,
            policyType = policy.type,
            timestamp = System.currentTimeMillis(),
            result = result.toResultType(),
            details = result.getMessage()
        )
        
        synchronized(executionHistory) {
            executionHistory.add(record)
            
            // Keep only last 100 records
            if (executionHistory.size > 100) {
                executionHistory.removeAt(0)
            }
        }
    }

    /**
     * Report execution outcome back to Rust core for reinforcement learning.
     * This enables the AI to learn which actions are effective.
     */
    private fun reportOutcome(actionId: String, result: ActionResult) {
        dispatcherScope.launch(Dispatchers.IO) {
            try {
                val outcomeJson = json.encodeToString(
                    ActionOutcome.serializer(),
                    ActionOutcome(
                        actionId = actionId,
                        success = result.isSuccess(),
                        resultType = result.toResultType(),
                        message = result.getMessage(),
                        timestampUs = System.currentTimeMillis() * 1000L
                    )
                )
                
                Log.d(TAG, "Reporting action outcome to Rust: $outcomeJson")
                
                // Send to Rust core for reinforcement learning
                zenbCoreApi.reportActionOutcome(outcomeJson)
                
                Log.v(TAG, "Action outcome reported successfully")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to report outcome to Rust", e)
                // Non-fatal: outcome reporting failure shouldn't break action execution
            }
        }
    }

    /**
     * Get execution history for debugging/analytics.
     */
    fun getExecutionHistory(): List<ActionExecutionRecord> {
        return synchronized(executionHistory) {
            executionHistory.toList()
        }
    }

    /**
     * Clear debounce cache (useful for testing or manual override).
     */
    fun clearDebounceCache() {
        actionTimestamps.clear()
        Log.d(TAG, "Debounce cache cleared")
    }
}

// ============================================================================
// Data Classes: ActionPolicy Schema (matches Rust)
// ============================================================================

@Serializable
data class ActionPolicyData(
    val type: String, // "NoAction", "GuidanceBreath", "DigitalIntervention"
    val guidanceBreath: GuidanceBreathParams? = null,
    val digitalIntervention: DigitalInterventionParams? = null
) {
    fun debounceKey(): String {
        return when (type) {
            "GuidanceBreath" -> "breath_${guidanceBreath?.patternId}"
            "DigitalIntervention" -> "digital_${digitalIntervention?.action}"
            else -> type
        }
    }
}

@Serializable
data class GuidanceBreathParams(
    val patternId: String,
    val targetBpm: Float,
    val durationSec: Int? = null,
    val targetHrv: Float? = null
)

@Serializable
data class DigitalInterventionParams(
    val action: String, // "BlockNotifications", "PlaySoundscape", "LaunchApp", "SuggestBreak"
    val durationSec: Int? = null,
    val targetApp: String? = null,
    val intensity: Float? = null
)

// ============================================================================
// Result Types
// ============================================================================

sealed class ActionResult {
    abstract fun isSuccess(): Boolean
    abstract fun toResultType(): String
    abstract fun getMessage(): String
    
    data class Success(
        val actionId: String,
        val actionType: String,
        val message: String
    ) : ActionResult() {
        override fun isSuccess() = true
        override fun toResultType() = "Success"
        override fun getMessage() = message
    }
    
    data class Debounced(
        val actionId: String,
        val actionType: String
    ) : ActionResult() {
        override fun isSuccess() = false
        override fun toResultType() = "Debounced"
        override fun getMessage() = "Action debounced: $actionType"
    }
    
    data class PermissionDenied(
        val actionId: String,
        val actionType: String,
        val reason: String
    ) : ActionResult() {
        override fun isSuccess() = false
        override fun toResultType() = "PermissionDenied"
        override fun getMessage() = "Permission denied: $reason"
    }
    
    data class ExecutionFailed(
        val actionId: String,
        val actionType: String,
        val reason: String
    ) : ActionResult() {
        override fun isSuccess() = false
        override fun toResultType() = "ExecutionFailed"
        override fun getMessage() = "Execution failed: $reason"
    }
    
    data class InvalidParams(
        val actionId: String,
        val actionType: String,
        val reason: String
    ) : ActionResult() {
        override fun isSuccess() = false
        override fun toResultType() = "InvalidParams"
        override fun getMessage() = "Invalid parameters: $reason"
    }
    
    data class UnsupportedAction(
        val actionId: String,
        val actionType: String
    ) : ActionResult() {
        override fun isSuccess() = false
        override fun toResultType() = "UnsupportedAction"
        override fun getMessage() = "Unsupported action: $actionType"
    }
    
    data class UnsupportedPlatform(
        val actionId: String,
        val actionType: String,
        val reason: String
    ) : ActionResult() {
        override fun isSuccess() = false
        override fun toResultType() = "UnsupportedPlatform"
        override fun getMessage() = "Platform unsupported: $reason"
    }
    
    data class Timeout(
        val actionId: String,
        val actionType: String
    ) : ActionResult() {
        override fun isSuccess() = false
        override fun toResultType() = "Timeout"
        override fun getMessage() = "Action timed out: $actionType"
    }
    
    data class ParseError(
        val actionId: String,
        val reason: String
    ) : ActionResult() {
        override fun isSuccess() = false
        override fun toResultType() = "ParseError"
        override fun getMessage() = "Parse error: $reason"
    }
    
    data class UnexpectedError(
        val actionId: String,
        val reason: String
    ) : ActionResult() {
        override fun isSuccess() = false
        override fun toResultType() = "UnexpectedError"
        override fun getMessage() = "Unexpected error: $reason"
    }
}

@Serializable
data class ActionOutcome(
    val actionId: String,
    val success: Boolean,
    val resultType: String,
    val message: String,
    val timestampUs: Long
)

data class ActionExecutionRecord(
    val actionId: String,
    val policyType: String,
    val timestamp: Long,
    val result: String,
    val details: String
)

// ============================================================================
// MiniFlows Interface (Stub - to be implemented)
// ============================================================================

/**
 * MiniFlows: Interface to Pandora's existing flow capabilities.
 * Implementations should integrate with FlowEngineService.
 */
interface MiniFlows {
    suspend fun startBreathGuidance(patternId: String, targetBpm: Float, durationSec: Int?): Boolean
    suspend fun playSpotifyPlaylist(playlistUri: String, volume: Float): Boolean
    suspend fun showBreakSuggestion(durationSec: Int, urgency: Float, message: String): Boolean
}
