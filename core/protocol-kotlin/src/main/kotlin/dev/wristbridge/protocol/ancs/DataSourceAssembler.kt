package dev.wristbridge.protocol.ancs

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** What the single active Control Point command expects back on Data Source. */
sealed interface ExpectedResponse {
    val attributeIds: List<Int>

    data class Notification(val uid: AncsUid, override val attributeIds: List<Int>) : ExpectedResponse

    data class App(val appIdentifier: String, override val attributeIds: List<Int>) : ExpectedResponse

    companion object {
        fun notification(uid: AncsUid, requests: List<AttributeRequest>) =
            Notification(uid, requests.map { it.id.raw })

        fun app(appIdentifier: String, attributes: List<AppAttributeId>) =
            App(appIdentifier, attributes.map { it.raw })
    }
}

/** Attribute bytes are decoded only once complete, so split multibyte sequences are safe (U07). */
class AttributeValue(val id: Int, val bytes: ByteArray) {
    val text: String
    val malformedUtf8: Boolean

    init {
        var malformed = false
        text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            malformed = true
            String(bytes, Charsets.UTF_8)
        }
        malformedUtf8 = malformed
    }

    override fun toString(): String = "AttributeValue(id=$id, bytes=${bytes.size}, malformed=$malformedUtf8)"
}

sealed interface AncsResponse {
    val attributes: Map<Int, AttributeValue>

    data class Notification(val uid: AncsUid, override val attributes: Map<Int, AttributeValue>) : AncsResponse

    data class App(val appIdentifier: String, override val attributes: Map<Int, AttributeValue>) : AncsResponse
}

sealed interface AssemblyFailure {
    /** Data arrived without an active command. */
    data object Unsolicited : AssemblyFailure
    data class CommandMismatch(val expected: Int, val actual: Int) : AssemblyFailure
    data class UidMismatch(val expected: AncsUid, val actual: AncsUid) : AssemblyFailure
    data class AppMismatch(val expected: String, val actualBytes: Int) : AssemblyFailure
    data class UnexpectedAttribute(val id: Int) : AssemblyFailure
    data class ResponseTooLarge(val limit: Int) : AssemblyFailure
}

sealed interface AssemblyResult {
    data object NeedMore : AssemblyResult

    /** [trailingBytes] counts bytes after the last expected attribute in the same chunk. */
    data class Complete(val response: AncsResponse, val trailingBytes: Int) : AssemblyResult

    data class Failed(val failure: AssemblyFailure) : AssemblyResult
}

/**
 * Incremental Data Source parser. ANCS responses carry no total length; a response is complete when
 * every requested attribute has arrived. Lengths are read only after both bytes exist (U06), memory
 * grows with received bytes, never with a remote length (U09), and [reset] drops all state (U12).
 * After a failure the assembler is idle until [begin] is called again.
 */
class DataSourceAssembler(private val maxResponseBytes: Int = 16 * 1024) {
    private enum class State { IDLE, COMMAND, UID, APP_ID, ATTR_ID, ATTR_LEN, ATTR_VALUE }

    private var expected: ExpectedResponse? = null
    private var state = State.IDLE
    private var total = 0
    private val scratch = ByteArrayOutputStream()
    private var scratchCount = 0
    private var attrId = 0
    private var attrLen = 0
    private var value = ByteArrayOutputStream()
    private val received = LinkedHashMap<Int, AttributeValue>()
    private var uid: AncsUid? = null
    private var appId: String? = null

    val isActive: Boolean get() = expected != null

    fun begin(expected: ExpectedResponse) {
        require(expected.attributeIds.isNotEmpty()) { "no attributes expected" }
        require(expected.attributeIds.toSet().size == expected.attributeIds.size) { "duplicate attribute" }
        reset()
        this.expected = expected
        state = State.COMMAND
    }

    fun reset() {
        expected = null
        state = State.IDLE
        total = 0
        scratch.reset()
        scratchCount = 0
        attrId = 0
        attrLen = 0
        value = ByteArrayOutputStream()
        received.clear()
        uid = null
        appId = null
    }

    fun feed(chunk: ByteArray): AssemblyResult {
        val exp = expected ?: return AssemblyResult.Failed(AssemblyFailure.Unsolicited)
        var i = 0
        while (i < chunk.size) {
            if (++total > maxResponseBytes) return fail(AssemblyFailure.ResponseTooLarge(maxResponseBytes))
            val b = chunk[i++].u8()
            when (state) {
                State.IDLE -> return fail(AssemblyFailure.Unsolicited)
                State.COMMAND -> {
                    val want = when (exp) {
                        is ExpectedResponse.Notification -> CommandId.GET_NOTIFICATION_ATTRIBUTES.raw
                        is ExpectedResponse.App -> CommandId.GET_APP_ATTRIBUTES.raw
                    }
                    if (b != want) return fail(AssemblyFailure.CommandMismatch(want, b))
                    state = if (exp is ExpectedResponse.Notification) State.UID else State.APP_ID
                }
                State.UID -> {
                    scratch.write(b)
                    if (++scratchCount == 4) {
                        val actual = AncsUid(readUInt32Le(scratch.toByteArray(), 0))
                        exp as ExpectedResponse.Notification
                        if (actual != exp.uid) return fail(AssemblyFailure.UidMismatch(exp.uid, actual))
                        uid = actual
                        clearScratch()
                        state = State.ATTR_ID
                    }
                }
                State.APP_ID -> {
                    exp as ExpectedResponse.App
                    if (b == 0) {
                        val actual = scratch.toByteArray()
                        if (!actual.contentEquals(exp.appIdentifier.toByteArray(Charsets.UTF_8))) {
                            return fail(AssemblyFailure.AppMismatch(exp.appIdentifier, actual.size))
                        }
                        appId = exp.appIdentifier
                        clearScratch()
                        state = State.ATTR_ID
                    } else {
                        scratch.write(b)
                        scratchCount++
                    }
                }
                State.ATTR_ID -> {
                    if (b !in exp.attributeIds || received.containsKey(b)) {
                        return fail(AssemblyFailure.UnexpectedAttribute(b))
                    }
                    attrId = b
                    state = State.ATTR_LEN
                }
                State.ATTR_LEN -> {
                    scratch.write(b)
                    if (++scratchCount == 2) {
                        val raw = scratch.toByteArray()
                        attrLen = raw[0].u8() or (raw[1].u8() shl 8)
                        clearScratch()
                        value = ByteArrayOutputStream()
                        if (attrLen == 0) {
                            if (finishAttribute()) return complete(exp, chunk.size - i)
                        } else {
                            state = State.ATTR_VALUE
                        }
                    }
                }
                State.ATTR_VALUE -> {
                    value.write(b)
                    if (value.size() == attrLen && finishAttribute()) return complete(exp, chunk.size - i)
                }
            }
        }
        return AssemblyResult.NeedMore
    }

    /** Returns true when the last expected attribute was stored. */
    private fun finishAttribute(): Boolean {
        received[attrId] = AttributeValue(attrId, value.toByteArray())
        state = State.ATTR_ID
        return received.size == expected!!.attributeIds.size
    }

    private fun complete(exp: ExpectedResponse, trailing: Int): AssemblyResult {
        val attrs = LinkedHashMap(received)
        val response = when (exp) {
            is ExpectedResponse.Notification -> AncsResponse.Notification(uid!!, attrs)
            is ExpectedResponse.App -> AncsResponse.App(appId!!, attrs)
        }
        reset()
        return AssemblyResult.Complete(response, trailing)
    }

    private fun fail(f: AssemblyFailure): AssemblyResult {
        reset()
        return AssemblyResult.Failed(f)
    }

    private fun clearScratch() {
        scratch.reset()
        scratchCount = 0
    }
}
