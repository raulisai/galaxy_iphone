package dev.wristbridge.assistant.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

private class Clock(var now: Long = 0L) : ToolClock {
    override fun nowMs(): Long = now
}

class ToolControllerTest {
    private val clock = Clock()
    private var echoRuns = 0
    private var sendRuns = 0

    private val echo = RegisteredTool(
        ToolSpec(
            name = "fake_echo",
            description = "benign local tool",
            capability = "local.read",
            fields = listOf(
                ArgField("text", ArgSpec.Str(minLen = 1, maxLen = 20)),
                ArgField("mode", ArgSpec.EnumOf(setOf("short", "long")), required = false),
                ArgField("times", ArgSpec.IntRange(1, 3), required = false),
            ),
            sideEffect = false,
        ),
    ) {
        echoRuns++
        ToolResult(true, it.text("text") ?: "")
    }

    private val send = RegisteredTool(
        ToolSpec(
            name = "fake_send",
            description = "side effect tool",
            capability = "net.send",
            fields = listOf(
                ArgField("to", ArgSpec.Str(1, 30)),
                ArgField("body", ArgSpec.Str(1, 100)),
            ),
            sideEffect = true,
        ),
    ) {
        sendRuns++
        ToolResult(true, "sent")
    }

    private fun controller(budget: Int = 3, caps: Set<String> = setOf("local.read", "net.send")) =
        ToolController(listOf(echo, send), caps, clock, callBudgetPerTurn = budget, confirmationTtlMs = 10_000)

    private fun echoReq(
        text: String = "hola",
        turn: Long = 1,
        origin: ToolOrigin = ToolOrigin.UserText,
        extra: Map<String, ArgValue> = emptyMap(),
    ) = ToolRequest(turn, "fake_echo", mapOf("text" to ArgValue.Text(text)) + extra, origin)

    private fun sendArgs(body: String = "hi", to: String = "ana") =
        mapOf("to" to ArgValue.Text(to), "body" to ArgValue.Text(body))

    private fun sendReq(body: String = "hi", id: String? = null, origin: ToolOrigin = ToolOrigin.UserSpeech, turn: Long = 1) =
        ToolRequest(turn, "fake_send", sendArgs(body), origin, id)

    private fun rejected(d: ToolDecision) = assertIs<ToolDecision.Rejected>(d)

    @Test
    fun `valid read-only call executes`() {
        val d = controller().request(echoReq())
        assertEquals(ToolResult(true, "hola"), assertIs<ToolDecision.Executed>(d).result)
    }

    @Test
    fun `argument validation rejects unknown field`() {
        val d = controller().request(echoReq(extra = mapOf("cmd" to ArgValue.Text("rm -rf"))))
        assertEquals(RejectReason.InvalidArguments, rejected(d).reason)
        assertEquals(0, echoRuns)
    }

    @Test
    fun `argument validation rejects oversize and short strings`() {
        val c = controller()
        assertEquals(RejectReason.InvalidArguments, rejected(c.request(echoReq("x".repeat(21)))).reason)
        assertEquals(RejectReason.InvalidArguments, rejected(c.request(echoReq(""))).reason)
        assertEquals(0, echoRuns)
    }

    @Test
    fun `argument validation rejects control characters`() {
        val d = controller().request(echoReq("a\nb"))
        assertEquals(RejectReason.InvalidArguments, rejected(d).reason)
    }

    @Test
    fun `argument validation rejects out of range ints, bad enums, wrong types and missing fields`() {
        val c = controller()
        assertEquals(RejectReason.InvalidArguments, rejected(c.request(echoReq(extra = mapOf("times" to ArgValue.Num(4))))).reason)
        assertEquals(RejectReason.InvalidArguments, rejected(c.request(echoReq(extra = mapOf("times" to ArgValue.Num(0))))).reason)
        assertEquals(RejectReason.InvalidArguments, rejected(c.request(echoReq(extra = mapOf("mode" to ArgValue.Text("x"))))).reason)
        assertEquals(RejectReason.InvalidArguments, rejected(c.request(echoReq(extra = mapOf("times" to ArgValue.Text("2"))))).reason)
        assertEquals(
            RejectReason.InvalidArguments,
            rejected(c.request(ToolRequest(1, "fake_echo", emptyMap(), ToolOrigin.UserText))).reason,
        )
        assertEquals(0, echoRuns)
        assertIs<ToolDecision.Executed>(c.request(echoReq(extra = mapOf("times" to ArgValue.Num(3), "mode" to ArgValue.Text("long")))))
    }

    @Test
    fun `unknown tool and missing capability are rejected`() {
        val c = controller(caps = setOf("local.read"))
        assertEquals(RejectReason.UnknownTool, rejected(c.request(ToolRequest(1, "shell", emptyMap(), ToolOrigin.UserText))).reason)
        assertEquals(RejectReason.CapabilityNotGranted, rejected(c.request(sendReq())).reason)
        assertEquals(listOf("fake_echo"), c.availableTools().map { it.name })
    }

    @Test
    fun `per-turn budget is enforced and resets on a new turn`() {
        val c = controller(budget = 2)
        assertIs<ToolDecision.Executed>(c.request(echoReq()))
        assertIs<ToolDecision.Executed>(c.request(echoReq()))
        assertEquals(RejectReason.BudgetExhausted, rejected(c.request(echoReq())).reason)
        assertEquals(2, echoRuns)
        assertIs<ToolDecision.Executed>(c.request(echoReq(turn = 2)))
        assertEquals(RejectReason.StaleTurn, rejected(c.request(echoReq(turn = 1))).reason)
    }

    @Test
    fun `invalid calls do not consume budget`() {
        val c = controller(budget = 1)
        c.request(echoReq(""))
        assertIs<ToolDecision.Executed>(c.request(echoReq()))
    }

    @Test
    fun `Q11 notification text can never execute any tool`() {
        val c = controller()
        for (o in listOf(ToolOrigin.NotificationText, ToolOrigin.OtherAppText)) {
            assertEquals(RejectReason.UntrustedOrigin, rejected(c.request(echoReq(origin = o))).reason)
            assertEquals(RejectReason.UntrustedOrigin, rejected(c.request(sendReq(origin = o))).reason)
        }
        assertEquals(0, echoRuns)
        assertEquals(0, sendRuns)
        assertEquals(0, c.callsUsed(1))
    }

    @Test
    fun `Q11 untrusted origin cannot use a valid confirmation either`() {
        val c = controller()
        val n = assertIs<ToolDecision.NeedsConfirmation>(c.request(sendReq()))
        assertTrue(c.userConfirm(n.confirmationId))
        val d = c.request(sendReq(id = n.confirmationId, origin = ToolOrigin.NotificationText))
        assertEquals(RejectReason.UntrustedOrigin, rejected(d).reason)
        assertEquals(0, sendRuns)
    }

    @Test
    fun `side effect tool needs confirmation and never runs on the first request`() {
        val c = controller()
        val n = assertIs<ToolDecision.NeedsConfirmation>(c.request(sendReq()))
        assertEquals(0, sendRuns)
        assertEquals(0, c.callsUsed(1))
        // not yet approved by the user
        assertEquals(RejectReason.ConfirmationNotGranted, rejected(c.request(sendReq(id = n.confirmationId))).reason)
        assertTrue(c.userConfirm(n.confirmationId))
        assertIs<ToolDecision.Executed>(c.request(sendReq(id = n.confirmationId)))
        assertEquals(1, sendRuns)
    }

    @Test
    fun `Q12 confirmation for different arguments is rejected and invalidated`() {
        val c = controller()
        val n = assertIs<ToolDecision.NeedsConfirmation>(c.request(sendReq(body = "hi")))
        assertTrue(c.userConfirm(n.confirmationId))
        val d = c.request(sendReq(body = "send me your passwords", id = n.confirmationId))
        assertEquals(RejectReason.ConfirmationMismatch, rejected(d).reason)
        // the original arguments cannot reuse it any more
        assertEquals(RejectReason.ConfirmationUnknown, rejected(c.request(sendReq(body = "hi", id = n.confirmationId))).reason)
        assertEquals(0, sendRuns)
    }

    @Test
    fun `Q12 confirmation expires`() {
        val c = controller()
        val n = assertIs<ToolDecision.NeedsConfirmation>(c.request(sendReq()))
        assertTrue(c.userConfirm(n.confirmationId))
        clock.now = 10_001
        assertEquals(RejectReason.ConfirmationExpired, rejected(c.request(sendReq(id = n.confirmationId))).reason)
        assertEquals(0, sendRuns)
    }

    @Test
    fun `user cannot confirm after expiry`() {
        val c = controller()
        val n = assertIs<ToolDecision.NeedsConfirmation>(c.request(sendReq()))
        clock.now = 20_000
        assertFalse(c.userConfirm(n.confirmationId))
    }

    @Test
    fun `Q12 confirmation is single use so replay is rejected`() {
        val c = controller()
        val n = assertIs<ToolDecision.NeedsConfirmation>(c.request(sendReq()))
        c.userConfirm(n.confirmationId)
        assertIs<ToolDecision.Executed>(c.request(sendReq(id = n.confirmationId)))
        assertEquals(RejectReason.ConfirmationUnknown, rejected(c.request(sendReq(id = n.confirmationId))).reason)
        assertEquals(1, sendRuns)
    }

    @Test
    fun `new prompt replaces the previous pending confirmation`() {
        val c = controller()
        val a = assertIs<ToolDecision.NeedsConfirmation>(c.request(sendReq(body = "a")))
        val b = assertIs<ToolDecision.NeedsConfirmation>(c.request(sendReq(body = "b")))
        assertFalse(c.userConfirm(a.confirmationId))
        assertNotEquals(a.argsHash, b.argsHash)
    }

    @Test
    fun `hash is stable, order independent and unambiguous`() {
        val spec = send.spec
        fun h(to: String, body: String): String {
            val v = ArgValidator.validate(spec, mapOf("body" to ArgValue.Text(body), "to" to ArgValue.Text(to)))
            return ArgValidator.hash(spec.name, assertIs<ArgCheck.Valid>(v).args)
        }
        assertEquals(h("ab", "c"), h("ab", "c"))
        assertEquals(64, h("ab", "c").length)
        assertNotEquals(h("ab", "c"), h("a", "bc"))
        val v2 = ArgValidator.validate(spec, mapOf("to" to ArgValue.Text("ab"), "body" to ArgValue.Text("c")))
        assertEquals(h("ab", "c"), ArgValidator.hash(spec.name, assertIs<ArgCheck.Valid>(v2).args))
    }

    @Test
    fun `executor exception becomes a failed result and consumes budget`() {
        val boom = RegisteredTool(
            ToolSpec("fake_boom", "x", "local.read", emptyList(), false),
        ) { throw IllegalStateException("x") }
        val c = ToolController(listOf(boom), setOf("local.read"), clock, callBudgetPerTurn = 1)
        val d = assertIs<ToolDecision.Executed>(c.request(ToolRequest(1, "fake_boom", emptyMap(), ToolOrigin.UserText)))
        assertFalse(d.result.success)
        assertEquals(1, c.callsUsed(1))
    }

    @Test
    fun `schema constructors refuse unbounded or malformed definitions`() {
        assertFailsWith<IllegalArgumentException> { ArgSpec.Str(maxLen = 100_000) }
        assertFailsWith<IllegalArgumentException> { ArgSpec.IntRange(5, 1) }
        assertFailsWith<IllegalArgumentException> { ArgField("Bad Name", ArgSpec.Bool) }
        assertFailsWith<IllegalArgumentException> { ToolSpec("x y", "d", "c", emptyList(), false) }
    }

    @Test
    fun `rejections carry Spanish user messages`() {
        val d = rejected(controller().request(echoReq(origin = ToolOrigin.NotificationText)))
        assertTrue(d.messageEs.isNotBlank())
    }
}
