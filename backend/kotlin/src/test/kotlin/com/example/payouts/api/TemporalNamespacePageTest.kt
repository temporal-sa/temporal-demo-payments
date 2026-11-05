package com.example.payouts.api

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class TemporalNamespacePageTest {

    private val literal = "https://cloud.temporal.io/namespaces/fixed.acct"

    @Test
    fun `the platform's base and namespace win over the literal page`() {
        assertEquals(
            "https://cloud.temporal.io/namespaces/demo.acct",
            temporalNamespacePage("https://cloud.temporal.io", "demo.acct", literal),
        )
    }

    @Test
    fun `a crashable workspace links to its own dev server UI`() {
        assertEquals(
            "https://ws.example/_temporal/namespaces/demo",
            temporalNamespacePage("https://ws.example/_temporal/", "demo", literal),
        )
    }

    @Test
    fun `without both the literal page is used`() {
        assertEquals(literal, temporalNamespacePage("", "", literal))
        assertEquals(literal, temporalNamespacePage("https://cloud.temporal.io", "", literal))
        assertEquals("", temporalNamespacePage("", "", ""))
    }
}
