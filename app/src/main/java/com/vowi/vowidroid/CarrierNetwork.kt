package com.vowi.vowidroid

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Mobile data held for one request. Carrier servers such as the entitlement
 * server often answer only from the carrier's own network, so while Wi-Fi
 * is the phone's default route the request goes over cellular, as Android's
 * own entitlement client does. Closing the lease lets the phone drop
 * cellular data again.
 */
class CarrierNetwork private constructor(
    private val manager: ConnectivityManager,
    private val callback: ConnectivityManager.NetworkCallback,
    val network: Network,
) : AutoCloseable {
    override fun close() {
        runCatching { manager.unregisterNetworkCallback(callback) }
    }

    companion object {
        /** Mobile data with internet access, or null when none comes up within [timeoutMs] (airplane mode, no SIM in a slot, no plan). */
        fun request(context: Context, timeoutMs: Long): CarrierNetwork? {
            val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            val found = ArrayBlockingQueue<Network>(1)
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    found.offer(network)
                }
            }
            try {
                manager.requestNetwork(request, callback)
            } catch (_: RuntimeException) {
                return null
            }
            val network = found.poll(timeoutMs, TimeUnit.MILLISECONDS)
            if (network == null) {
                runCatching { manager.unregisterNetworkCallback(callback) }
                return null
            }
            return CarrierNetwork(manager, callback, network)
        }
    }
}
