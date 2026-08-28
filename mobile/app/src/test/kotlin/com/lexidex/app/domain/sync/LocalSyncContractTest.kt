package com.lexidex.app.domain.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

class LocalSyncContractTest {
    @Test
    fun `kotlin interprets the shared request fixture`() {
        val request = parseSyncExchangeRequest(fixture("exchange-request.valid.json"))

        assertEquals("req_00000000000000000000000000000001", request.requestId)
        assertEquals("103", request.sinceCursor)
        assertEquals(5, request.changes.size)
        assertEquals(
            setOf("personal_term", "favorite", "history", "collection", "collection_member"),
            request.changes.mapTo(mutableSetOf(), SyncClientChange::entityType),
        )
        assertEquals("usr_33333333333333333333333333333333", request.changes.first().entityId.uid)
    }

    @Test
    fun `kotlin reads term payload version two with ordered sources`() {
        val text = fixture("exchange-request.valid.json")
            .replaceFirst("\"payload_version\": 1", "\"payload_version\": 2")
            .replaceFirst(
                "\"source_url\": \"https://es.wikipedia.org/wiki/Red_de_%C3%A1rea_local\",",
                """"source_url": "https://es.wikipedia.org/wiki/Red_de_%C3%A1rea_local",
                "sources": [{
                  "uid": "src_65ddf8964ecaf36e7f9610700ade3f02",
                  "provider_id": "wikipedia",
                  "kind": "wikipedia",
                  "title": "",
                  "url": "https://es.wikipedia.org/wiki/Red_de_%C3%A1rea_local",
                  "language": "es",
                  "license_name": "CC BY-SA",
                  "retrieved_at": null,
                  "content_sha256": ""
                }],""",
            )

        val request = parseSyncExchangeRequest(text)
        assertEquals(2, request.changes.first().payloadVersion)
        assertEquals(1, request.changes.first().payload?.get("sources")?.let { (it as kotlinx.serialization.json.JsonArray).size })
    }

    @Test
    fun `kotlin interprets the shared response and error fixtures`() {
        val response = parseSyncExchangeResponse(fixture("exchange-response.valid.json"))
        val error = parseSyncErrorResponse(fixture("error-response.valid.json"))

        assertEquals(
            listOf("applied", "duplicate", "conflict"),
            response.acknowledgements.map(SyncAcknowledgement::status),
        )
        assertEquals(listOf("104", "105"), response.changes.map(SyncServerChange::cursor))
        assertEquals("105", response.nextCursor)
        assertEquals("cursor_expired", error.error.code)
        assertFalse(error.error.retryable)
    }

    @Test
    fun `duplicate change id is rejected with the same stable code`() {
        try {
            parseSyncExchangeRequest(fixture("exchange-request.invalid-duplicate-change-id.json"))
            fail("Expected InvalidSyncContractException")
        } catch (error: InvalidSyncContractException) {
            assertEquals("duplicate_change_id", error.code)
        }
    }

    @Test
    fun `v1 limits are part of the executable contract`() {
        assertEquals(1024 * 1024, MAX_SYNC_REQUEST_BYTES)
        assertEquals(200, MAX_SYNC_CHANGES)
        assertEquals(200, MAX_SYNC_PULL_LIMIT)
    }

    private fun fixture(name: String): String = requireNotNull(
        javaClass.getResource("/local-sync/v1/fixtures/$name"),
    ).readText()
}
