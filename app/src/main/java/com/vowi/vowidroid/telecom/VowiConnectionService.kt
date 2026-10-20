package com.vowi.vowidroid.telecom

import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.PhoneAccountHandle

/** Telecom's entry point for VowiDroid's self-managed calls; CallManager does the work. */
class VowiConnectionService : ConnectionService() {
    override fun onCreateOutgoingConnection(account: PhoneAccountHandle?, request: ConnectionRequest): Connection =
        CallManager.createOutgoing(account, request)

    override fun onCreateOutgoingConnectionFailed(account: PhoneAccountHandle?, request: ConnectionRequest?) =
        CallManager.outgoingFailed()

    override fun onCreateIncomingConnection(account: PhoneAccountHandle?, request: ConnectionRequest): Connection =
        CallManager.createIncoming(account, request)

    override fun onCreateIncomingConnectionFailed(account: PhoneAccountHandle?, request: ConnectionRequest?) =
        CallManager.incomingFailed(request)
}
