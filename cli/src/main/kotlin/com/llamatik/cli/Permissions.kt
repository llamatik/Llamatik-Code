package com.llamatik.cli

import com.llamatik.agent.core.host.Decision
import com.llamatik.agent.core.host.PermissionKind
import com.llamatik.agent.core.host.PermissionPrompt
import com.llamatik.agent.core.host.PermissionRequest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Asks on the terminal: `y` once, `a` always for this kind of action, anything else (including end of
 * input, e.g. when piped) declines. With auto-approve on nothing is asked.
 */
class TerminalPermissionPrompt(private val terminal: Terminal, autoApprove: Boolean = false) : PermissionPrompt {
    private val auto = AtomicBoolean(autoApprove)
    private val always = mutableSetOf<PermissionKind>()

    var autoApprove: Boolean
        get() = auto.get()
        set(value) = auto.set(value)

    override fun confirm(request: PermissionRequest): Decision {
        if (auto.get() || request.kind in always) return Decision.ALLOW_ONCE
        if (request.detail.isNotBlank()) terminal.println(request.detail)
        val answer = terminal.readLine(terminal.style(Style.YELLOW, "${request.title} [y/N/a=always] ")) ?: return Decision.DENY
        return when (answer.trim().lowercase()) {
            "y", "yes" -> Decision.ALLOW_ONCE
            "a", "always" -> { always += request.kind; Decision.ALLOW_ALWAYS }
            else -> Decision.DENY
        }
    }
}
