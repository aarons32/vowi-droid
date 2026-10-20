package com.vowi.vowidroid.core

import com.vowi.vowidroid.core.ike.EpdgErrors
import com.vowi.vowidroid.core.ike.EpdgRejectedException
import com.vowi.vowidroid.core.ike.IkeException
import com.vowi.vowidroid.core.ike.NoProposalChosenException
import com.vowi.vowidroid.core.ims.RegistrationRejectedException
import com.vowi.vowidroid.core.sim.AkaMacFailureException
import com.vowi.vowidroid.core.sim.SimCardException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/** The short codes the status banner shows for a failed attempt. */
class ErrorCodesTest {
    @Test
    fun codesNameTheProtocolAndItsNumber() {
        assertEquals("IKE 9000", ErrorCodes.of(EpdgRejectedException(9000, "NON_3GPP_ACCESS_TO_EPC_NOT_ALLOWED", EpdgErrors.Policy.FATAL, explanation = "no")))
        assertEquals("IKE 14", ErrorCodes.of(NoProposalChosenException()))
        assertEquals("IKE 8193", ErrorCodes.of(IkeException("ike: the ePDG reported fatal notification 8193")))
        assertEquals("SIP 403", ErrorCodes.of(RegistrationRejectedException("403 Forbidden")))
        assertEquals("SIP", ErrorCodes.of(RegistrationRejectedException("registrar granted zero expiry")))
        assertEquals("SIM MAC", ErrorCodes.of(AkaMacFailureException()))
        assertEquals("SIM", ErrorCodes.of(SimCardException("the SIM card is not in")))
        assertEquals("timeout", ErrorCodes.of(IOException("no ePDG address for epdg.test answered", SocketTimeoutException("read"))))
        assertEquals("timeout", ErrorCodes.of(IOException("the ePDG did not answer")))
        assertEquals("no network", ErrorCodes.of(IOException("No network is connected")))
        assertEquals("no network", ErrorCodes.of(IOException("No mobile data network is connected")))
        assertEquals("", ErrorCodes.of(IllegalStateException("something else")))
    }

    @Test
    fun aWrappedCauseStillGivesItsCode() {
        val wrapped = IOException("tunnel", IOException("inner", EpdgRejectedException(10500, "NETWORK_FAILURE", EpdgErrors.Policy.BACK_OFF, explanation = "pause")))
        assertEquals("IKE 10500", ErrorCodes.of(wrapped))
    }
}
