package com.llamatik.teammate.licensing

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.llamatik.teammate.model.LicenseStatus
import com.llamatik.teammate.model.TrialState
import com.llamatik.teammate.settings.LlamatikAppSettings
import com.llamatik.teammate.ui.Strings
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date

@Service(Service.Level.APP)
class TrialService {

    // ── Marketplace licence ───────────────────────────────────────────────────

    /**
     * Returns the current Marketplace licence status.
     *
     * LICENSED – valid Marketplace licence found.
     * TRIAL    – IDE-managed trial is active.
     * FREE     – no licence and no active trial; free-tier features remain available.
     * UNKNOWN  – LicensingFacade not yet initialised (IDE startup); treat as FREE.
     */
    fun licenseStatus(): LicenseStatus {
        val licensed = runCatching { LicenseChecker.isLicensed() }.getOrNull()
        return when (licensed) {
            true  -> LicenseStatus.LICENSED
            false -> if (isTrialActive()) LicenseStatus.TRIAL else LicenseStatus.FREE
            null  -> LicenseStatus.UNKNOWN
        }
    }

    /** True when the plugin has a valid Marketplace licence or an active trial. */
    fun isPro(): Boolean = licenseStatus() == LicenseStatus.LICENSED || licenseStatus() == LicenseStatus.TRIAL

    /** Triggers the JetBrains licence registration dialog. */
    fun requestLicense(message: String = Strings.Trial.REQUEST_LICENSE_MESSAGE) {
        LicenseChecker.requestLicense(message)
    }

    // ── Feature gates (FREEMIUM) ──────────────────────────────────────────────

    /** Chat is always available. */
    fun isChatAllowed(): Boolean = true

    /** Agent is always available; Pro unlocks additional capabilities within the agent. */
    fun isAgentAllowed(): Boolean = true

    // ── Trial helpers ─────────────────────────────────────────────────────────

    /** True when the IDE-managed trial expiry date is in the future. */
    fun isTrialActive(): Boolean {
        val expiry = LicenseChecker.trialExpirationDate() ?: return false
        return expiry.after(Date())
    }

    fun statusLabel(): String = when (licenseStatus()) {
        LicenseStatus.LICENSED -> Strings.Trial.STATUS_LICENSED
        LicenseStatus.TRIAL    -> Strings.Trial.STATUS_ACTIVE
        LicenseStatus.FREE     -> Strings.Trial.STATUS_FREE
        LicenseStatus.UNKNOWN  -> Strings.Trial.STATUS_FREE
    }

    fun daysRemaining(): Int {
        val expiry = LicenseChecker.trialExpirationDate() ?: return 0
        return maxOf(0, ChronoUnit.DAYS.between(Instant.now(), expiry.toInstant()).toInt())
    }

    fun isInWarningPeriod(): Boolean = daysRemaining() in 1..5

    fun trialState(): TrialState {
        val settings = service<LlamatikAppSettings>().data()
        val remaining = daysRemaining()
        return TrialState(
            daysRemaining = remaining,
            isInWarningPeriod = remaining in 1..5,
            warningDismissed = settings.trialWarningDismissed,
        )
    }
}
