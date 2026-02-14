package com.autoguard.vpn.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autoguard.vpn.data.model.VpnConnectionState
import com.autoguard.vpn.data.model.VpnGateServer
import com.autoguard.vpn.data.model.VpnServer
import com.autoguard.vpn.data.repository.ServerRepository
import com.autoguard.vpn.data.repository.VpnGateStats
import com.autoguard.vpn.service.AutoGuardVpnService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.random.Random

/**
 * Main Interface ViewModel
 * Manages VPN connection status, server list, and user interaction
 * Supports fetching server data from VPN Gate
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    application: Application,
    private val serverRepository: ServerRepository
) : AndroidViewModel(application) {

    private val context = application.applicationContext

    // VPN Connection State
    val connectionState: StateFlow<VpnConnectionState> = AutoGuardVpnService.connectionState

    // Currently Connected Server
    val connectedServer: StateFlow<VpnServer?> = AutoGuardVpnService.connectedServer

    // Server List
    val serverList: StateFlow<List<VpnServer>> = serverRepository.serverList

    // VPN Gate Raw Server Data
    val vpnGateServers: StateFlow<List<VpnGateServer>> = serverRepository.vpnGateServers

    // VPN Gate Statistics
    val vpnGateStats: StateFlow<VpnGateStats> = MutableStateFlow(
        VpnGateStats(0, 0, 0, 0, "")
    ).also { flow ->
        viewModelScope.launch {
            serverRepository.vpnGateServers.collect { servers ->
                flow.value = serverRepository.getVpnGateStats()
            }
        }
    }

    // Loading State
    val isLoading: StateFlow<Boolean> = serverRepository.isLoading

    // Error Message
    val error: StateFlow<String?> = serverRepository.error

    // Current Data Source Type
    val dataSourceType: StateFlow<ServerRepository.DataSourceType> = serverRepository.dataSourceType

    // Whether to show server selector
    private val _showServerSelector = MutableStateFlow(false)
    val showServerSelector: StateFlow<Boolean> = _showServerSelector.asStateFlow()

    // Currently selected server
    private val _selectedServer = MutableStateFlow<VpnServer?>(null)
    val selectedServer: StateFlow<VpnServer?> = _selectedServer.asStateFlow()

    // User country preference
    private val _preferredCountry = MutableStateFlow<String?>(null)
    val preferredCountry: StateFlow<String?> = _preferredCountry.asStateFlow()

    // Whether Kill Switch is enabled
    private val _killSwitchEnabled = MutableStateFlow(false)
    val killSwitchEnabled: StateFlow<Boolean> = _killSwitchEnabled.asStateFlow()

    // Auto-connect mode (random server with ping < 500ms)
    private val _autoConnectEnabled = MutableStateFlow(true)
    val autoConnectEnabled: StateFlow<Boolean> = _autoConnectEnabled.asStateFlow()

    // Auto-disconnect timer job
    private var autoDisconnectJob: Job? = null

    // Connection start time for timeout tracking
    private var connectionStartTime: Long = 0

    // Flag to track if initial connection has been attempted
    private var initialConnectionAttempted = false

    init {
        // Automatically fetch server list and test connectivity on startup
        fetchServers()
        
        // Force Simplified Chinese
        applyLanguage("zh-CN")

        // Auto-connect on startup: randomly select a server with ping < 500ms
        viewModelScope.launch {
            // Wait for server list to be fetched and tested
            delay(3000)
            
            if (!initialConnectionAttempted && _autoConnectEnabled.value) {
                initialConnectionAttempted = true
                quickConnect()
            }
        }
    }

    private fun applyLanguage(languageCode: String) {
        val appLocale: LocaleListCompat = LocaleListCompat.forLanguageTags(languageCode)
        AppCompatDelegate.setApplicationLocales(appLocale)
    }

    /**
     * Fetch server list
     * Prioritizes fetching from VPN Gate, then automatically tests connectivity
     */
    fun fetchServers() {
        viewModelScope.launch {
            serverRepository.fetchServerList().onSuccess {
                testAllServers()
            }
        }
    }

    /**
     * Fetch server list from VPN Gate and test connectivity
     */
    fun fetchFromVpnGate() {
        viewModelScope.launch {
            serverRepository.fetchFromVpnGate().onSuccess {
                testAllServers()
            }
        }
    }

    /**
     * Test connectivity for all servers
     */
    fun testAllServers() {
        viewModelScope.launch {
            serverRepository.testAllServers()
        }
    }

    /**
     * Switch data source
     */
    fun switchDataSource(type: ServerRepository.DataSourceType) {
        viewModelScope.launch {
            serverRepository.switchDataSource(type).onSuccess {
                testAllServers()
            }
        }
    }

    /**
     * Select a server
     */
    fun selectServer(server: VpnServer) {
        _selectedServer.value = server
        _showServerSelector.value = false
    }

    /**
     * Show server selector
     */
    fun showServerSelector() {
        _showServerSelector.value = true
    }

    /**
     * Hide server selector
     */
    fun hideServerSelector() {
        _showServerSelector.value = false
    }

    /**
     * Toggle VPN connection state
     */
    fun toggleConnection() {
        when (connectionState.value) {
            VpnConnectionState.CONNECTED -> {
                disconnect()
            }
            VpnConnectionState.DISCONNECTED -> {
                // If auto-connect is enabled, use quick connect
                if (_autoConnectEnabled.value) {
                    quickConnect()
                } else {
                    // Original behavior: use selected server or first available
                    val server = _selectedServer.value ?: serverList.value.firstOrNull()
                    if (server != null) {
                        connect(server)
                    }
                }
            }
            else -> {
                // Connecting or disconnecting, do nothing
            }
        }
    }

    /**
     * Set preferred country
     */
    fun setPreferredCountry(countryCode: String?) {
        _preferredCountry.value = countryCode
    }

    /**
     * Toggle Kill Switch
     */
    fun toggleKillSwitch() {
        _killSwitchEnabled.value = !_killSwitchEnabled.value
    }

    /**
     * Set Kill Switch status
     */
    fun setKillSwitchEnabled(enabled: Boolean) {
        _killSwitchEnabled.value = enabled
    }

    /**
     * Get best server (lowest latency)
     */
    fun getBestServer(): VpnServer? {
        return serverList.value.filter { it.pingLatency > 0 }.minByOrNull { it.pingLatency }
    }

    /**
     * Get high-quality servers (score >= 10000)
     */
    fun getHighQualityServers(): List<VpnServer> {
        return serverRepository.getHighQualityServers()
    }

    /**
     * Filter servers by country
     */
    fun getServersByCountry(country: String): List<VpnServer> {
        return serverRepository.getServersByCountry(country)
    }

    /**
     * Get list of all countries
     */
    fun getCountries(): List<String> {
        return serverRepository.getCountries()
    }

    /**
     * Get details for a VPN Gate server
     */
    fun getVpnGateServerDetails(server: VpnServer): VpnGateServer? {
        val ip = server.endpoint.split(":").firstOrNull() ?: return null
        return serverRepository.getVpnGateServerByIp(ip)
    }

    /**
     * Get connection info text
     */
    fun getConnectionInfo(server: VpnServer): String {
        val vpnGateServer = getVpnGateServerDetails(server)
        return if (vpnGateServer != null) {
            """
            |Server: ${vpnGateServer.country}
            |IP: ${vpnGateServer.ipAddress}
            |ISP: ${vpnGateServer.operatorName}
            |Score: ${vpnGateServer.getQualityDescription()}
            |Throughput: ${vpnGateServer.throughput.format(2)} Mbps
            |Sessions: ${vpnGateServer.vpnSessions}
            |Logging Policy: ${vpnGateServer.loggingPolicy}
            |Type: ${vpnGateServer.getConnectionType()}
            |Latency: ${if (server.pingLatency > 0) "${server.pingLatency}ms" else "Measuring..."}
            """.trimMargin()
        } else {
            "Connection info unavailable"
        }
    }
    
    private fun Double.format(digits: Int) = "%.${digits}f".format(this)

    /**
     * Clear error message
     */
    fun clearError() {
        // Error is managed by the repository
    }

    /**
     * Toggle auto-connect mode
     * When enabled, automatically selects a random server with ping < 500ms and connects
     */
    fun toggleAutoConnect() {
        _autoConnectEnabled.value = !_autoConnectEnabled.value
    }

    /**
     * Set auto-connect mode
     */
    fun setAutoConnectEnabled(enabled: Boolean) {
        _autoConnectEnabled.value = enabled
    }

    /**
     * Get servers with ping less than 500ms
     */
    fun getServersWithLowLatency(): List<VpnServer> {
        return serverList.value.filter { server ->
            server.pingLatency in 1..500
        }
    }

    /**
     * Select a random server from servers with ping < 500ms
     */
    fun getRandomLowLatencyServer(): VpnServer? {
        val lowLatencyServers = getServersWithLowLatency()
        return if (lowLatencyServers.isNotEmpty()) {
            lowLatencyServers[Random.nextInt(lowLatencyServers.size)]
        } else {
            null
        }
    }

    /**
     * Auto-connect to a random server with ping < 500ms within 500ms timeout
     */
    fun autoConnect() {
        viewModelScope.launch {
            // First, ensure we have server list
            if (serverList.value.isEmpty()) {
                fetchServers()
                // Wait a bit for servers to be fetched
                delay(2000)
            }

            // Try to find a server with ping < 500ms
            var selectedServer = getRandomLowLatencyServer()

            // If no server with ping < 500ms, try to test all servers first
            if (selectedServer == null) {
                testAllServers()
                delay(1000) // Wait for tests to complete
                selectedServer = getRandomLowLatencyServer()
            }

            // If still no suitable server, use any available server
            if (selectedServer == null) {
                selectedServer = serverList.value.firstOrNull { it.pingLatency > 0 }
            }

            // If still no server, use first available
            if (selectedServer == null) {
                selectedServer = serverList.value.firstOrNull()
            }

            if (selectedServer != null) {
                _selectedServer.value = selectedServer
                connect(selectedServer)
            }
        }
    }

    /**
     * Start auto-disconnect timer
     * Disconnects after specified milliseconds (default: 5 minutes)
     */
    fun startAutoDisconnect(delayMs: Long = 5 * 60 * 1000) {
        autoDisconnectJob?.cancel()
        autoDisconnectJob = viewModelScope.launch {
            delay(delayMs)
            disconnect()
        }
    }

    /**
     * Cancel auto-disconnect timer
     */
    fun cancelAutoDisconnect() {
        autoDisconnectJob?.cancel()
        autoDisconnectJob = null
    }

    /**
     * Connect to VPN with auto-disconnect option
     */
    private fun connect(server: VpnServer, autoDisconnect: Boolean = false) {
        connectionStartTime = System.currentTimeMillis()
        
        val context = getApplication<Application>()
        val intent = Intent(context, AutoGuardVpnService::class.java).apply {
            action = AutoGuardVpnService.ACTION_CONNECT
            putExtra(AutoGuardVpnService.EXTRA_SERVER, server)
        }
        context.startForegroundService(intent)

        // If auto-disconnect is enabled, schedule disconnect after 5 minutes
        if (autoDisconnect) {
            startAutoDisconnect()
        }
    }

    /**
     * Disconnect from VPN
     */
    private fun disconnect() {
        cancelAutoDisconnect()
        
        val context = getApplication<Application>()
        val intent = Intent(context, AutoGuardVpnService::class.java).apply {
            action = AutoGuardVpnService.ACTION_DISCONNECT
        }
        context.startService(intent)
    }

    /**
     * Quick connect - random server with ping < 500ms
     * This is the main function for quick connection
     */
    fun quickConnect() {
        viewModelScope.launch {
            // Ensure we have fresh server list
            if (serverList.value.isEmpty()) {
                fetchServers()
                delay(2000)
            }

            // Get random low latency server
            var selectedServer = getRandomLowLatencyServer()

            // If no suitable server, test connectivity first
            if (selectedServer == null) {
                testAllServers()
                delay(1500)
                selectedServer = getRandomLowLatencyServer()
            }

            // Fallback to best server if still no low latency server
            if (selectedServer == null) {
                selectedServer = getBestServer()
            }

            // Final fallback
            if (selectedServer == null) {
                selectedServer = serverList.value.firstOrNull()
            }

            selectedServer?.let { server ->
                _selectedServer.value = server
                connect(server)
            }
        }
    }

    /**
     * Update VPN list from API
     */
    fun updateVpnList() {
        viewModelScope.launch {
            serverRepository.fetchServerList().onSuccess {
                testAllServers()
            }
        }
    }
}
