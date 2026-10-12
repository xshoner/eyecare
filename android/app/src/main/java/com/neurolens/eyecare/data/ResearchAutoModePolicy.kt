package com.neurolens.eyecare.data

data class InitialResearchAutoMode(val enabled: Boolean, val checkPriorConsent: Boolean)

/** Preserve an explicit older OFF/withdrawal when upgrading to automatic sync. */
object ResearchAutoModePolicy {
    fun initial(previousConsent: Boolean, hasSession: Boolean, hasPseudonym: Boolean,
        pendingWithdrawal: Boolean): InitialResearchAutoMode {
        if (previousConsent) return InitialResearchAutoMode(true, false)
        if (pendingWithdrawal || hasPseudonym) return InitialResearchAutoMode(false, false)
        if (hasSession) return InitialResearchAutoMode(false, true)
        return InitialResearchAutoMode(true, false)
    }
}
