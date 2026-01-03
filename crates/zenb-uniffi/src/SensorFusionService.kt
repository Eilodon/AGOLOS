package com.pandora.app.service

import android.content.Context
import android.os.BatteryManager
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SensorFusionService: Aggregates multi-dimensional context from Android sensors
 * and forwards observations to the Rust ZenB core via UniFFI.
 *
 * Architecture:
 * - Combines data from LocationAwareness, UserActivityAnalyzer, and AppUsageIntelligence
 * - Normalizes raw Android values to [0, 1] range for ML compatibility
 * - Serializes to JSON matching Rust Observation struct schema
 * - Calls FFI on background dispatcher to avoid blocking main thread
 *
 * Thread Safety:
 * - All FFI calls are dispatched to Dispatchers.IO
 * - Flow collection happens on background coroutine scope
 * - Error handling prevents crashes from FFI panics
 */
@Singleton
class SensorFusionService @Inject constructor(
    private val context: Context,
    private val locationAwareness: LocationAwareness,
    private val userActivityAnalyzer: UserActivityAnalyzer,
    private val appUsageIntelligence: AppUsageIntelligence,
    private val zenbCoreApi: ZenbCoreApi,
    private val serviceScope: CoroutineScope
) {
    companion object {
        private const val TAG = "SensorFusionService"
        private const val COLLECTION_INTERVAL_MS = 500L // 2 Hz sampling rate
        
        // Normalization constants
        private const val MAX_SCREEN_BRIGHTNESS = 255f
        private const val MAX_NOISE_DB = 100f
        private const val MAX_NOTIFICATION_RATE = 60f // notifications per hour
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private var collectionJob: Job? = null
    private val _observationFlow = MutableStateFlow<ObservationData?>(null)
    val observationFlow: StateFlow<ObservationData?> = _observationFlow.asStateFlow()

    /**
     * Start continuous sensor fusion and observation ingestion.
     * Combines data from all sources and sends to Rust core at regular intervals.
     */
    fun startCollection() {
        if (collectionJob?.isActive == true) {
            Log.w(TAG, "Collection already active")
            return
        }

        collectionJob = serviceScope.launch {
            try {
                collectAndSend()
            } catch (e: Exception) {
                Log.e(TAG, "Fatal error in sensor fusion", e)
            }
        }
        
        Log.i(TAG, "Sensor fusion started")
    }

    /**
     * Stop sensor fusion collection.
     */
    fun stopCollection() {
        collectionJob?.cancel()
        collectionJob = null
        Log.i(TAG, "Sensor fusion stopped")
    }

    /**
     * Core fusion logic: Combines flows from all sensor sources.
     * Uses Kotlin Flow.combine to synchronize multi-source data.
     */
    private suspend fun collectAndSend() {
        combine(
            locationAwareness.locationFlow,
            userActivityAnalyzer.activityFlow,
            appUsageIntelligence.usageFlow,
            getBatteryFlow()
        ) { location, activity, appUsage, battery ->
            // Aggregate into unified observation
            createObservation(location, activity, appUsage, battery)
        }
            .distinctUntilChanged() // Avoid redundant updates
            .sample(COLLECTION_INTERVAL_MS) // Rate limiting
            .flowOn(Dispatchers.Default) // Data processing on background thread
            .collect { observation ->
                _observationFlow.value = observation
                sendToRustCore(observation)
            }
    }

    /**
     * Create an Observation from aggregated sensor data.
     * Handles data normalization and null safety.
     */
    private fun createObservation(
        location: LocationData?,
        activity: ActivityData?,
        appUsage: AppUsageData?,
        battery: BatteryData
    ): ObservationData {
        val timestampUs = System.currentTimeMillis() * 1000L

        // BioMetrics: Extract from wearable or activity data
        val bioMetrics = activity?.let { act ->
            BioMetricsData(
                hrBpm = act.heartRate?.toFloat(),
                hrvRmssd = act.hrv?.toFloat(),
                respiratoryRate = act.respiratoryRate?.toFloat()
            )
        }

        // EnvironmentalContext: Location and device state
        val environmentalContext = EnvironmentalContextData(
            locationType = location?.type?.toLocationType(),
            noiseLevel = normalizeNoiseLevel(location?.ambientNoiseDb),
            isCharging = battery.isCharging
        )

        // DigitalContext: App usage and notification patterns
        val digitalContext = appUsage?.let { usage ->
            DigitalContextData(
                activeAppCategory = usage.currentAppCategory?.toAppCategory(),
                interactionIntensity = normalizeInteractionIntensity(usage.touchEventsPerMinute),
                notificationPressure = normalizeNotificationPressure(usage.notificationsPerHour)
            )
        }

        return ObservationData(
            timestampUs = timestampUs,
            bioMetrics = bioMetrics,
            environmentalContext = environmentalContext,
            digitalContext = digitalContext
        )
    }

    /**
     * Send observation to Rust core via FFI.
     * Runs on IO dispatcher to avoid blocking.
     * Handles JSON serialization and FFI errors gracefully.
     */
    private suspend fun sendToRustCore(observation: ObservationData) {
        withContext(Dispatchers.IO) {
            try {
                // Serialize to JSON
                val jsonPayload = json.encodeToString(observation)
                
                Log.d(TAG, "Sending observation: ${jsonPayload.take(200)}...")
                
                // Call FFI (thread-safe, non-blocking from Rust perspective)
                zenbCoreApi.ingestObservation(jsonPayload)
                
                Log.v(TAG, "Observation ingested successfully")
                
            } catch (e: kotlinx.serialization.SerializationException) {
                Log.e(TAG, "JSON serialization failed", e)
                // Don't crash - skip this observation
            } catch (e: Exception) {
                // Catch FFI panics or Rust errors
                Log.e(TAG, "FFI call failed", e)
                // Consider exponential backoff or circuit breaker here
            }
        }
    }

    /**
     * Get battery state as a Flow.
     * Monitors charging state for context awareness.
     */
    private fun getBatteryFlow(): Flow<BatteryData> = flow {
        while (currentCoroutineContext().isActive) {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val isCharging = batteryManager.isCharging
            val batteryPct = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            
            emit(BatteryData(isCharging, batteryPct))
            delay(COLLECTION_INTERVAL_MS)
        }
    }

    // ============================================================================
    // Data Normalization Functions
    // ============================================================================

    /**
     * Normalize screen brightness from [0, 255] to [0.0, 1.0]
     */
    private fun normalizeBrightness(brightness: Int?): Float? {
        return brightness?.let { (it / MAX_SCREEN_BRIGHTNESS).coerceIn(0f, 1f) }
    }

    /**
     * Normalize ambient noise level from dB to [0.0, 1.0]
     * Assumes 0-100 dB range (0 = silent, 100 = very loud)
     */
    private fun normalizeNoiseLevel(noiseDb: Float?): Float? {
        return noiseDb?.let { (it / MAX_NOISE_DB).coerceIn(0f, 1f) }
    }

    /**
     * Normalize interaction intensity from touch events per minute.
     * 0 events = 0.0 (passive), 60+ events = 1.0 (high interaction)
     */
    private fun normalizeInteractionIntensity(touchEventsPerMin: Int?): Float? {
        return touchEventsPerMin?.let { 
            (it / 60f).coerceIn(0f, 1f) 
        }
    }

    /**
     * Normalize notification pressure from notifications per hour.
     * 0 = 0.0 (no pressure), 60+ = 1.0 (high pressure)
     */
    private fun normalizeNotificationPressure(notificationsPerHour: Float?): Float? {
        return notificationsPerHour?.let {
            (it / MAX_NOTIFICATION_RATE).coerceIn(0f, 1f)
        }
    }

    // ============================================================================
    // Type Mapping Extensions
    // ============================================================================

    private fun String.toLocationType(): String {
        return when (this.lowercase()) {
            "home" -> "Home"
            "work", "office" -> "Work"
            "transit", "moving", "vehicle" -> "Transit"
            else -> "Home" // Default
        }
    }

    private fun String.toAppCategory(): String {
        return when (this.lowercase()) {
            "social", "communication" -> "Social"
            "productivity", "work", "business" -> "Productivity"
            "entertainment", "games", "video" -> "Entertainment"
            "health", "fitness", "wellness" -> "Wellness"
            "browser", "web" -> "Browser"
            else -> "Other"
        }
    }
}

// ============================================================================
// Data Classes: Match Rust Observation Schema
// ============================================================================

@Serializable
data class ObservationData(
    val timestampUs: Long,
    val bioMetrics: BioMetricsData? = null,
    val environmentalContext: EnvironmentalContextData? = null,
    val digitalContext: DigitalContextData? = null
)

@Serializable
data class BioMetricsData(
    val hrBpm: Float? = null,
    val hrvRmssd: Float? = null,
    val respiratoryRate: Float? = null
)

@Serializable
data class EnvironmentalContextData(
    val locationType: String? = null,
    val noiseLevel: Float? = null,
    val isCharging: Boolean
)

@Serializable
data class DigitalContextData(
    val activeAppCategory: String? = null,
    val interactionIntensity: Float? = null,
    val notificationPressure: Float? = null
)

// ============================================================================
// Input Data Classes: From Android Analyzers
// ============================================================================

/**
 * Location data from LocationAwareness service
 */
data class LocationData(
    val type: String, // "home", "work", "transit"
    val latitude: Double?,
    val longitude: Double?,
    val ambientNoiseDb: Float?
)

/**
 * Activity data from UserActivityAnalyzer (wearable integration)
 */
data class ActivityData(
    val heartRate: Int?,
    val hrv: Int?,
    val respiratoryRate: Int?,
    val stepCount: Int?,
    val activityType: String? // "still", "walking", "running"
)

/**
 * App usage data from AppUsageIntelligence
 */
data class AppUsageData(
    val currentAppCategory: String?,
    val currentAppPackage: String?,
    val touchEventsPerMinute: Int?,
    val notificationsPerHour: Float?,
    val screenTimeMinutes: Int?
)

/**
 * Battery state data
 */
data class BatteryData(
    val isCharging: Boolean,
    val batteryPercent: Int
)

// ============================================================================
// Stub Interfaces: To be implemented by Android modules
// ============================================================================

/**
 * LocationAwareness: Provides location context
 * Implementation should use FusedLocationProviderClient and geofencing
 */
interface LocationAwareness {
    val locationFlow: Flow<LocationData?>
}

/**
 * UserActivityAnalyzer: Provides biometric and activity data
 * Implementation should integrate with Google Fit, Health Connect, or wearable APIs
 */
interface UserActivityAnalyzer {
    val activityFlow: Flow<ActivityData?>
}

/**
 * AppUsageIntelligence: Provides digital context
 * Implementation should use UsageStatsManager and NotificationListenerService
 */
interface AppUsageIntelligence {
    val usageFlow: Flow<AppUsageData?>
}
