package com.aicode.feature.network.domain

import com.aicode.feature.agent.domain.container.ContainerInstaller
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NetworkHistoryStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun store(): NetworkHistoryStore {
        val installer = mockk<ContainerInstaller>()
        every { installer.aicodeDir } returns temp.root
        return NetworkHistoryStore(installer)
    }

    @Test
    fun saveSnapshot_writesJsonAndListsNewestFirst() {
        val store = store()
        val first = store.save(
            NetworkHistoryEntry(
                id = "older",
                createdAt = 1L,
                method = HttpMethod.GET.name,
                url = "https://example.com/a"
            )
        )
        val second = store.save(
            NetworkHistoryEntry(
                id = "newer",
                createdAt = 2L,
                method = HttpMethod.POST.name,
                url = "https://example.com/b",
                queryJson = """{"q":"1"}""",
                headersJson = """{"Accept":"application/json"}""",
                bodyJson = """{"ok":true}""",
                authJson = """{"type":"bearer","token":"t"}""",
                followRedirects = false,
                sslVerify = false,
                timeoutSeconds = 12
            )
        )

        val listed = store.list()
        assertEquals(listOf(second.id, first.id), listed.map { it.id })
        assertEquals("POST", listed.first().method)
        assertEquals(12, listed.first().timeoutSeconds)
        assertTrue(store.historyDir.listFiles()?.any { it.name == "${second.id}.json" } == true)
    }

    @Test
    fun deleteAll_removesHistoryFiles() {
        val store = store()
        store.saveSnapshot(
            method = HttpMethod.GET,
            url = "https://example.com",
            queryJson = DEFAULT_JSON_OBJECT,
            headersJson = DEFAULT_JSON_OBJECT,
            bodyJson = DEFAULT_JSON_OBJECT,
            authJson = DEFAULT_AUTH_JSON,
            settings = NetworkClientSettings()
        )
        assertTrue(store.list().isNotEmpty())
        assertTrue(store.deleteAll() > 0L)
        assertTrue(store.list().isEmpty())
    }
}
