package appmonitor.sdk.core

import appmonitor.v1.MockFailure
import appmonitor.v1.MockRule
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The same vector is read by every SDK that serves mocks (ADR-0110 B.1).
class MockMatchingVectorTest {
    private val vector = Json.parseToJsonElement(MOCK_MATCHING_VECTOR).jsonObject
    private val rules =
        vector.getValue("rules").jsonArray.map {
            val rule = it.jsonObject
            MockRule(
                id = rule.getValue("id").jsonPrimitive.long,
                method = rule.string("method"),
                host = rule.string("host"),
                path = rule.string("path"),
                failure = MockFailure(message = "mocked"),
            )
        }
    private val set = MockRuleSet().apply { replace(rules) }

    @Test
    fun everyCaseMatchesTheRuleTheVectorSays() {
        val cases = vector.getValue("cases").jsonArray.map { it.jsonObject }
        assertTrue(cases.isNotEmpty())
        for (case in cases) {
            val expected =
                case
                    .getValue("match")
                    .takeUnless { it == JsonNull }
                    ?.jsonPrimitive
                    ?.long
            assertEquals(expected, set.match(case.string("method"), case.string("url"))?.id, case.string("name"))
        }
    }

    @Test
    fun aClearedSetMatchesNothing() {
        set.clear()
        assertNull(set.match("GET", "https://api.example.com/v1/items"))
    }

    @Test
    fun aRuleWithoutAnAnswerIsLeftOut() {
        set.replace(listOf(MockRule(id = 9, host = "api.example.com", path = "/v1/items")))
        assertNull(set.match("GET", "https://api.example.com/v1/items"))
    }

    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
}
