package com.llamatik.teammate.codehealth

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.llamatik.teammate.licensing.LicenseChecker
import com.llamatik.teammate.settings.LlamatikAppSettings

@Service(Service.Level.APP)
class CodeHealthBudgetService {

    fun isEnabled(): Boolean = runCatching {
        service<LlamatikAppSettings>().data().codeHealthEnabled
    }.getOrElse { false }

    fun currentMode(): AnalysisMode {
        val raw = runCatching { service<LlamatikAppSettings>().data().codeHealthMode }.getOrElse { "OFF" }
        return runCatching { AnalysisMode.valueOf(raw) }.getOrElse { AnalysisMode.OFF }
    }

    fun isPro(): Boolean = runCatching { LicenseChecker.isLicensed() == true }.getOrElse { false }

    fun effectiveMode(): AnalysisMode {
        if (!isEnabled()) return AnalysisMode.OFF
        val mode = currentMode()
        if (!isPro() && mode !in listOf(AnalysisMode.OFF, AnalysisMode.LIGHT)) return AnalysisMode.LIGHT
        return mode
    }

    fun maxFiles(): Int = CodeHealthBudgets.maxFiles(effectiveMode(), isPro())
    fun maxRuntimeMs(): Long = CodeHealthBudgets.maxRuntimeMs(effectiveMode(), isPro())
    fun maxModelCallsPerHour(): Int = CodeHealthBudgets.maxModelCallsPerHour(effectiveMode(), isPro())
    fun maxInsightsPerFile(): Int = CodeHealthBudgets.maxInsightsPerFile(effectiveMode(), isPro())

    fun canRunInBackground(): Boolean =
        isPro() && effectiveMode() in listOf(AnalysisMode.BALANCED, AnalysisMode.AGGRESSIVE)

    fun canRunOnSave(): Boolean = isEnabled() && effectiveMode() != AnalysisMode.OFF
    fun canRunIdle(): Boolean = canRunInBackground()

    companion object {
        @JvmStatic
        fun getInstance(): CodeHealthBudgetService = service()
    }
}
