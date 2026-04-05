package com.airplay.receiver.server

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.net.InetAddress
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

                // AirPlay service properties
                val props = mapOf(
                    "deviceid" to "00:11:22:33:44:55",
                    "features" to "0x77",
                    "model" to "AndroidTV",
                    "srcvers" to "150.33",
                    "flags" to "0x04",
                    "pk" to "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    "pi" to "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
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
        @Suppress("DEPRECATION")
        val wifiInfo = wifiManager.connectionInfo
        @Suppress("DEPRECATION")
        val ipInt = wifiInfo.ipAddress
        val ipBytes = byteArrayOf(
            (ipInt and 0xff).toByte(),
            (ipInt shr 8 and 0xff).toByte(),
            (ipInt shr 16 and 0xff).toByte(),
            (ipInt shr 24 and 0xff).toByte()
        )
        return InetAddress.getByAddress(ipBytes)
    }

    fun getIpAddressString(): String {
        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val ipInt = wifiManager.connectionInfo.ipAddress
        return "${ipInt and 0xff}.${ipInt shr 8 and 0xff}.${ipInt shr 16 and 0xff}.${ipInt shr 24 and 0xff}"
    }
}
