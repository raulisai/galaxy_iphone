package dev.wristbridge.assistant

import kotlin.test.Test
import kotlin.test.assertFalse

class AssistantGateTest {
    @Test
    fun assistantStaysDisabledInP1() {
        assertFalse(AssistantGate.ENABLED)
    }
}
