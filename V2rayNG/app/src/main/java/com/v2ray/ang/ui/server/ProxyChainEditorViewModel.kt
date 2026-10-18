package com.v2ray.ang.ui.server

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class ProxyChainEditorState(
    val loading: Boolean = true,
    val saving: Boolean = false,
    val remarks: String = "",
    val members: List<String> = emptyList(),
    val memberKeys: List<String> = emptyList(),
    val options: Map<String, String> = emptyMap(),
    val errorRes: Int? = null,
    val savedGuid: String? = null,
    val deleted: Boolean = false,
    val running: Boolean = false,
) {
    val editable: Boolean get() = !loading && !saving
}

/** Owns the chain draft and its storage work; the composable only dispatches edits by row ID. */
class ProxyChainEditorViewModel @JvmOverloads constructor(
    private val savedState: SavedStateHandle,
    private val editorDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val loadProfiles: () -> Map<String, ProfileItem> = {
        MmkvManager.decodeAllServerList().mapNotNull { id -> MmkvManager.decodeServerConfig(id)?.let { id to it } }.toMap()
    },
    private val loadProfile: (String) -> ProfileItem? = MmkvManager::decodeServerConfig,
    private val saveProfile: (String, ProfileItem) -> String = MmkvManager::encodeServerConfigTransactional,
    private val removeProfile: (String) -> Unit = MmkvManager::removeServerConfigTransactional,
    private val selectedGuid: () -> String? = MmkvManager::getSelectServer,
    private val reportFailure: (String, String, Exception) -> Unit = { operation, guid, e ->
        LogUtil.e(AppConfig.TAG, "Proxy chain editor: $operation guid=$guid", e)
    },
    private val newRowKey: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {
    private val mutableState = MutableStateFlow(ProxyChainEditorState())
    val state = mutableState.asStateFlow()
    private var initialized = false
    private var guid = ""
    private var initialProfile: ProfileItem? = null

    fun initialize(profileGuid: String, subscriptionId: String, requestedRunning: Boolean) {
        if (initialized) return
        initialized = true
        guid = profileGuid
        viewModelScope.launch(editorDispatcher) {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val profile = if (guid.isBlank()) ProfileItem.create(EConfigType.PROXYCHAIN).copy(subscriptionId = subscriptionId)
                        else requireNotNull(loadProfile(guid))
                    require(profile.configType == EConfigType.PROXYCHAIN)
                    Triple(profile, loadProfiles(), requestedRunning && guid.isNotEmpty() && guid == selectedGuid())
                }
                initialProfile = loaded.first
                var error: Int? = null
                val initialMembers = savedState.get<List<String>>("chain_members") ?: if (guid.isBlank()) listOf("", "") else {
                    try { loaded.first.chainMemberGuids(loaded.second) } catch (e: Exception) {
                        reportFailure("resolve legacy members", guid, e)
                        error = R.string.aether_chain_invalid
                        listOf("", "")
                    }
                }
                val keys = savedState.get<List<String>>("chain_member_keys")?.takeIf { it.size == initialMembers.size && it.distinct().size == it.size }
                    ?: initialMembers.map { newRowKey() }
                mutableState.value = ProxyChainEditorState(loading = false,
                    remarks = savedState.get<String>("chain_remarks") ?: loaded.first.remarks,
                    members = initialMembers, memberKeys = keys, running = loaded.third, errorRes = error,
                    options = loaded.second.filterValues { it.configType !in setOf(EConfigType.CUSTOM, EConfigType.POLICYGROUP, EConfigType.PROXYCHAIN, EConfigType.HYSTERIA) }
                        .mapValues { (_, profile) -> profile.remarks })
                rememberDraft()
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                reportFailure("load", guid, e)
                mutableState.value = ProxyChainEditorState(loading = false, errorRes = R.string.toast_failure)
            }
        }
    }

    private fun rememberDraft() {
        val value = mutableState.value
        savedState["chain_remarks"] = value.remarks
        savedState["chain_members"] = value.members
        savedState["chain_member_keys"] = value.memberKeys
    }

    private fun edit(update: (ProxyChainEditorState) -> ProxyChainEditorState) {
        if (!mutableState.value.editable || initialProfile == null) return
        mutableState.value = update(mutableState.value).copy(errorRes = null)
        rememberDraft()
    }

    fun changeRemarks(value: String) = edit { it.copy(remarks = value) }
    fun selectMember(key: String, profileGuid: String) = edit { current ->
        val index = current.memberKeys.indexOf(key)
        if (index < 0 || profileGuid !in current.options) current else
            current.copy(members = current.members.toMutableList().also { it[index] = profileGuid })
    }
    fun addMember() = edit { it.copy(members = it.members + "", memberKeys = it.memberKeys + newRowKey()) }
    fun removeMember(key: String) = edit { current ->
        val (members, keys) = withoutProxyChainMember(current.members, current.memberKeys, key)
        current.copy(members = members, memberKeys = keys)
    }
    fun moveMember(fromKey: String, toKey: String) = edit { current ->
        val from = current.memberKeys.indexOf(fromKey)
        val to = current.memberKeys.indexOf(toKey)
        if (from < 0 || to < 0) current else {
            val members = current.members.toMutableList()
            val keys = current.memberKeys.toMutableList()
            members.add(to, members.removeAt(from))
            keys.add(to, keys.removeAt(from))
            current.copy(members = members, memberKeys = keys)
        }
    }

    fun save() {
        val snapshot = mutableState.value
        val initial = initialProfile ?: return
        if (!snapshot.editable) return
        if (snapshot.remarks.isBlank() || snapshot.members.any { it.isBlank() } || snapshot.members.size < 2) {
            mutableState.value = snapshot.copy(errorRes = R.string.aether_chain_invalid)
            return
        }
        mutableState.value = snapshot.copy(saving = true, errorRes = null)
        viewModelScope.launch(editorDispatcher) {
            try {
                val id = withContext(Dispatchers.IO) {
                    val profiles = loadProfiles()
                    val candidate = initial.copy(remarks = snapshot.remarks.trim(), proxyChainProfileGuids = snapshot.members)
                    candidate.aetherEntryGuid(profiles)
                    candidate.proxyChainProfiles = snapshot.members.joinToString(",") { profiles.getValue(it).remarks }
                    candidate.description = snapshot.members.joinToString(" -> ") { profiles.getValue(it).remarks }
                    saveProfile(guid, candidate)
                }
                mutableState.value = snapshot.copy(saving = false, savedGuid = id)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                reportFailure("save", guid, e)
                mutableState.value = snapshot.copy(saving = false, errorRes = if (e is IllegalArgumentException) R.string.aether_chain_invalid else R.string.toast_failure)
            }
        }
    }

    fun delete() {
        val snapshot = mutableState.value
        if (!snapshot.editable || guid.isBlank()) return
        mutableState.value = snapshot.copy(saving = true)
        viewModelScope.launch(editorDispatcher) {
            try {
                withContext(Dispatchers.IO) {
                    check(guid != selectedGuid())
                    removeProfile(guid)
                }
                mutableState.value = snapshot.copy(saving = false, deleted = true)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                reportFailure("delete", guid, e)
                mutableState.value = snapshot.copy(saving = false, errorRes = R.string.toast_action_not_allowed)
            }
        }
    }
}
