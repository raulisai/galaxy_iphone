package dev.wristbridge.assistant.tools

import java.security.MessageDigest

/** Injected clock (milliseconds) for confirmation expiry. */
fun interface ToolClock {
    fun nowMs(): Long
}

/**
 * Where a tool request came from. Text coming from notifications or other apps is DATA, never an
 * instruction (Q11): requests with an untrusted origin can never execute.
 */
enum class ToolOrigin(val trusted: Boolean) {
    UserSpeech(true),
    UserText(true),

    /** Planned by the model while serving a user turn; side-effect tools still need confirmation. */
    ModelPlan(true),
    NotificationText(false),
    OtherAppText(false),
}

/** Typed argument values; there is no free-form map of Any. */
sealed interface ArgValue {
    data class Text(val value: String) : ArgValue
    data class Num(val value: Long) : ArgValue
    data class Flag(val value: Boolean) : ArgValue
}

/** Argument schemas. Every one is bounded; there is no unbounded string and no shell-like type. */
sealed interface ArgSpec {
    /** Single-line text without control characters, length within [minLen, maxLen]. */
    data class Str(val minLen: Int = 0, val maxLen: Int) : ArgSpec {
        init {
            require(minLen >= 0 && maxLen in 1..MAX_STRING && minLen <= maxLen) { "invalid string bounds" }
        }

        companion object {
            const val MAX_STRING = 512
        }
    }

    data class EnumOf(val values: Set<String>) : ArgSpec {
        init {
            require(values.isNotEmpty() && values.size <= 32) { "enum must have 1..32 values" }
        }
    }

    data class IntRange(val min: Long, val max: Long) : ArgSpec {
        init {
            require(min <= max) { "empty range" }
        }
    }

    data object Bool : ArgSpec
}

data class ArgField(val name: String, val spec: ArgSpec, val required: Boolean = true) {
    init {
        require(NAME_REGEX.matches(name)) { "invalid field name" }
    }
}

private val NAME_REGEX = Regex("[a-z][a-z0-9_]{0,31}")

data class ToolSpec(
    val name: String,
    val description: String,
    /** Capability that must be granted for the tool to be callable. */
    val capability: String,
    val fields: List<ArgField>,
    /** True for tools with external/persistent effect: they need exact-argument confirmation. */
    val sideEffect: Boolean,
) {
    init {
        require(NAME_REGEX.matches(name)) { "invalid tool name" }
        require(fields.size <= 8) { "too many fields" }
        require(fields.map { it.name }.toSet().size == fields.size) { "duplicate field" }
    }
}

data class ToolResult(val success: Boolean, val message: String)

fun interface ToolExecutor {
    fun execute(args: ValidatedArgs): ToolResult
}

class RegisteredTool(val spec: ToolSpec, val executor: ToolExecutor)

/** Arguments that passed schema validation; fields are exactly those declared by the spec. */
class ValidatedArgs internal constructor(private val values: Map<String, ArgValue>) {
    fun text(name: String): String? = (values[name] as? ArgValue.Text)?.value
    fun num(name: String): Long? = (values[name] as? ArgValue.Num)?.value
    fun flag(name: String): Boolean? = (values[name] as? ArgValue.Flag)?.value
    fun asMap(): Map<String, ArgValue> = values
}

class ToolRequest(
    val turnId: Long,
    val toolName: String,
    val args: Map<String, ArgValue>,
    val origin: ToolOrigin,
    /** Confirmation id previously issued by [ToolController]; only meaningful for side-effect tools. */
    val confirmationId: String? = null,
)

enum class RejectReason {
    UntrustedOrigin,
    UnknownTool,
    CapabilityNotGranted,
    InvalidArguments,
    BudgetExhausted,
    StaleTurn,
    ConfirmationUnknown,
    ConfirmationNotGranted,
    ConfirmationExpired,
    ConfirmationMismatch,
}

sealed interface ToolDecision {
    data class Executed(val result: ToolResult) : ToolDecision

    /** The user must approve this exact action (shown via [promptEs]) through [ToolController.userConfirm]. */
    data class NeedsConfirmation(
        val confirmationId: String,
        val argsHash: String,
        val expiresAtMs: Long,
        val promptEs: String,
    ) : ToolDecision

    data class Rejected(val reason: RejectReason, val detail: String) : ToolDecision {
        /** User-facing message in Spanish. */
        val messageEs: String
            get() = when (reason) {
                RejectReason.UntrustedOrigin -> "Un aviso o app no puede ordenar acciones."
                RejectReason.UnknownTool -> "Herramienta no disponible."
                RejectReason.CapabilityNotGranted -> "No tienes permiso para esta acción."
                RejectReason.InvalidArguments -> "Datos de la acción no válidos."
                RejectReason.BudgetExhausted -> "Límite de acciones alcanzado en este turno."
                RejectReason.StaleTurn -> "Turno anterior; acción ignorada."
                RejectReason.ConfirmationUnknown -> "Confirmación no encontrada o ya usada."
                RejectReason.ConfirmationNotGranted -> "Falta tu confirmación."
                RejectReason.ConfirmationExpired -> "La confirmación caducó. Vuelve a pedirla."
                RejectReason.ConfirmationMismatch -> "La acción cambió; se requiere nueva confirmación."
            }
    }
}

sealed interface ArgCheck {
    data class Valid(val args: ValidatedArgs) : ArgCheck
    data class Invalid(val error: String) : ArgCheck
}

object ArgValidator {
    fun validate(spec: ToolSpec, raw: Map<String, ArgValue>): ArgCheck {
        val byName = spec.fields.associateBy { it.name }
        for (key in raw.keys) {
            if (key !in byName) return ArgCheck.Invalid("unknown_field:" + key.take(32))
        }
        for (f in spec.fields) {
            val v = raw[f.name]
            if (v == null) {
                if (f.required) return ArgCheck.Invalid("missing:" + f.name)
                continue
            }
            val err = check(f, v)
            if (err != null) return ArgCheck.Invalid(err)
        }
        return ArgCheck.Valid(ValidatedArgs(raw.toMap()))
    }

    private fun check(f: ArgField, v: ArgValue): String? = when (val s = f.spec) {
        is ArgSpec.Str -> {
            if (v !is ArgValue.Text) {
                "type:" + f.name
            } else if (v.value.length > s.maxLen) {
                "too_long:" + f.name
            } else if (v.value.length < s.minLen) {
                "too_short:" + f.name
            } else if (v.value.any { it.isISOControl() }) {
                "control_chars:" + f.name
            } else {
                null
            }
        }
        is ArgSpec.EnumOf -> when {
            v !is ArgValue.Text -> "type:" + f.name
            v.value !in s.values -> "not_in_enum:" + f.name
            else -> null
        }
        is ArgSpec.IntRange -> when {
            v !is ArgValue.Num -> "type:" + f.name
            v.value < s.min || v.value > s.max -> "out_of_range:" + f.name
            else -> null
        }
        ArgSpec.Bool -> if (v is ArgValue.Flag) null else "type:" + f.name
    }

    /**
     * Unambiguous canonical form (sorted names, type tags, length-prefixed text) hashed with
     * SHA-256 together with the tool name. Any change to tool or arguments changes the hash.
     */
    fun hash(toolName: String, args: ValidatedArgs): String {
        val sb = StringBuilder()
        sb.append(toolName.length).append(':').append(toolName)
        for ((name, v) in args.asMap().toSortedMap()) {
            sb.append('|').append(name.length).append(':').append(name).append('=')
            when (v) {
                is ArgValue.Text -> sb.append('s').append(v.value.length).append(':').append(v.value)
                is ArgValue.Num -> sb.append('n').append(v.value)
                is ArgValue.Flag -> sb.append('b').append(if (v.value) '1' else '0')
            }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(sb.toString().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

/**
 * Tool registry + guards (P5-06). No real tools live here; executors are supplied by the caller.
 * Order of checks: origin (Q11) -> tool known -> capability -> turn -> argument schema -> budget ->
 * confirmation bound to the argument hash (Q12) -> execute.
 *
 * [userConfirm] is the ONLY way to grant a confirmation and must be wired solely to a real user UI
 * action, never to model output. A single confirmation is pending at a time; a new prompt replaces
 * the previous one. Confirmations are single use and expire after [confirmationTtlMs].
 * Not thread-safe: use one dispatcher.
 */
class ToolController(
    tools: List<RegisteredTool>,
    private val grantedCapabilities: Set<String>,
    private val clock: ToolClock,
    private val callBudgetPerTurn: Int = 3,
    private val confirmationTtlMs: Long = 30_000L,
) {
    private class Pending(val id: String, val tool: String, val hash: String, val expiresAtMs: Long) {
        var granted = false
    }

    private val registry: Map<String, RegisteredTool> = tools.associateBy { it.spec.name }.also {
        require(it.size == tools.size) { "duplicate tool name" }
    }
    private var currentTurn = Long.MIN_VALUE
    private var callsThisTurn = 0
    private var pending: Pending? = null
    private var nextConfirmation = 1L

    init {
        require(callBudgetPerTurn > 0 && confirmationTtlMs > 0)
    }

    /** Capability list: specs of the tools that are registered AND granted. */
    fun availableTools(): List<ToolSpec> =
        registry.values.map { it.spec }.filter { it.capability in grantedCapabilities }.sortedBy { it.name }

    fun callsUsed(turnId: Long): Int = if (turnId == currentTurn) callsThisTurn else 0

    fun request(req: ToolRequest): ToolDecision {
        if (!req.origin.trusted) return reject(RejectReason.UntrustedOrigin, req.origin.name)
        val tool = registry[req.toolName] ?: return reject(RejectReason.UnknownTool, req.toolName.take(32))
        val spec = tool.spec
        if (spec.capability !in grantedCapabilities) return reject(RejectReason.CapabilityNotGranted, spec.capability)
        if (req.turnId < currentTurn) return reject(RejectReason.StaleTurn, req.turnId.toString())
        val args = when (val c = ArgValidator.validate(spec, req.args)) {
            is ArgCheck.Valid -> c.args
            is ArgCheck.Invalid -> return reject(RejectReason.InvalidArguments, c.error)
        }
        if (req.turnId != currentTurn) {
            currentTurn = req.turnId
            callsThisTurn = 0
        }
        if (callsThisTurn >= callBudgetPerTurn) return reject(RejectReason.BudgetExhausted, "budget=$callBudgetPerTurn")

        if (spec.sideEffect) {
            val hash = ArgValidator.hash(spec.name, args)
            val id = req.confirmationId
            if (id == null) {
                val now = clock.nowMs()
                val p = Pending("c" + nextConfirmation++, spec.name, hash, now + confirmationTtlMs)
                pending = p
                return ToolDecision.NeedsConfirmation(
                    p.id, hash, p.expiresAtMs,
                    "¿Confirmas ejecutar \"" + spec.name + "\" con estos datos exactos?",
                )
            }
            val p = pending
            if (p == null || p.id != id) return reject(RejectReason.ConfirmationUnknown, id.take(16))
            if (clock.nowMs() > p.expiresAtMs) {
                pending = null
                return reject(RejectReason.ConfirmationExpired, id)
            }
            if (p.hash != hash || p.tool != spec.name) {
                pending = null // changed arguments invalidate the confirmation for good
                return reject(RejectReason.ConfirmationMismatch, id)
            }
            if (!p.granted) return reject(RejectReason.ConfirmationNotGranted, id)
            pending = null // single use: replay is rejected
        }

        callsThisTurn++
        val result = try {
            tool.executor.execute(args)
        } catch (e: Exception) {
            ToolResult(false, "Error al ejecutar la acción.")
        }
        return ToolDecision.Executed(result)
    }

    /** Called from the user's explicit approval UI. Returns false if unknown or expired. */
    fun userConfirm(confirmationId: String): Boolean {
        val p = pending ?: return false
        if (p.id != confirmationId) return false
        if (clock.nowMs() > p.expiresAtMs) {
            pending = null
            return false
        }
        p.granted = true
        return true
    }

    /** User declined, or the session ended. */
    fun cancelPending() {
        pending = null
    }

    private fun reject(reason: RejectReason, detail: String): ToolDecision.Rejected =
        ToolDecision.Rejected(reason, detail)
}
