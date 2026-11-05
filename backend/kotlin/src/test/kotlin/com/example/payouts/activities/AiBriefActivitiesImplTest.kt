package com.example.payouts.activities

import com.example.payouts.model.activity.ChooseInvestigationRequest
import com.example.payouts.model.domain.Money
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AiBriefActivitiesImplTest {
    @Test
    fun `OpenAI provider sends a structured request and reads the investigation choice`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var authorization = ""
        var requestBody = ""
        server.createContext("/v1/chat/completions") { exchange ->
            authorization = exchange.requestHeaders.getFirst("Authorization")
            requestBody = exchange.requestBody.bufferedReader().readText()
            val response = """{"choices":[{"message":{"content":"{\"tools\":[\"RECENT_PAYOUTS\"],\"reason\":\"Compare past amounts\"}","refusal":null}}]}"""
            val bytes = response.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val activity = AiBriefActivitiesImpl(
                "gpt-4.1-mini", "http://127.0.0.1:1", "openai",
                "http://127.0.0.1:${server.address.port}/v1", "test-key",
            )
            val choice = activity.chooseInvestigation(
                ChooseInvestigationRequest(Money(250_000, "USD"), 250_000, listOf("unusual amount for this customer")),
            )
            assertEquals(listOf("RECENT_PAYOUTS"), choice.tools)
            assertEquals("Compare past amounts", choice.reason)
            assertEquals("Bearer test-key", authorization)
            assertTrue(requestBody.contains("\"type\":\"json_schema\""))
            assertTrue(requestBody.contains("\"model\":\"gpt-4.1-mini\""))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `typical low-value payout skips investigation without calling the model`() {
        val activity = AiBriefActivitiesImpl("test-qwen", "http://127.0.0.1:1")

        val choice = activity.chooseInvestigation(
            ChooseInvestigationRequest(Money(7_500, "USD"), 7_500, listOf("typical customer behavior")),
        )

        assertTrue(choice.tools.isEmpty())
        assertTrue(choice.reason.contains("Typical customer behavior"))
    }

    @Test
    fun `no review facts skip investigation without calling the model`() {
        val activity = AiBriefActivitiesImpl("test-qwen", "http://127.0.0.1:1")

        val choice = activity.chooseInvestigation(
            ChooseInvestigationRequest(Money(250_000, "USD"), 250_000, emptyList()),
        )

        assertTrue(choice.tools.isEmpty())
        assertEquals("No review facts supplied", choice.reason)
    }
}
