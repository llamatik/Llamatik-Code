package com.llamatik.teammate.licensing

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.llamatik.teammate.settings.LlamatikAppSettings
import java.time.Instant
import java.time.temporal.ChronoUnit

private const val TRIAL_DAYS = 3L

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

    // TODO: Replace with JetBrains Marketplace-compatible licensing integration for freemium distribution.
}
