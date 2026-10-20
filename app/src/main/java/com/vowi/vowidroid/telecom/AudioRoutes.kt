package com.vowi.vowidroid.telecom

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Build
import android.os.OutcomeReceiver
import android.telecom.CallAudioState
import android.telecom.CallEndpoint
import android.telecom.CallEndpointException
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat

/** One place call audio can go, as the in-call screen offers it. */
data class AudioRoute(
    val key: String,
    val type: Type,
    /** A Bluetooth device's or endpoint's own name, when Android gives one. */
    val name: String?,
) {
    enum class Type { EARPIECE, SPEAKER, WIRED_HEADSET, BLUETOOTH, STREAMING }
}

/**
 * The audio routes of a VowiDroid call and how to switch between them. Android 14
 * and later describe them as call endpoints; earlier versions use the call
 * audio state, where Bluetooth devices may have no readable name.
 */
object AudioRoutes {
    private const val TAG = "VowiAudioRoute"

    /** The routes [connection] offers, and the one in use. */
    fun of(connection: VowiConnection): Pair<List<AudioRoute>, AudioRoute?> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val routes = connection.endpoints.map(::fromEndpoint)
            val current = connection.currentEndpoint?.let(::fromEndpoint)
            return routes to (routes.firstOrNull { it.key == current?.key } ?: current)
        }
        @Suppress("DEPRECATION")
        val state = connection.callAudioState ?: return emptyList<AudioRoute>() to null
        return fromAudioState(state)
    }

    /** Moves the call's audio to [route]. */
    fun select(context: Context, connection: VowiConnection, route: AudioRoute) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val endpoint = connection.endpoints.firstOrNull { it.identifier.toString() == route.key } ?: return
            connection.requestCallEndpointChange(
                endpoint,
                ContextCompat.getMainExecutor(context),
                object : OutcomeReceiver<Void, CallEndpointException> {
                    override fun onResult(result: Void?) = Unit
                    override fun onError(error: CallEndpointException) {
                        Log.w(TAG, "audio route change failed", error)
                    }
                },
            )
            return
        }
        @Suppress("DEPRECATION")
        when (route.type) {
            AudioRoute.Type.BLUETOOTH -> {
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    connection.callAudioState?.supportedBluetoothDevices?.firstOrNull { bluetoothKey(it) == route.key }
                } else {
                    null
                }
                if (device != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    connection.requestBluetoothAudio(device)
                } else {
                    connection.setAudioRoute(CallAudioState.ROUTE_BLUETOOTH)
                }
            }
            AudioRoute.Type.EARPIECE -> connection.setAudioRoute(CallAudioState.ROUTE_EARPIECE)
            AudioRoute.Type.SPEAKER -> connection.setAudioRoute(CallAudioState.ROUTE_SPEAKER)
            AudioRoute.Type.WIRED_HEADSET -> connection.setAudioRoute(CallAudioState.ROUTE_WIRED_HEADSET)
            AudioRoute.Type.STREAMING -> Unit
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun fromEndpoint(endpoint: CallEndpoint): AudioRoute {
        val type = when (endpoint.endpointType) {
            CallEndpoint.TYPE_EARPIECE -> AudioRoute.Type.EARPIECE
            CallEndpoint.TYPE_SPEAKER -> AudioRoute.Type.SPEAKER
            CallEndpoint.TYPE_WIRED_HEADSET -> AudioRoute.Type.WIRED_HEADSET
            CallEndpoint.TYPE_BLUETOOTH -> AudioRoute.Type.BLUETOOTH
            else -> AudioRoute.Type.STREAMING
        }
        val name = endpoint.endpointName.toString().takeIf { type == AudioRoute.Type.BLUETOOTH && it.isNotBlank() }
        return AudioRoute(endpoint.identifier.toString(), type, name)
    }

    @Suppress("DEPRECATION")
    private fun fromAudioState(state: CallAudioState): Pair<List<AudioRoute>, AudioRoute?> {
        val routes = ArrayList<AudioRoute>()
        val mask = state.supportedRouteMask
        if (mask and CallAudioState.ROUTE_EARPIECE != 0) routes += AudioRoute("earpiece", AudioRoute.Type.EARPIECE, null)
        if (mask and CallAudioState.ROUTE_WIRED_HEADSET != 0) routes += AudioRoute("wired", AudioRoute.Type.WIRED_HEADSET, null)
        if (mask and CallAudioState.ROUTE_SPEAKER != 0) routes += AudioRoute("speaker", AudioRoute.Type.SPEAKER, null)
        var activeBluetooth: String? = null
        if (mask and CallAudioState.ROUTE_BLUETOOTH != 0) {
            val devices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) state.supportedBluetoothDevices.toList() else emptyList()
            if (devices.isEmpty()) {
                routes += AudioRoute("bluetooth", AudioRoute.Type.BLUETOOTH, null)
            } else {
                devices.forEach { routes += AudioRoute(bluetoothKey(it), AudioRoute.Type.BLUETOOTH, bluetoothName(it)) }
            }
            activeBluetooth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) state.activeBluetoothDevice?.let(::bluetoothKey) else null
        }
        val current = when (state.route) {
            CallAudioState.ROUTE_EARPIECE -> routes.firstOrNull { it.type == AudioRoute.Type.EARPIECE }
            CallAudioState.ROUTE_SPEAKER -> routes.firstOrNull { it.type == AudioRoute.Type.SPEAKER }
            CallAudioState.ROUTE_WIRED_HEADSET -> routes.firstOrNull { it.type == AudioRoute.Type.WIRED_HEADSET }
            CallAudioState.ROUTE_BLUETOOTH -> routes.firstOrNull { it.key == activeBluetooth } ?: routes.firstOrNull { it.type == AudioRoute.Type.BLUETOOTH }
            else -> null
        }
        return routes to current
    }

    // Reading a Bluetooth device's address or name needs BLUETOOTH_CONNECT on Android 12 and 13,
    // which VowiDroid does not ask for, so both fall back to something stable.
    private fun bluetoothKey(device: BluetoothDevice): String =
        "bt:" + (runCatching { device.address }.getOrNull() ?: device.hashCode().toString())

    private fun bluetoothName(device: BluetoothDevice): String? =
        runCatching { @Suppress("MissingPermission") device.name }.getOrNull()?.takeIf { it.isNotBlank() }
}
