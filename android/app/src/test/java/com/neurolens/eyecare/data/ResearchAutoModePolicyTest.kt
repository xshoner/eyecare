package com.neurolens.eyecare.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ResearchAutoModePolicyTest {
    @Test fun freshDeploymentDefaultsOn() {
        assertEquals(InitialResearchAutoMode(true, false),
            ResearchAutoModePolicy.initial(false, false, false, false))
    }

    @Test fun existingConsentStaysOn() {
        assertEquals(InitialResearchAutoMode(true, false),
            ResearchAutoModePolicy.initial(true, true, true, false))
    }

    @Test fun priorWithdrawalOrOffStaysOff() {
        assertEquals(InitialResearchAutoMode(false, false),
            ResearchAutoModePolicy.initial(false, true, true, true))
        assertEquals(InitialResearchAutoMode(false, false),
            ResearchAutoModePolicy.initial(false, false, true, false))
    }

    @Test fun existingAccountWithoutLocalMarkerRequiresRemoteCheck() {
        assertEquals(InitialResearchAutoMode(false, true),
            ResearchAutoModePolicy.initial(false, true, false, false))
    }
}
