package com.autoguard.vpn.data.repository

import android.content.Context
import com.autoguard.vpn.data.model.ServerListResponse
import com.autoguard.vpn.data.model.VpnServer
import com.autoguard.vpn.di.DefaultClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Server Repository Class
 * Responsible for managing static server data from assets and connectivity testing
 */
@Singleton
class ServerRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @DefaultClient private val okHttpClient: OkHttpClient
) {
    private val _serverList = MutableStateFlow<List<VpnServer>>(emptyList())
    val serverList: StateFlow<List<VpnServer>> = _serverList.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    companion object {
        private const val SERVERS_FILE = "servers.json"
        private const val FALLBACK_TEST_URL = "https://www.bing.com"
    }

    /**
     * Load server list from static assets file
     */
    suspend fun fetchServerList(): Result<List<VpnServer>> {
        return withContext(Dispatchers.IO) {
            try {
                _isLoading.value = true
                _error.value = null
                
                val jsonString = context.assets.open(SERVERS_FILE).bufferedReader().use { it.readText() }
                val response = GsonHelper.fromJson<ServerListResponse>(jsonString)
                val servers = response.servers
                
                _serverList.value = servers
                _isLoading.value = false
                Result.success(servers)
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to load static server list"
                _isLoading.value = false
                Result.failure(e)
            }
        }
    }

    /**
     * Test server connectivity
     */
    suspend fun testServerConnectivity(server: VpnServer): Long {
        return withContext(Dispatchers.IO) {
            try {
                val startTime = System.currentTimeMillis()
                val socket = Socket()
                socket.connect(InetSocketAddress(server.endpoint, server.port), 3000)
                val latency = System.currentTimeMillis() - startTime
                socket.close()
                latency
            } catch (_: Exception) {
                try {
                    val request = Request.Builder()
                        .url(FALLBACK_TEST_URL)
                        .build()
                    val startTime = System.currentTimeMillis()
                    okHttpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) System.currentTimeMillis() - startTime else -1L
                    }
                } catch (_: Exception) {
                    -1L
                }
            }
        }
    }

    suspend fun testAllServers() {
        val currentList = _serverList.value
        if (currentList.isEmpty()) return

        _isLoading.value = true
        withContext(Dispatchers.IO) {
            val updatedList = currentList.map { server ->
                async {
                    val latency = testServerConnectivity(server)
                    server.copy(pingLatency = latency)
                }
            }.awaitAll()
            
            _serverList.value = updatedList
        }
        _isLoading.value = false
    }

    fun getServerById(serverId: String): VpnServer? {
        return _serverList.value.find { it.id == serverId }
    }

    fun getServersByCountry(country: String): List<VpnServer> {
        return _serverList.value.filter { it.country == country }
    }

    fun getCountries(): List<String> {
        return _serverList.value.map { it.country }.distinct().sorted()
    }

    fun getServersGroupedByCountry(): Map<String, List<VpnServer>> {
        return _serverList.value.groupBy { it.country }
    }
}

object GsonHelper {
    @PublishedApi
    internal val gson = com.google.gson.Gson()

    fun <T> toJson(obj: T): String {
        return gson.toJson(obj)
    }

    inline fun <reified T> fromJson(json: String): T {
        return try {
            val type = object : com.google.gson.reflect.TypeToken<T>() {}.type
            gson.fromJson(json, type)
        } catch (_: Exception) {
            gson.fromJson(json, T::class.java)
        }
    }
}
