package com.vowi.vowidroid.core.ike

import com.vowi.vowidroid.core.esp.EspPolicyDropException
import com.vowi.vowidroid.core.esp.EspTunnel
import com.vowi.vowidroid.core.ims.ImsNetwork
import com.vowi.vowidroid.core.ipsec.TransportSecurity
import com.vowi.vowidroid.core.net.NetStack
import com.vowi.vowidroid.core.util.CoreLog
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A running WiFi Calling tunnel: the IKE session, the ESP dataplane and the
 * in-app IP stack that SIP and RTP use. ipsec-3gpp is applied to inner
 * packets on their way in and out of the stack.
 */
class Tunnel(private val session: IkeSession, private val mtu: Int = NetStack.MTU, private val onFailure: (Exception) -> Unit) : AutoCloseable, ImsNetwork {
    val evidence: TunnelEvidence get() = session.evidence
    override val security = TransportSecurity()
    override val pcscf: List<String> get() = session.evidence.pcscf

    @Volatile
    private var esp = EspTunnel(session.child)

    /** Kept briefly after a rekey for packets still on the old SA. */
    @Volatile
    private var previousEsp: EspTunnel? = null

    @Volatile
    private var previousUntil = 0L

    override val stack: NetStack = NetStack(session.child.innerLocalIpv4, session.child.innerLocalIpv6, mtu, ::transmit)
    private val closed = AtomicBoolean(false)

    init {
        stack.filter = object : NetStack.PacketFilter {
            override fun outbound(packet: ByteArray): ByteArray = security.protect(packet)
            override fun inbound(packet: ByteArray): Pair<ByteArray?, String> = security.unprotect(packet)
        }
        session.listener = object : IkeSession.Listener {
            override fun onEsp(packet: ByteArray) = receive(packet)

            override fun onChildRekeyed(child: ChildSaConfig) {
                previousEsp = esp
                previousUntil = System.currentTimeMillis() + 30_000
                esp = EspTunnel(child)
            }

            override fun onFailure(error: Exception) {
                if (closed.compareAndSet(false, true)) {
                    stack.close()
                    onFailure(error)
                }
            }
        }
        session.start()
    }

    private fun transmit(inner: ByteArray) {
        if (closed.get()) return
        val sealed = try {
            esp.seal(inner)
        } catch (_: EspPolicyDropException) {
            return // outside the negotiated selectors
        }
        try {
            session.sendEsp(sealed)
        } catch (error: Exception) {
            CoreLog.d(TAG, "could not send an ESP packet: ${error.message}")
        }
    }

    private fun receive(packet: ByteArray) {
        val inner = try {
            esp.open(packet)
        } catch (_: EspPolicyDropException) {
            // Router advertisements and other link-local chatter arrive outside the selectors.
            return
        } catch (error: Exception) {
            val previous = previousEsp
            if (previous != null && System.currentTimeMillis() < previousUntil) {
                try {
                    previous.open(packet)
                } catch (_: Exception) {
                    CoreLog.d(TAG, "dropped an inbound ESP packet: ${error.message}")
                    return
                }
            } else {
                CoreLog.d(TAG, "dropped an inbound ESP packet: ${error.message}")
                return
            }
        }
        stack.deliver(inner)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        stack.close()
        session.close()
    }

    companion object {
        private const val TAG = "Tunnel"
    }
}
