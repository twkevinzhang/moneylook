package tw.kevinzhang.moneylook.ui.home

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class CredentialJsonCodecTest {
    private val codec = CredentialJsonCodec(Gson())

    @Test
    fun parsesExtensionOwnedFields() {
        val fields = codec.parseFields(
            """[{"key":"customerId","label":"身分證字號","type":"text","required":true,"summary":true},{"key":"password","label":"密碼","type":"password","required":true,"summary":false}]""",
        )

        assertEquals(listOf("customerId", "password"), fields.map { it.key })
        assertFalse(fields.first().isPassword)
        assertEquals(true, fields.last().isPassword)
    }

    @Test
    fun invalidFieldsFallBackWithoutMakingPasswordSummarizable() {
        val fields = codec.parseFields(
            """[{"key":"password","label":"密碼","type":"password","required":true,"summary":true}]""",
        )

        assertEquals(listOf("username", "password"), fields.map { it.key })
        assertFalse(fields.last().summary)
    }

    @Test
    fun credentialMustContainOnlyFlatStringValues() {
        assertEquals(
            mapOf("customerId" to "A123", "password" to "secret"),
            codec.parseCredential("""{"customerId":"A123","password":"secret"}"""),
        )
        assertNull(codec.parseCredential("""{"remember":true}"""))
        assertNull(codec.parseCredential("""{"nested":{"value":"x"}}"""))
    }

    @Test
    fun blankPasswordRetainsSameExistingKeyAndDropsUnknownLegacyKeys() {
        val fields = codec.parseFields(
            """[{"key":"customerId","label":"身分證字號","type":"text","required":true,"summary":true},{"key":"password","label":"密碼","type":"password","required":true,"summary":false}]""",
        )

        val resolved = codec.resolveForSave(
            fields = fields,
            submittedValues = mapOf("customerId" to "  A123  ", "password" to ""),
            existingValues = mapOf("username" to "legacy", "password" to "kept-secret"),
        )

        assertEquals(mapOf("customerId" to "A123", "password" to "kept-secret"), resolved.values)
        assertNull(resolved.missingRequiredField)
    }

    @Test
    fun requiredFieldIsReportedAfterResolution() {
        val fields = codec.parseFields(
            """[{"key":"userId","label":"使用者代號","type":"text","required":true,"summary":true}]""",
        )

        val resolved = codec.resolveForSave(fields, mapOf("userId" to "  "), emptyMap())

        assertEquals("userId", resolved.missingRequiredField?.key)
    }
}
