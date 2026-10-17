package dev.wristbridge.assistant

/**
 * P4/P5 assistant work is gated behind G2/G3 (Doc/00 §Secuencia). Nothing in P1 may enable it.
 */
object AssistantGate {
    const val ENABLED: Boolean = false
    const val REQUIRED_GATE: String = "G3"
}
