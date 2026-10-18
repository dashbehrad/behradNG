package com.v2ray.ang.ui.server

import androidx.lifecycle.SavedStateHandle
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ProxyChainEditorViewModelTest {
    private var nextRowKey = 0
    private val profiles = mapOf(
        "entry-guid" to ProfileItem.create(EConfigType.AETHER).copy(remarks = "entry"),
        "exit-guid" to ProfileItem.create(EConfigType.VLESS).copy(remarks = "exit"),
    )

    @Test
    fun legacyLoadAndGuidSavePreserveMetadataAndResolveEditsByRowId() = runBlocking {
        var saved: ProfileItem? = null
        val legacy = ProfileItem.create(EConfigType.PROXYCHAIN).copy(remarks = "chain", subscriptionId = "group-guid", proxyChainProfiles = "entry,exit")
        val handle = SavedStateHandle()
        val model = ProxyChainEditorViewModel(handle, Dispatchers.Unconfined, { profiles }, { legacy },
            { id, profile -> assertEquals("chain-guid", id); saved = profile; id }, {}, { null }, { _, _, _ -> }, newRowKey = { "draft-${nextRowKey++}" })
        model.initialize("chain-guid", "", false)
        withTimeout(3_000) { model.state.first { !it.loading } }
        val keys = model.state.value.memberKeys
        assertEquals(listOf("entry-guid", "exit-guid"), model.state.value.members)
        model.moveMember(keys[1], keys[0])
        model.selectMember(keys[0], "entry-guid")
        assertEquals(listOf("exit-guid", "entry-guid"), model.state.value.members)
        model.save()
        withTimeout(3_000) { model.state.first { !it.saving } }
        assertNull(saved)
        assertEquals(R.string.aether_chain_invalid, model.state.value.errorRes)
        model.moveMember(keys[0], keys[1])
        model.changeRemarks("renamed chain")
        model.save()
        withTimeout(3_000) { model.state.first { it.savedGuid != null } }
        assertEquals(listOf("entry-guid", "exit-guid"), saved?.proxyChainProfileGuids)
        assertEquals("entry,exit", saved?.proxyChainProfiles)
        assertEquals("group-guid", saved?.subscriptionId)
        assertEquals("renamed chain", saved?.remarks)
        assertEquals(listOf("entry-guid", "exit-guid"), handle.get<List<String>>("chain_members"))
    }

    @Test
    fun newEmptyDraftAndStorageFailuresNeverReportSuccess() = runBlocking {
        val fresh = ProxyChainEditorViewModel(SavedStateHandle(), Dispatchers.Unconfined, { profiles }, { null },
            { _, _ -> error("write failed") }, {}, { null }, { _, _, _ -> }, newRowKey = { "draft-${nextRowKey++}" })
        fresh.initialize("", "group-guid", false)
        withTimeout(3_000) { fresh.state.first { !it.loading } }
        fresh.save()
        assertEquals(R.string.aether_chain_invalid, fresh.state.value.errorRes)
        fresh.changeRemarks("new chain")
        val keys = fresh.state.value.memberKeys
        fresh.selectMember(keys[0], "entry-guid")
        fresh.selectMember(keys[1], "exit-guid")
        fresh.save()
        withTimeout(3_000) { fresh.state.first { !it.saving } }
        assertNull(fresh.state.value.savedGuid)
        assertEquals(R.string.toast_failure, fresh.state.value.errorRes)
        val failedLoad = ProxyChainEditorViewModel(SavedStateHandle(), Dispatchers.Unconfined, { error("read failed") }, { null },
            { _, _ -> error("must not save") }, {}, { null }, { _, _, _ -> }, newRowKey = { "draft-${nextRowKey++}" })
        failedLoad.initialize("", "", false)
        withTimeout(3_000) { failedLoad.state.first { !it.loading } }
        assertEquals(R.string.toast_failure, failedLoad.state.value.errorRes)
        assertNull(failedLoad.state.value.savedGuid)
    }

    @Test
    fun ambiguousLegacyLoadAndDeleteOutcomesAreExplicit() = runBlocking {
        val legacy = ProfileItem.create(EConfigType.PROXYCHAIN).copy(proxyChainProfiles = "entry,exit")
        val ambiguous = ProxyChainEditorViewModel(SavedStateHandle(), Dispatchers.Unconfined,
            { profiles + ("duplicate-guid" to profiles.getValue("exit-guid")) }, { legacy },
            { _, _ -> error("must not save") }, {}, { null }, { _, _, _ -> }, newRowKey = { "draft-" + nextRowKey++ })
        ambiguous.initialize("chain-guid", "", false)
        withTimeout(3_000) { ambiguous.state.first { !it.loading } }
        assertEquals(R.string.aether_chain_invalid, ambiguous.state.value.errorRes)
        val removed = mutableListOf<String>()
        val model = ProxyChainEditorViewModel(SavedStateHandle(), Dispatchers.Unconfined, { profiles }, { legacy },
            { id, _ -> id }, { removed.add(it) }, { null }, { _, _, _ -> }, newRowKey = { "draft-" + nextRowKey++ })
        model.initialize("chain-guid", "", false)
        withTimeout(3_000) { model.state.first { !it.loading } }
        model.delete()
        withTimeout(3_000) { model.state.first { it.deleted } }
        assertEquals(listOf("chain-guid"), removed)
    }

    @Test
    fun restoredDraftKeepsMemberIdentityAndBlocksDeletingTheSelectedProfile() = runBlocking {
        val handle = SavedStateHandle(mapOf("chain_remarks" to "draft", "chain_members" to listOf("entry-guid", "exit-guid"),
            "chain_member_keys" to listOf("row-entry", "row-exit")))
        val existing = ProfileItem.create(EConfigType.PROXYCHAIN)
        val model = ProxyChainEditorViewModel(handle, Dispatchers.Unconfined, { profiles }, { existing },
            { id, _ -> id }, { error("must not delete selected") }, { "chain-guid" }, { _, _, _ -> }, newRowKey = { "draft-${nextRowKey++}" })
        model.initialize("chain-guid", "", true)
        withTimeout(3_000) { model.state.first { !it.loading } }
        assertEquals("draft", model.state.value.remarks)
        assertEquals(listOf("row-entry", "row-exit"), model.state.value.memberKeys)
        model.delete()
        withTimeout(3_000) { model.state.first { !it.saving } }
        assertEquals(false, model.state.value.deleted)
        assertNotNull(model.state.value.errorRes)
        model.removeMember("row-entry")
        assertEquals(listOf("exit-guid"), model.state.value.members)
        model.addMember()
        assertEquals(2, model.state.value.members.size)
    }
}
