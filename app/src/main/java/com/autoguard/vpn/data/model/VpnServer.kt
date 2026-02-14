package com.autoguard.vpn.data.model

import android.os.Parcelable
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize

/**
 * VPN服务器数据模型
 * 增加协议、配置以及凭据支持
 */
@Parcelize
data class VpnServer(
    @SerializedName("id")
    val id: String,

    @SerializedName("name")
    val name: String,

    @SerializedName("country")
    val country: String,

    @SerializedName("city")
    val city: String,

    @SerializedName("endpoint")
    val endpoint: String,

    @SerializedName("port")
    val port: Int = 443,

    @SerializedName("protocol")
    val protocol: String = "OpenVPN",

    @SerializedName("config")
    val config: String? = null,

    @SerializedName("username")
    val username: String? = "vpn",

    @SerializedName("password")
    val password: String? = "vpn",

    @SerializedName("psk")
    val psk: String? = "vpn",

    @SerializedName("publicKey")
    val publicKey: String? = null,

    @SerializedName("allowedIps")
    val allowedIps: String? = "0.0.0.0/0",

    @SerializedName("pingLatency")
    val pingLatency: Long = -1L,

    @SerializedName("vpnSessions")
    val vpnSessions: Int? = null,

    @SerializedName("throughput")
    val throughput: Double? = null
) : Parcelable {
    fun getDisplayName(): String {
        return if (city.isNotEmpty()) "$country - $city" else name
    }

    fun getFlagEmoji(): String {
        return countryCodeToEmoji(country)
    }

    private fun countryCodeToEmoji(code: String): String {
        val upperCode = code.uppercase()
        if (upperCode.length != 2 || !upperCode.all { it in 'A'..'Z' }) return ""
        val firstChar = Character.codePointAt(upperCode, 0) - 0x41 + 0x1F1E6
        val secondChar = Character.codePointAt(upperCode, 1) - 0x41 + 0x1F1E6
        return String(Character.toChars(firstChar)) + String(Character.toChars(secondChar))
    }
}

data class ServerListResponse(
    @SerializedName("servers")
    val servers: List<VpnServer>,
    @SerializedName("version")
    val version: String,
    @SerializedName("lastUpdated")
    val lastUpdated: String
)

enum class VpnConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    ERROR
}
