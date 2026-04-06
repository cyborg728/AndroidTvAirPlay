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
 *
 * Registers both _airplay._tcp and _raop._tcp services — modern iOS
 * requires both for AirPlay 2 video discovery.
 */
class AirPlayMdnsService(private val context: Context) {

    companion object {
        private const val TAG = "AirPlayMdns"
        private const val AIRPLAY_SERVICE_TYPE = "_airplay._tcp.local."
        private const val RAOP_SERVICE_TYPE = "_raop._tcp.local."
        private const val AIRPLAY_PORT = 7000
    }

    private var jmDNS: JmDNS? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var airplayServiceInfo: ServiceInfo? = null
    private var raopServiceInfo: ServiceInfo? = null
    private var deviceMac: String = "AA:BB:CC:DD:EE:FF"

    fun start(deviceName: String) {
        Thread {
            try {
                val wifiManager = context.applicationContext
                    .getSystemService(Context.WIFI_SERVICE) as WifiManager
                multicastLock = wifiManager.createMulticastLock("airplay-mdns").apply {
                    setReferenceCounted(true)
                    acquire()
                }

                val ipAddress = getDeviceIpAddress(wifiManager)
                Log.d(TAG, "Device IP: $ipAddress")

                jmDNS = JmDNS.create(ipAddress, deviceName)

                deviceMac = getMacAddress() ?: "AA:BB:CC:DD:EE:FF"
                val macHex = deviceMac.replace(":", "")

                // === Register _airplay._tcp ===
                val airplayProps = mapOf(
                    "deviceid" to deviceMac,
                    "features" to "0x5A7FFFF7,0x1E",
                    "flags" to "0x44",
                    "model" to "AppleTV3,2",
                    "pi" to "2e388006-13ba-4041-9a67-25dd4a43d536",
                    "pk" to "b07727d6f6cd6e08b58571d525391f99be98e8744e5dbcee5ccb705485e05b71",
                    "srcvers" to "220.68",
                    "vv" to "2"
                )

                airplayServiceInfo = ServiceInfo.create(
                    AIRPLAY_SERVICE_TYPE,
                    deviceName,
                    AIRPLAY_PORT,
                    0, 0,
                    airplayProps
                )
                jmDNS?.registerService(airplayServiceInfo)
                Log.d(TAG, "AirPlay service registered: $deviceName on port $AIRPLAY_PORT")

                // === Register _raop._tcp ===
                // RAOP service name format: MACADDRESS@DeviceName
                val raopName = "${macHex}@${deviceName}"
                val raopProps = mapOf(
                    "am" to "AppleTV3,2",
                    "cn" to "0,1,2,3",
                    "da" to "true",
                    "et" to "0,3,5",
                    "ft" to "0x5A7FFFF7,0x1E",
                    "md" to "0,1,2",
                    "pk" to "b07727d6f6cd6e08b58571d525391f99be98e8744e5dbcee5ccb705485e05b71",
                    "sf" to "0x44",
                    "sr" to "44100",
                    "ss" to "16",
                    "sv" to "false",
                    "tp" to "UDP",
                    "vn" to "65537",
                    "vs" to "220.68",
                    "vv" to "2"
                )

                raopServiceInfo = ServiceInfo.create(
                    RAOP_SERVICE_TYPE,
                    raopName,
                    AIRPLAY_PORT,
                    0, 0,
                    raopProps
                )
                jmDNS?.registerService(raopServiceInfo)
                Log.d(TAG, "RAOP service registered: $raopName on port $AIRPLAY_PORT")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start mDNS service", e)
            }
        }.start()
    }

    fun stop() {
        Thread {
            try {
                jmDNS?.unregisterAllServices()
                jmDNS?.close()
                jmDNS = null

                multicastLock?.let {
                    if (it.isHeld) it.release()
                }
                multicastLock = null
                Log.d(TAG, "mDNS services stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping mDNS service", e)
            }
        }.start()
    }

    fun getDeviceId(): String = deviceMac

    private fun getDeviceIpAddress(wifiManager: WifiManager): InetAddress {
        getIpFromNetworkInterface()?.let { return it }

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
                val name = intf.name.lowercase()
                if (!name.startsWith("wlan") && !name.startsWith("eth") && !name.startsWith("en")) continue
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
