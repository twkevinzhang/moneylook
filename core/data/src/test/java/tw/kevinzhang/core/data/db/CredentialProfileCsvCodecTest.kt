package tw.kevinzhang.core.data.db

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kevinzhang.core.data.model.CredentialProfile

class CredentialProfileCsvCodecTest {
    @Test
    fun `dynamic fields plaintext values and schedule round trip`() {
        val fields = listOf(
            CredentialProfileCsvFieldDefinition("customerId", "身分證字號", "text", true),
            CredentialProfileCsvFieldDefinition("userCode", "使用者代號", "text", true),
            CredentialProfileCsvFieldDefinition("password", "網銀密碼", "password", true),
            CredentialProfileCsvFieldDefinition("memo", "備註", "text", false),
        )
        val source = CredentialProfileCsvExtension(
            extensionId = "tw.example.bank::https://github.com/example/bank",
            extensionName = "Example, \"Bank\"",
            profile = CredentialProfile(
                extensionId = "tw.example.bank::https://github.com/example/bank",
                credential =
                """{"customerId":"A123456789","userCode":"user","password":"p@ss,\n\"word\"","memo":"=1+1"}""",
                scheduleEnabled = true,
                scheduleCron = "15 8 * * 1",
                timezoneId = "Asia/Taipei",
            ),
            fields = fields,
        )

        val result = CredentialProfileCsvCodec.decode(CredentialProfileCsvCodec.encode(listOf(source)))

        assertTrue(result is CredentialProfileCsvDecodeResult.Success)
        val actual = (result as CredentialProfileCsvDecodeResult.Success).value.single()
        assertEquals(source.extensionId, actual.extensionId)
        assertEquals(source.extensionName, actual.extensionName)
        assertEquals(source.fields, actual.fields)
        assertEquals(source.profile.scheduleEnabled, actual.profile.scheduleEnabled)
        assertEquals(source.profile.scheduleCron, actual.profile.scheduleCron)
        assertEquals(source.profile.timezoneId, actual.profile.timezoneId)
        assertEquals(
            JsonParser.parseString(source.profile.credential),
            JsonParser.parseString(actual.profile.credential),
        )
    }

    @Test
    fun `multiple extensions retain field order independently`() {
        val first = extension("ext-a", "Bank A", listOf("username" to "alice", "password" to "secret"))
        val second = extension("ext-b", "Bank B", listOf("password" to "other", "username" to "bob"))

        val result = CredentialProfileCsvCodec.decode(
            CredentialProfileCsvCodec.encode(listOf(second, first)),
        )

        assertTrue(result is CredentialProfileCsvDecodeResult.Success)
        val values = (result as CredentialProfileCsvDecodeResult.Success).value
        assertEquals(listOf("ext-a", "ext-b"), values.map { it.extensionId })
        assertEquals(listOf("password", "username"), values[1].fields.map { it.key })
    }

    @Test
    fun `disabled schedule allows blank cron and timezone from existing profiles`() {
        val source = profile(
            id = "ext",
            credential = """{"username":"alice"}""",
            fields = listOf(
                CredentialProfileCsvFieldDefinition("username", "帳號", "text", true),
            ),
        ).copy(
            profile = CredentialProfile(
                extensionId = "ext",
                credential = """{"username":"alice"}""",
                scheduleEnabled = false,
                scheduleCron = "",
                timezoneId = "",
            ),
        )

        val result = CredentialProfileCsvCodec.decode(
            CredentialProfileCsvCodec.encode(listOf(source)),
        )

        assertTrue(result is CredentialProfileCsvDecodeResult.Success)
        val profile = (result as CredentialProfileCsvDecodeResult.Success).value.single().profile
        assertEquals(false, profile.scheduleEnabled)
        assertEquals("", profile.scheduleCron)
        assertEquals("", profile.timezoneId)
    }

    @Test
    fun `decode fails whole file for duplicate field and inconsistent metadata`() {
        val header = """
            moneylook-credential-profiles,1
            extensionId,extensionName,fieldKey,fieldLabel,fieldType,fieldRequired,value,scheduleEnabled,scheduleCron,timezoneId
        """.trimIndent()
        val duplicate = "$header\n" +
            "ext,Bank,username,帳號,text,true,user,true,0 8 * * *,Asia/Taipei\n" +
            "ext,Bank,username,帳號,text,true,other,true,0 8 * * *,Asia/Taipei"
        val inconsistent = "$header\n" +
            "ext,Bank,username,帳號,text,true,user,true,0 8 * * *,Asia/Taipei\n" +
            "ext,Other,password,密碼,password,true,secret,true,0 8 * * *,Asia/Taipei"

        assertTrue(
            CredentialProfileCsvCodec.decode(duplicate) is CredentialProfileCsvDecodeResult.Failure,
        )
        assertTrue(
            CredentialProfileCsvCodec.decode(inconsistent) is
                CredentialProfileCsvDecodeResult.Failure,
        )
    }

    @Test
    fun `decode rejects unknown columns invalid field metadata and invalid timezone`() {
        val unknownColumn = """
            moneylook-credential-profiles,1
            extensionId,extensionName,fieldKey,fieldLabel,fieldType,fieldRequired,value,scheduleEnabled,scheduleCron,timezoneId,extra
            ext,Bank,username,帳號,text,true,user,true,0 8 * * *,Asia/Taipei,payload
        """.trimIndent()
        val invalidType = validCsv().replace(",text,true,", ",pin,true,")
        val invalidTimezone = validCsv().replace("Asia/Taipei", "Mars/Olympus")
        val partialRow = validCsv().substringBeforeLast(',')

        listOf(unknownColumn, invalidType, invalidTimezone, partialRow).forEach { csv ->
            assertTrue(
                CredentialProfileCsvCodec.decode(csv) is CredentialProfileCsvDecodeResult.Failure,
            )
        }
    }

    @Test
    fun `hostile malformed quoting oversized input and excessive rows fail closed`() {
        val malformedQuote = validCsv().replace("Bank", "B\"ank")
        val trailingPayload = validCsv().replace("Bank", "\"Bank\"payload")
        val oversized = validCsv().replace("user", "x".repeat(1_000_001))
        val excessiveRows = buildString {
            append("moneylook-credential-profiles,1\n")
            repeat(10_000) { append("\n") }
        }

        listOf(malformedQuote, trailingPayload, oversized, excessiveRows).forEach { csv ->
            assertTrue(
                CredentialProfileCsvCodec.decode(csv) is CredentialProfileCsvDecodeResult.Failure,
            )
        }
    }

    @Test
    fun `encode rejects mismatched keys non string JSON and missing required values`() {
        val fields = listOf(
            CredentialProfileCsvFieldDefinition("username", "帳號", "text", true),
        )
        val missingKey = profile("ext", """{"password":"secret"}""", fields)
        val nonString = profile("ext", """{"username":123}""", fields)
        val blankRequired = profile("ext", """{"username":" "}""", fields)

        listOf(missingKey, nonString, blankRequired).forEach { source ->
            assertTrue(runCatching { CredentialProfileCsvCodec.encode(listOf(source)) }.isFailure)
        }
    }

    private fun extension(
        id: String,
        name: String,
        values: List<Pair<String, String>>,
    ): CredentialProfileCsvExtension {
        val json = values.joinToString(prefix = "{", postfix = "}") { (key, value) ->
            """"$key":"$value""""
        }
        val fields = values.map { (key, _) ->
            CredentialProfileCsvFieldDefinition(
                key = key,
                label = key,
                type = if (key == "password") "password" else "text",
                required = true,
            )
        }
        return profile(id, json, fields, name)
    }

    private fun profile(
        id: String,
        credential: String,
        fields: List<CredentialProfileCsvFieldDefinition>,
        name: String = "Bank",
    ) = CredentialProfileCsvExtension(
        extensionId = id,
        extensionName = name,
        profile = CredentialProfile(
            extensionId = id,
            credential = credential,
            scheduleEnabled = true,
            scheduleCron = "0 8 * * *",
            timezoneId = "Asia/Taipei",
        ),
        fields = fields,
    )

    private fun validCsv() = """
        moneylook-credential-profiles,1
        extensionId,extensionName,fieldKey,fieldLabel,fieldType,fieldRequired,value,scheduleEnabled,scheduleCron,timezoneId
        ext,Bank,username,帳號,text,true,user,true,0 8 * * *,Asia/Taipei
    """.trimIndent()
}
