package com.airplay.receiver.server

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/**
 * Manages mDNS (Bonjour) advertisement so Apple devices can discover
 * this Android TV as an AirPlay receiver on the local network.
 */
class AirPlayMdnsService(private val context: Context) {

    companion object {
        private const val TAG = "AirPlayMdns"
        private const val AIRPLAY_SERVICE_TYPE = "_airplay._tcp.local."
        private const val AIRPLAY_PORT = 7000
    }

    private var jmDNS: JmDNS? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var serviceInfo: ServiceInfo? = null

    fun start(deviceName: String) {
        Thread {
            try {
                // Acquire multicast lock to receive mDNS packets
                val wifiManager = context.applicationContext
                    .getSystemService(Context.WIFI_SERVICE) as WifiManager
                multicastLock = wifiManager.createMulticastLock("airplay-mdns").apply {
                    setReferenceCounted(true)
                    acquire()
                }

                val ipAddress = getDeviceIpAddress(wifiManager)
                Log.d(TAG, "Device IP: $ipAddress")

                jmDNS = JmDNS.create(ipAddress, deviceName)

                val deviceId = getMacAddress() ?: "AA:BB:CC:DD:EE:FF"

                // AirPlay service properties
                // features bitmask: video(1) + photo(2) + slideshow(4) + screen(8) + audio(16) + video_http(32) + video_volume(64)
                // 0x527FFFF7 is a commonly accepted value for video-capable AirPlay receivers
                val props = mapOf(
                    "deviceid" to deviceId,
                    "features" to "0x527FFFF7",
                    "model" to "AppleTV3,2",
                    "srcvers" to "220.68",
                    "flags" to "0x44",
                    "pk" to "b07727d6f6cd6e08b58571d525391f99be98e8744e5dbcee5ccb705485e05b71",
                    "pi" to "2e388006-13ba-4041-9a67-25dd4a43d536",
                    "vv" to "2"
                )

                serviceInfo = ServiceInfo.create(
                    AIRPLAY_SERVICE_TYPE,
                    deviceName,
                    AIRPLAY_PORT,
                    0, // weight
                    0, // priority
                    props
                )

                jmDNS?.registerService(serviceInfo)
                Log.d(TAG, "AirPlay mDNS service registered: $deviceName on port $AIRPLAY_PORT")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start mDNS service", e)
            }
        }.start()
    }

    fun stop() {
        Thread {
            try {
                serviceInfo?.let { jmDNS?.unregisterService(it) }
                jmDNS?.unregisterAllServices()
                jmDNS?.close()
                jmDNS = null

                multicastLock?.let {
                    if (it.isHeld) it.release()
                }
                multicastLock = null

                Log.d(TAG, "mDNS service stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping mDNS service", e)
            }
        }.start()
    }

    private fun getDeviceIpAddress(wifiManager: WifiManager): InetAddress {
        // Try NetworkInterface first (no permissions needed)
        getIpFromNetworkInterface()?.let { return it }

        // Fallback to WifiManager (needs ACCESS_FINE_LOCATION on Android 8+)
        @Suppress("DEPRECATION")
        val ipInt = wifiManager.connectionInfo.ipAddress
        if (ipInt != 0) {
            val ipBytes = byteArrayOf(
                (ipInt and 0xff).toByte(),
                (ipInt shr 8 and 0xff).toByte(),
                (ipInt shr 16 and 0xff).toByte(),
                (ipInt shr 24 and 0xff).toByte()
            )
            return InetAddress.getByAddress(ipBytes)
        }

        return InetAddress.getLocalHost()
    }

    private fun getIpFromNetworkInterface(): InetAddress? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (intf in interfaces) {
                // Look for wlan or eth interfaces
                val name = intf.name.lowercase()
                if (!name.startsWith("wlan") && !name.startsWith("eth") && !name.startsWith("en")) {
                    continue
                }
                if (!intf.isUp || intf.isLoopback) continue

                for (addr in intf.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        Log.d(TAG, "Found IP via NetworkInterface(${intf.name}): ${addr.hostAddress}")
                        return addr
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting IP from NetworkInterface", e)
        }
        return null
    }

    private fun getMacAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (intf in interfaces) {
                val name = intf.name.lowercase()
                if (!name.startsWith("wlan") && !name.startsWith("eth") && !name.startsWith("en")) continue
                val mac = intf.hardwareAddress ?: continue
                if (mac.isEmpty()) continue
                return mac.joinToString(":") { String.format("%02X", it) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting MAC address", e)
        }
        return null
    }

    fun getIpAddressString(): String {
        getIpFromNetworkInterface()?.let { return it.hostAddress ?: "0.0.0.0" }

        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val ipInt = wifiManager.connectionInfo.ipAddress
        if (ipInt != 0) {
            return "${ipInt and 0xff}.${ipInt shr 8 and 0xff}.${ipInt shr 16 and 0xff}.${ipInt shr 24 and 0xff}"
        }
        return "0.0.0.0"
    }
}
