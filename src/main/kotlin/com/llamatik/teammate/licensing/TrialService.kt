package com.llamatik.teammate.licensing

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.llamatik.teammate.model.TrialState
import com.llamatik.teammate.settings.LlamatikAppSettings
import java.time.Instant
import java.time.temporal.ChronoUnit

private const val TRIAL_DAYS = 30L

@Service(Service.Level.APP)
class TrialService {

    private val trialStart: Instant
        get() {
            val settings = service<LlamatikAppSettings>().data()
            if (settings.trialStartedAtEpoch == 0L) {
                settings.trialStartedAtEpoch = System.currentTimeMillis()
            }
            return Instant.ofEpochMilli(settings.trialStartedAtEpoch)
        }

    fun isTrialActive(): Boolean = ChronoUnit.DAYS.between(trialStart, Instant.now()) < TRIAL_DAYS

    fun statusLabel(): String = if (isTrialActive()) "Trial active" else "Trial expired"

    fun daysRemaining(): Int = maxOf(0, (TRIAL_DAYS - ChronoUnit.DAYS.between(trialStart, Instant.now())).toInt())

    fun isInWarningPeriod(): Boolean {
        val remaining = daysRemaining()
        return remaining in 1..5
    }

    fun trialState(): TrialState {
        val settings = service<LlamatikAppSettings>().data()
        val remaining = daysRemaining()
        return TrialState(
            trialStartMs = settings.trialStartedAtEpoch,
            daysRemaining = remaining,
            isExpired = remaining <= 0,
            isInWarningPeriod = remaining in 1..5,
            warningDismissed = settings.trialWarningDismissed,
        )
    }

    fun isAgentAllowed(): Boolean = isTrialActive()
    fun isChatAllowed(): Boolean = true // Chat always available

    // TODO: Replace with JetBrains Marketplace-compatible licensing integration for freemium distribution.
}
