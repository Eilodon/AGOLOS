# ActionDispatcher Implementation Guide

Complete guide for executing Rust ActionPolicy decisions on Android with safety mechanisms and feedback loops.

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                    Rust ZenB Core                                │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │  Active Inference Engine                                    │ │
│  │  - Observes multi-dimensional context                       │ │
│  │  - Updates belief state                                     │ │
│  │  - Selects ActionPolicy (minimize expected free energy)    │ │
│  └──────────────────────┬─────────────────────────────────────┘ │
└─────────────────────────┼───────────────────────────────────────┘
                          │ ActionPolicy JSON
                          ▼
┌─────────────────────────────────────────────────────────────────┐
│              FlowEngineIntegration (Kotlin)                      │
│  - Polls Rust for policy decisions                              │
│  - Forwards to ActionDispatcher                                 │
└──────────────────────┬──────────────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────────────┐
│              ActionDispatcher (Kotlin)                           │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │  Safety Mechanisms:                                         │ │
│  │  ✓ Debounce (5s window)                                    │ │
│  │  ✓ Permission checks                                       │ │
│  │  ✓ Timeout protection (10s)                                │ │
│  │  ✓ Graceful degradation                                    │ │
│  └────────────────────────────────────────────────────────────┘ │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │  Action Routing:                                            │ │
│  │  • NoAction → Passive observation                          │ │
│  │  • GuidanceBreath → MiniFlows breath UI                    │ │
│  │  • DigitalIntervention → System actions                    │ │
│  └────────────────────────────────────────────────────────────┘ │
└──────────────────────┬──────────────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────────────┐
│              Android System Services                             │
│  • NotificationManager (DND mode)                               │
│  • PackageManager (app launch)                                  │
│  • AudioManager (soundscape)                                    │
│  • MiniFlows (breath guidance, break suggestions)               │
└─────────────────────────────────────────────────────────────────┘
                       │
                       │ ActionResult
                       ▼
┌─────────────────────────────────────────────────────────────────┐
│              Feedback Loop to Rust                               │
│  - Reports success/failure                                       │
│  - Enables reinforcement learning                                │
│  - Improves policy selection over time                           │
└─────────────────────────────────────────────────────────────────┘
```

## Key Features

### 1. Safety Valve: Debouncing

**Problem**: Prevent rapid-fire execution of expensive actions (e.g., launching apps repeatedly).

**Solution**: Track last execution timestamp per action type.

```kotlin
private val actionTimestamps = ConcurrentHashMap<String, Long>()

private fun shouldDebounce(policy: ActionPolicyData): Boolean {
    val actionKey = policy.debounceKey()
    val lastExecution = actionTimestamps[actionKey] ?: return false
    val elapsed = System.currentTimeMillis() - lastExecution
    
    return elapsed < DEBOUNCE_WINDOW_MS // 5 seconds
}
```

**Debounce Keys**:
- `GuidanceBreath`: `"breath_{patternId}"` (per-pattern debouncing)
- `DigitalIntervention`: `"digital_{action}"` (per-action-type debouncing)
- `NoAction`: `"NoAction"` (no debouncing needed)

### 2. Graceful Degradation

**Problem**: Missing permissions should not crash the app.

**Solution**: Wrap all system calls in try-catch, check permissions first.

```kotlin
// Example: BlockNotifications
if (!notificationManager.isNotificationPolicyAccessGranted) {
    Log.w(TAG, "Missing NOTIFICATION_POLICY permission")
    
    // Prompt user instead of crashing
    promptForNotificationPolicyAccess()
    
    return ActionResult.PermissionDenied(
        actionId,
        "BlockNotifications",
        "NOTIFICATION_POLICY permission required"
    )
}
```

**Graceful Failure Modes**:
- `PermissionDenied`: Permission missing, user prompted
- `UnsupportedPlatform`: Android version too old
- `ExecutionFailed`: System call failed, logged but not fatal
- `Debounced`: Action skipped due to recent execution

### 3. Feedback Loop

**Problem**: Rust needs to know if actions succeeded to improve policy selection.

**Solution**: Report outcomes back to Rust core.

```kotlin
private fun reportOutcome(actionId: String, result: ActionResult) {
    dispatcherScope.launch(Dispatchers.IO) {
        val outcomeJson = json.encodeToString(
            ActionOutcome(
                actionId = actionId,
                success = result.isSuccess(),
                resultType = result.toResultType(),
                message = result.getMessage(),
                timestampUs = System.currentTimeMillis() * 1000L
            )
        )
        
        // Send to Rust for reinforcement learning
        // zenbCoreApi.reportActionOutcome(outcomeJson)
    }
}
```

**Outcome Types**:
- `Success`: Action executed successfully
- `Debounced`: Skipped due to rate limiting
- `PermissionDenied`: Missing required permission
- `ExecutionFailed`: System call failed
- `Timeout`: Action took too long (>10s)
- `ParseError`: Invalid JSON from Rust
- `UnexpectedError`: Uncaught exception

## Action Mapping

### 1. BlockNotifications

**Rust Policy**:
```json
{
  "type": "DigitalIntervention",
  "digitalIntervention": {
    "action": "BlockNotifications",
    "durationSec": 1800,
    "intensity": 0.8
  }
}
```

**Android Implementation**:
```kotlin
// Map intensity to DND filter level
val filter = when (params.intensity ?: 1.0f) {
    in 0.0f..0.3f -> INTERRUPTION_FILTER_PRIORITY  // Allow priority
    in 0.3f..0.7f -> INTERRUPTION_FILTER_ALARMS    // Allow alarms only
    else -> INTERRUPTION_FILTER_NONE               // Block all
}

notificationManager.setInterruptionFilter(filter)

// Auto-restore after duration
scheduleNotificationRestore(params.durationSec)
```

**Required Permission**: `NOTIFICATION_POLICY_ACCESS` (API 23+)

**Fallback**: Prompt user to grant permission via Settings.

### 2. PlaySoundscape

**Rust Policy**:
```json
{
  "type": "DigitalIntervention",
  "digitalIntervention": {
    "action": "PlaySoundscape",
    "intensity": 0.5
  }
}
```

**Android Implementation**:
```kotlin
// Try Spotify first (via MiniFlows)
val spotifySuccess = miniFlows.playSpotifyPlaylist(
    playlistUri = "spotify:playlist:37i9dQZF1DWZqd5JICZI0u",
    volume = params.intensity ?: 0.5f
)

// Fallback to local audio if Spotify unavailable
if (!spotifySuccess) {
    playLocalSoundscape(params.intensity ?: 0.5f)
}
```

**Integration Points**:
- Spotify SDK (via MiniFlows)
- MediaPlayer (local audio fallback)
- AudioManager (volume control)

### 3. LaunchApp

**Rust Policy**:
```json
{
  "type": "DigitalIntervention",
  "digitalIntervention": {
    "action": "LaunchApp",
    "targetApp": "com.calm.android"
  }
}
```

**Android Implementation**:
```kotlin
val launchIntent = packageManager.getLaunchIntentForPackage(targetPackage)

if (launchIntent != null) {
    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
    context.startActivity(launchIntent)
}
```

**Error Handling**:
- App not installed → `ExecutionFailed`
- Security exception → `PermissionDenied`

### 4. SuggestBreak

**Rust Policy**:
```json
{
  "type": "DigitalIntervention",
  "digitalIntervention": {
    "action": "SuggestBreak",
    "durationSec": 600,
    "intensity": 0.7
  }
}
```

**Android Implementation**:
```kotlin
miniFlows.showBreakSuggestion(
    durationSec = params.durationSec ?: 600,
    urgency = params.intensity ?: 0.5f,
    message = "ZenB suggests a ${durationSec / 60}-minute break"
)
```

**UI Options**:
- High-priority notification
- Full-screen overlay (high urgency)
- Gentle banner (low urgency)

### 5. GuidanceBreath

**Rust Policy**:
```json
{
  "type": "GuidanceBreath",
  "guidanceBreath": {
    "patternId": "resonance_breathing",
    "targetBpm": 6.0,
    "durationSec": 300,
    "targetHrv": 60.0
  }
}
```

**Android Implementation**:
```kotlin
miniFlows.startBreathGuidance(
    patternId = params.patternId,
    targetBpm = params.targetBpm,
    durationSec = params.durationSec
)
```

**Breath Patterns**:
- `box_breathing`: 4-4-4-4 (inhale-hold-exhale-hold)
- `4_7_8`: 4-7-8 (calming pattern)
- `resonance_breathing`: 6 BPM (HRV optimization)
- `coherent_breathing`: 5 BPM (coherence training)

## Setup Instructions

### 1. Add Dependencies

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
    implementation("com.google.dagger:hilt-android:2.48")
    kapt("com.google.dagger:hilt-compiler:2.48")
}
```

### 2. Declare Permissions

```xml
<!-- AndroidManifest.xml -->
<manifest>
    <!-- For BlockNotifications -->
    <uses-permission android:name="android.permission.ACCESS_NOTIFICATION_POLICY" />
    
    <!-- For LaunchApp -->
    <uses-permission android:name="android.permission.QUERY_ALL_PACKAGES"
        tools:ignore="QueryAllPackagesPermission" />
    
    <!-- For audio playback -->
    <uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
</manifest>
```

### 3. Initialize in Application Class

```kotlin
@HiltAndroidApp
class PandoraApplication : Application() {
    @Inject lateinit var flowEngineService: FlowEngineService
    
    override fun onCreate() {
        super.onCreate()
        
        // Start policy execution loop
        flowEngineService.start()
    }
}
```

### 4. Implement MiniFlows

```kotlin
@Singleton
class MiniFlowsImpl @Inject constructor(
    private val context: Context,
    private val spotifyService: SpotifyService?,
    private val breathGuideUI: BreathGuideUI
) : MiniFlows {
    
    override suspend fun startBreathGuidance(
        patternId: String,
        targetBpm: Float,
        durationSec: Int?
    ): Boolean {
        return breathGuideUI.start(patternId, targetBpm, durationSec)
    }
    
    override suspend fun playSpotifyPlaylist(
        playlistUri: String,
        volume: Float
    ): Boolean {
        return spotifyService?.play(playlistUri, volume) ?: false
    }
    
    override suspend fun showBreakSuggestion(
        durationSec: Int,
        urgency: Float,
        message: String
    ): Boolean {
        // Show notification or full-screen prompt
        return NotificationHelper.showBreakSuggestion(context, message, urgency)
    }
}
```

## Testing

### Unit Test: Debouncing

```kotlin
@Test
fun `test debounce prevents duplicate actions`() = runTest {
    val dispatcher = ActionDispatcher(context, miniFlows, zenbCoreApi, testScope)
    
    val policyJson = """
        {
            "type": "DigitalIntervention",
            "digitalIntervention": {
                "action": "LaunchApp",
                "targetApp": "com.example.app"
            }
        }
    """
    
    // First call should succeed
    val result1 = dispatcher.dispatch(policyJson)
    assertTrue(result1 is ActionResult.Success || result1 is ActionResult.ExecutionFailed)
    
    // Second call within 5s should be debounced
    val result2 = dispatcher.dispatch(policyJson)
    assertTrue(result2 is ActionResult.Debounced)
    
    // After 5s, should work again
    delay(5100)
    val result3 = dispatcher.dispatch(policyJson)
    assertFalse(result3 is ActionResult.Debounced)
}
```

### Integration Test: Permission Handling

```kotlin
@Test
fun `test graceful degradation on missing permission`() = runTest {
    // Ensure notification policy permission is NOT granted
    val notificationManager = context.getSystemService(NotificationManager::class.java)
    assertFalse(notificationManager.isNotificationPolicyAccessGranted)
    
    val dispatcher = ActionDispatcher(context, miniFlows, zenbCoreApi, testScope)
    
    val policyJson = """
        {
            "type": "DigitalIntervention",
            "digitalIntervention": {
                "action": "BlockNotifications",
                "durationSec": 1800
            }
        }
    """
    
    val result = dispatcher.dispatch(policyJson)
    
    // Should return PermissionDenied, not crash
    assertTrue(result is ActionResult.PermissionDenied)
    assertEquals("BlockNotifications", (result as ActionResult.PermissionDenied).actionType)
}
```

### Manual Test: End-to-End Flow

```kotlin
@Test
fun `test end to end policy execution`() = runTest {
    // 1. Start sensor fusion
    sensorFusionService.startCollection()
    
    // 2. Wait for observation to be ingested
    delay(1000)
    
    // 3. Tick Rust engine
    zenbCoreApi.tick(System.currentTimeMillis() * 1000L)
    
    // 4. Check for policy decision
    val dashboard = zenbCoreApi.getDashboard()
    // Parse dashboard and verify policy was selected
    
    // 5. Execute policy
    val result = actionDispatcher.dispatch(policyJson)
    assertTrue(result.isSuccess())
}
```

## Performance Considerations

### Memory

- **Debounce Cache**: Limited to action types (typically <20 entries)
- **Execution History**: Capped at 100 records, auto-pruned
- **Coroutine Scopes**: Properly cancelled to prevent leaks

### Battery

- **Policy Poll Rate**: 1 Hz (1000ms interval) balances responsiveness and battery
- **Action Timeout**: 10s prevents hanging operations
- **Background Dispatchers**: All FFI calls on `Dispatchers.IO`

### Network

- **Spotify Integration**: Requires network, gracefully degrades to local audio
- **No Blocking Calls**: All network operations are async

## Troubleshooting

### Issue: Actions not executing

**Check**:
1. Is `FlowEngineService` started?
2. Is policy loop running? Check logs for "Policy loop started"
3. Is Rust core producing policies? Check dashboard JSON
4. Are permissions granted? Check logcat for "Permission denied"

### Issue: Debouncing too aggressive

**Solution**: Adjust `DEBOUNCE_WINDOW_MS` or implement adaptive debouncing:

```kotlin
private fun getDebounceWindow(actionType: String): Long {
    return when (actionType) {
        "LaunchApp" -> 5000L      // 5s for app launches
        "PlaySoundscape" -> 30000L // 30s for audio
        "SuggestBreak" -> 60000L   // 1min for break suggestions
        else -> 5000L
    }
}
```

### Issue: Notifications not blocking

**Solution**: Ensure permission is granted and API level is correct:

```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
    if (!notificationManager.isNotificationPolicyAccessGranted) {
        // Prompt user to grant permission
        val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        startActivity(intent)
    }
}
```

## Future Enhancements

1. **Adaptive Debouncing**: Learn optimal debounce windows per user
2. **Context-Aware Execution**: Skip actions based on user context (driving, in meeting)
3. **Action Queuing**: Queue actions when device is locked, execute on unlock
4. **Reinforcement Learning**: Use outcome feedback to improve policy selection
5. **Multi-Modal Actions**: Combine multiple interventions (breath + soundscape)

## References

- [Android NotificationManager](https://developer.android.com/reference/android/app/NotificationManager)
- [Android PackageManager](https://developer.android.com/reference/android/content/pm/PackageManager)
- [Kotlin Coroutines](https://kotlinlang.org/docs/coroutines-guide.html)
- [Active Inference Framework](../docs/BELIEF_ENGINE.md)
