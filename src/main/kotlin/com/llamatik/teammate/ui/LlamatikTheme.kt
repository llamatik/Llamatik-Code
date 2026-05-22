package com.llamatik.teammate.ui

import com.intellij.ui.JBColor
import java.awt.Color

/**
 * Single source of truth for all UI colors in the Llamatik plugin.
 * Mirrors the Llamatik dark/light theme palette defined in the mobile app.
 */
object LlamatikTheme {

    // ── Backgrounds ───────────────────────────────────────────────────────────

    /** Page / root background. darkBackground = #0F0F10, lightBackground = #FFFBF5 */
    val background = JBColor(Color(0xFFFBF5), Color(0x0F0F10))

    /** Card / panel surface. darkSurfaceVariant = #1C1C1F, lightSurface = #FFFBF5 */
    val surface = JBColor(Color(0xFFFBF5), Color(0x1C1C1F))

    /** Elevated / secondary surface. darkSecondaryContainer = #1F1F1F, lightSecondaryContainer = #FFF3E0 */
    val surfaceVariant = JBColor(Color(0xFFF3E0), Color(0x1F1F1F))

    /** Info / activity card. darkTertiaryContainer = #262626, lightTertiaryContainer = #D7CCC8 */
    val tertiaryContainer = JBColor(Color(0xD7CCC8), Color(0x262626))

    /** User chat bubble. darkPrimaryContainer = #2A2116, lightPrimaryContainer = #FFE0B2 */
    val primaryContainer = JBColor(Color(0xFFE0B2), Color(0x2A2116))

    // ── Borders / dividers ────────────────────────────────────────────────────

    /** Panel stroke / outline. darkOutline = #35353A, lightOutline = #8D6E63 */
    val outline = JBColor(Color(0x8D6E63), Color(0x35353A))

    /** Header divider line. */
    val divider = JBColor(Color(0xE8DDD0), Color(0x222630))

    /** Tertiary container border. */
    val tertiaryBorder = JBColor(Color(0xD7CCC8), Color(0x35353A))

    // ── Accent / primary ──────────────────────────────────────────────────────

    /** Primary accent (Llamatik orange). darkPrimary = #FFB74D, lightPrimary = #F59E0B */
    val primary = JBColor(Color(0xF59E0B), Color(0xFFB74D))

    /** On-primary text (dark text on orange background). darkOnPrimary = #1A1A1A, lightOnPrimary = #3B1D07 */
    val onPrimary = JBColor(Color(0x3B1D07), Color(0x1A1A1A))

    /** Secondary accent (burnt amber). lightSecondary = #D97706, darkSecondary = #BDBDBD */
    val secondary = JBColor(Color(0xD97706), Color(0xFFB74D))

    // ── Text ──────────────────────────────────────────────────────────────────

    /** Primary body text. darkOnSurface = #EDEDED, lightOnSurface = #261C0C */
    val onSurface = JBColor(Color(0x261C0C), Color(0xEDEDED))

    /** Muted / secondary text. darkOnSurfaceVariant = #D6D6D6, lightOnSurfaceVariant = #5E4636 */
    val onSurfaceVariant = JBColor(Color(0x5E4636), Color(0xD6D6D6))

    // ── Status ────────────────────────────────────────────────────────────────

    /** Success green. successContainer = #388E3C */
    val success = JBColor(Color(0x388E3C), Color(0x388E3C))

    /** Error text color. */
    val error = JBColor(Color(0xC62828), Color(0xFF5252))

    /** Error / banner background. */
    val errorBackground = JBColor(Color(0xFFE0E0), Color(0x5C2626))

    /** Warning / trial banner background. */
    val warningBackground = JBColor(Color(0xFFF3CD), Color(0x3C2C00))

    /** Warning border. */
    val warningBorder = JBColor(Color(0xF59E0B), Color(0x7A5A00))

    // ── Status badges (model download states) ────────────────────────────────

    val statusBlue = JBColor(Color(0x007AFF), Color(0x4DA3FF))
    val statusGreen = JBColor(Color(0x34C759), Color(0x30D158))
    val statusOrange = JBColor(Color(0xFF9500), Color(0xFFAA00))

    // ── Code block colors ─────────────────────────────────────────────────────

    /** Code block body background. darkCode = #1A1A1D, lightCode = #F5F0E8 */
    val codeBackground = JBColor(Color(0xF5F0E8), Color(0x1A1A1D))

    /** Code block header bar. darkCodeHeader = #252528, lightCodeHeader = #EDE8E0 */
    val codeHeaderBackground = JBColor(Color(0xEDE8E0), Color(0x252528))

    /** Inline code chip background. darkInlineCode = #2A2A2E, lightInlineCode = #EDE8E0 */
    val inlineCodeBackground = JBColor(Color(0xEDE8E0), Color(0x2A2A2E))

    // ── HTML color strings (for use inside <span style='color:...'>) ──────────
    // Derived from the JBColor instances so they always match the resolved theme color,
    // regardless of which light/dark theme the user has active.

    /** Primary accent hex string for HTML. */
    val htmlPrimary get() = toHex(primary)

    /** Body text hex string for HTML. */
    val htmlOnSurface get() = toHex(onSurface)

    /** Muted text hex string for HTML. */
    val htmlOnSurfaceVariant get() = toHex(onSurfaceVariant)

    /** On-primary (dark text on orange) hex string for HTML. */
    val htmlOnPrimary get() = toHex(onPrimary)

    /** Code block background hex string for HTML. */
    val htmlCodeBackground get() = toHex(codeBackground)

    /** Code block header background hex string for HTML. */
    val htmlCodeHeaderBackground get() = toHex(codeHeaderBackground)

    /** Inline code background hex string for HTML. */
    val htmlInlineCodeBackground get() = toHex(inlineCodeBackground)

    private fun toHex(c: java.awt.Color) = "#%02X%02X%02X".format(c.red, c.green, c.blue)
}
