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
     * LICENSED   – valid Marketplace licence found.
     * TRIAL      – no licence, but IDE-managed trial is still active.
     * EXPIRED    – no licence and trial has ended (or was never started).
     * UNKNOWN    – LicensingFacade not yet initialised (IDE startup); callers
     *              should treat this as "allow" and re-check later.
     */
    fun licenseStatus(): LicenseStatus {
        val licensed = runCatching { LicenseChecker.isLicensed() }.getOrNull()
        return when (licensed) {
            true  -> LicenseStatus.LICENSED
            false -> if (isTrialActive()) LicenseStatus.TRIAL else LicenseStatus.EXPIRED
            null  -> LicenseStatus.UNKNOWN
        }
    }

    /** True when the plugin has either a valid Marketplace licence or an active trial. */
    fun isAllowed(): Boolean = licenseStatus() != LicenseStatus.EXPIRED

    /** Triggers the JetBrains licence registration dialog. */
    fun requestLicense(message: String = Strings.Trial.REQUEST_LICENSE_MESSAGE) {
        LicenseChecker.requestLicense(message)
    }

    // ── Feature gates (FREEMIUM) ──────────────────────────────────────────────

    /** Chat is always free. */
    fun isChatAllowed(): Boolean = true

    /** Agent requires a licence or an active trial. */
    fun isAgentAllowed(): Boolean = isAllowed()

    // ── Trial helpers ─────────────────────────────────────────────────────────

    /** True when the IDE-managed trial expiry is in the future. */
    fun isTrialActive(): Boolean {
        val expiry = LicenseChecker.trialExpirationDate() ?: return false
        return expiry.after(Date())
    }

    fun statusLabel(): String = when (licenseStatus()) {
        LicenseStatus.LICENSED -> Strings.Trial.STATUS_LICENSED
        LicenseStatus.TRIAL    -> Strings.Trial.STATUS_ACTIVE
        LicenseStatus.EXPIRED  -> Strings.Trial.STATUS_EXPIRED
        LicenseStatus.UNKNOWN  -> Strings.Trial.STATUS_ACTIVE
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
            isExpired = licenseStatus() == LicenseStatus.EXPIRED,
            isInWarningPeriod = remaining in 1..5,
            warningDismissed = settings.trialWarningDismissed,
        )
    }
}
