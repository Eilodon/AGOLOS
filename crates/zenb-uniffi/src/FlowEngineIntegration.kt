package com.pandora.app.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton
import com.pandora.app.logic.ActionDispatcher
import com.pandora.app.logic.ActionResult

/**
 * FlowEngineIntegration: Connects Rust ZenB core with Android ActionDispatcher.
 *
 * This service acts as the bridge between:
 * 1. Rust Active Inference engine (produces ActionPolicy decisions)
 * 2. Android ActionDispatcher (executes policies on device)
 *
 * Architecture:
 * - Polls Rust core for policy decisions at regular intervals
 * - Dispatches actions via ActionDispatcher
 * - Reports execution outcomes back to Rust for learning
 *
 * Integration with existing FlowEngineService:
 * - Can be injected into FlowEngineService
 * - Runs alongside existing BLE/NFC flows
 * - Shares MiniFlows infrastructure
 */
@Singleton
class FlowEngineIntegration @Inject constructor(
    private val context: Context,
    private val zenbCoreApi: ZenbCoreApi,
    private val actionDispatcher: ActionDispatcher,
    private val integrationScope: CoroutineScope
) {
    companion object {
        private const val TAG = "FlowEngineIntegration"
        private const val POLICY_POLL_INTERVAL_MS = 1000L // Check for new policies every second
    }

    private var policyLoopJob: Job? = null
    private val _policyFlow = MutableSharedFlow<String>(replay = 1)
    val policyFlow: SharedFlow<String> = _policyFlow.asSharedFlow()

    /**
     * Start the policy execution loop.
     * This should be called when the app enters foreground or a session starts.
     */
    fun startPolicyLoop() {
        if (policyLoopJob?.isActive == true) {
            Log.w(TAG, "Policy loop already running")
            return
        }

        policyLoopJob = integrationScope.launch {
            try {
                runPolicyLoop()
            } catch (e: CancellationException) {
                Log.d(TAG, "Policy loop cancelled")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Fatal error in policy loop", e)
            }
        }

        Log.i(TAG, "Policy loop started")
    }

    /**
     * Stop the policy execution loop.
     */
    fun stopPolicyLoop() {
        policyLoopJob?.cancel()
        policyLoopJob = null
        Log.i(TAG, "Policy loop stopped")
    }

    /**
     * Main policy execution loop.
     * Polls Rust for decisions and dispatches them.
     */
    private suspend fun runPolicyLoop() {
        while (currentCoroutineContext().isActive) {
            try {
                // Get current dashboard state from Rust
                val dashboardJson = zenbCoreApi.getDashboard()
                
                // Check if there's a new policy decision
                val policyJson = extractPolicyFromDashboard(dashboardJson)
                
                if (policyJson != null) {
                    Log.d(TAG, "New policy received: ${policyJson.take(100)}")
                    
                    // Emit to flow for observers
                    _policyFlow.emit(policyJson)
                    
                    // Dispatch the action
                    val result = actionDispatcher.dispatch(policyJson)
                    
                    // Log result
                    when (result) {
                        is ActionResult.Success -> {
                            Log.i(TAG, "Action executed successfully: ${result.message}")
                        }
                        is ActionResult.Debounced -> {
                            Log.d(TAG, "Action debounced: ${result.actionType}")
                        }
                        else -> {
                            Log.w(TAG, "Action failed: ${result.getMessage()}")
                        }
                    }
                }
                
                // Tick the Rust engine
                val timestampUs = System.currentTimeMillis() * 1000L
                zenbCoreApi.tick(timestampUs)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error in policy loop iteration", e)
            }
            
            // Wait before next poll
            delay(POLICY_POLL_INTERVAL_MS)
        }
    }

    /**
     * Extract ActionPolicy from dashboard JSON.
     * The dashboard contains the latest policy decision from the Rust engine.
     */
    private fun extractPolicyFromDashboard(dashboardJson: String): String? {
        // TODO: Parse dashboard JSON and extract policy field
        // For now, return null (no policy)
        // Real implementation would parse the JSON and check for a "current_policy" field
        return null
    }

    /**
     * Manually trigger a policy execution (for testing or manual override).
     */
    suspend fun executePolicyManually(policyJson: String): ActionResult {
        return actionDispatcher.dispatch(policyJson)
    }
}

/**
 * Example: Integration with existing FlowEngineService.
 *
 * This shows how to add ZenB policy execution to an existing service
 * that handles BLE/NFC flows.
 */
class FlowEngineService @Inject constructor(
    private val context: Context,
    private val flowEngineIntegration: FlowEngineIntegration,
    // Existing dependencies
    private val bleManager: BleManager?,
    private val nfcManager: NfcManager?
) {
    companion object {
        private const val TAG = "FlowEngineService"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Start the service: Initialize all flow engines.
     */
    fun start() {
        Log.i(TAG, "Starting FlowEngineService")

        // Start existing flows (BLE, NFC)
        startExistingFlows()

        // Start ZenB policy execution
        flowEngineIntegration.startPolicyLoop()

        // Observe policy decisions for logging/analytics
        serviceScope.launch {
            flowEngineIntegration.policyFlow.collect { policyJson ->
                Log.d(TAG, "Policy decision: $policyJson")
                // Could trigger UI updates, analytics events, etc.
            }
        }
    }

    /**
     * Stop the service: Clean up all resources.
     */
    fun stop() {
        Log.i(TAG, "Stopping FlowEngineService")

        // Stop ZenB policy execution
        flowEngineIntegration.stopPolicyLoop()

        // Stop existing flows
        stopExistingFlows()

        // Cancel service scope
        serviceScope.cancel()
    }

    private fun startExistingFlows() {
        // Start BLE scanning/connection
        bleManager?.startScanning()

        // Start NFC reader
        nfcManager?.enableReaderMode()

        Log.d(TAG, "Existing flows started")
    }

    private fun stopExistingFlows() {
        bleManager?.stopScanning()
        nfcManager?.disableReaderMode()

        Log.d(TAG, "Existing flows stopped")
    }
}

/**
 * Hilt/Dagger module for dependency injection.
 */
@dagger.Module
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
object FlowEngineModule {

    @dagger.Provides
    @Singleton
    fun provideFlowEngineIntegration(
        @dagger.hilt.android.qualifiers.ApplicationContext context: Context,
        zenbCoreApi: ZenbCoreApi,
        actionDispatcher: ActionDispatcher
    ): FlowEngineIntegration {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return FlowEngineIntegration(context, zenbCoreApi, actionDispatcher, scope)
    }

    @dagger.Provides
    @Singleton
    fun provideFlowEngineService(
        @dagger.hilt.android.qualifiers.ApplicationContext context: Context,
        flowEngineIntegration: FlowEngineIntegration,
        bleManager: BleManager?,
        nfcManager: NfcManager?
    ): FlowEngineService {
        return FlowEngineService(context, flowEngineIntegration, bleManager, nfcManager)
    }
}

// ============================================================================
// Stub Interfaces (to be implemented by existing Pandora modules)
// ============================================================================

interface BleManager {
    fun startScanning()
    fun stopScanning()
}

interface NfcManager {
    fun enableReaderMode()
    fun disableReaderMode()
}
