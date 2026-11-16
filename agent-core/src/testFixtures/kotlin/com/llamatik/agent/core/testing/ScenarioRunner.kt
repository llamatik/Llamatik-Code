package com.llamatik.agent.core.testing

import com.llamatik.agent.core.agent.AgentLoop
import com.llamatik.agent.core.agent.ToolPolicy

/** Runs a scenario through some agent implementation and returns its transcript. */
fun interface ScenarioRunner {
    fun run(world: AgentWorld)

    fun transcript(scenario: AgentScenario): String {
        val world = AgentWorld(scenario)
        run(world)
        return world.transcript()
    }
}

/** The extracted [AgentLoop]. */
fun agentLoopRunner(policy: ToolPolicy = ToolPolicy.PORTABLE) = ScenarioRunner { w ->
    val loop = AgentLoop(w.backend, w.env, w.tools, policy, w.sources)
    val s = w.scenario
    if (s.chat) {
        loop.chat(w.session, s.userPrompt, w.config, w::onToken, w::onChatFinished, w::onError)
    } else {
        loop.submit(
            session = w.session, userPrompt = s.userPrompt, config = w.config,
            onToken = w::onToken, onThinkingToken = w::onThinking,
            onFinished = w::onFinished, onError = w::onError,
            onTokenCount = w::onTokenCount, onTrace = w::onTrace,
        )
    }
}
