package com.vowi.vowidroid.telecom

import android.content.Context
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.telecom.CallAudioState
import android.telecom.CallEndpoint
import android.telecom.Connection
import android.telecom.StatusHints
import android.telecom.TelecomManager
import com.vowi.vowidroid.R

/** One VowiDroid call as Telecom sees it. User actions are forwarded to CallManager. */
class VowiConnection(
    val deviceId: String,
    val number: String,
    val outgoing: Boolean,
) : Connection() {
    /** VowiDroid's call ID; known once the dial request returns, or from the start for incoming calls. */
    @Volatile
    var callId: String? = null

    @Volatile
    var endpoints: List<CallEndpoint> = emptyList()
        private set

    @Volatile
    var currentEndpoint: CallEndpoint? = null
        private set

    init {
        // Every VowiDroid call goes over Wi-Fi; dialers, cars and watches show that (public from Android 11).
        connectionProperties = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) PROPERTY_SELF_MANAGED or PROPERTY_WIFI else PROPERTY_SELF_MANAGED
        // Hold lets VowiDroid swap its own two calls, and lets Android hold a VowiDroid call for another app's call.
        connectionCapabilities = CAPABILITY_MUTE or CAPABILITY_HOLD or CAPABILITY_SUPPORT_HOLD
        audioModeIsVoip = true
        setAddress(Uri.fromParts("tel", number, null), TelecomManager.PRESENTATION_ALLOWED)
    }

    /** The label and icon other call screens show under the caller, e.g. "WiFi Calling via Work". */
    fun describe(context: Context, lineName: String?) {
        val label = if (lineName != null) context.getString(R.string.incall_via, lineName) else context.getString(R.string.incall_wifi_call)
        statusHints = StatusHints(label, Icon.createWithResource(context, R.drawable.ic_notification), null)
    }

    /** HD voice (AMR-WB or EVS), which AOSP's in-call screen marks with an HD icon (public from Android 11). */
    fun setHighDefinition(hd: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val properties = if (hd) connectionProperties or PROPERTY_HIGH_DEF_AUDIO else connectionProperties and PROPERTY_HIGH_DEF_AUDIO.inv()
        if (properties != connectionProperties) connectionProperties = properties
    }

    override fun onAnswer() = CallManager.answer(this)

    override fun onAnswer(videoState: Int) = CallManager.answer(this)

    override fun onReject() = CallManager.reject(this)

    override fun onDisconnect() = CallManager.hangup(this)

    override fun onAbort() = CallManager.hangup(this)

    override fun onHold() = CallManager.hold(this)

    override fun onUnhold() = CallManager.resume(this)

    override fun onShowIncomingCallUi() = CallManager.showIncomingCallUi(this)

    /** DTMF from Telecom, e.g. a car or headset keypad. */
    override fun onPlayDtmfTone(c: Char) = CallManager.sendDtmf(c, this)

    override fun onStopDtmfTone() = Unit

    @Deprecated("Replaced by call endpoints on Android 14")
    override fun onCallAudioStateChanged(state: CallAudioState?) {
        CallManager.onRoutesChanged(this)
    }

    override fun onCallEndpointChanged(endpoint: CallEndpoint) {
        currentEndpoint = endpoint
        CallManager.onRoutesChanged(this)
    }

    override fun onAvailableCallEndpointsChanged(available: List<CallEndpoint>) {
        endpoints = available
        CallManager.onRoutesChanged(this)
    }
}
