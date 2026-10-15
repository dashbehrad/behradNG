package com.v2ray.ang.ui.server

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.DeleteConfirmDialog
import com.v2ray.ang.ui.compose.FormDropdownField
import com.v2ray.ang.ui.compose.FormTextField
import com.v2ray.ang.ui.compose.reorderableDragHandle
import com.v2ray.ang.ui.compose.verticalScrollbar
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

class ServerProxyChainActivity : BaseComponentActivity() {
    private val viewModel: ProxyChainEditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.initialize(intent.getStringExtra("guid").orEmpty(),
            intent.getStringExtra("subscriptionId").orEmpty(), intent.getBooleanExtra("isRunning", false))
    }

    @Composable
    override fun ScreenContent() {
        val state by viewModel.state.collectAsStateWithLifecycle()
        LaunchedEffect(state.savedGuid, state.deleted) {
            state.savedGuid?.let { with(ProfileEditorResult) { finishSaved(it, restartService = state.running) } }
            if (state.deleted) with(ProfileEditorResult) { finishDeleted(intent.getStringExtra("guid").orEmpty()) }
        }
        ProxyChainScreen(
            editGuid = intent.getStringExtra("guid").orEmpty(), state = state,
            onBackClick = { finish() }, onSave = viewModel::save, onDelete = viewModel::delete,
            onRemarksChange = viewModel::changeRemarks, onSelectMember = viewModel::selectMember,
            onAddMember = viewModel::addMember, onRemoveMember = viewModel::removeMember,
            onMoveMember = viewModel::moveMember,
        )
    }
}

@Composable
fun ProxyChainScreen(
    editGuid: String,
    state: ProxyChainEditorState,
    onBackClick: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onRemarksChange: (String) -> Unit,
    onSelectMember: (String, String) -> Unit,
    onAddMember: () -> Unit,
    onRemoveMember: (String) -> Unit,
    onMoveMember: (String, String) -> Unit,
) {
    val remarks = state.remarks
    val members = state.members
    val memberKeys = state.memberKeys
    val isRemarksError = state.errorRes != null && remarks.isBlank()
    var showProfileDeleteConfirm by remember { mutableStateOf(false) }
    var memberToDeleteKey by rememberSaveable { mutableStateOf<String?>(null) }
    val showDelete = editGuid.isNotEmpty() && !state.running

    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        onMoveMember(from.key.toString(), to.key.toString())
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            AppTopBar(
                title = EConfigType.PROXYCHAIN.toString(),
                onBackClick = onBackClick,
                actions = {
                    if (showDelete) {
                        IconButton(onClick = { showProfileDeleteConfirm = true }) {
                            Icon(painterResource(R.drawable.ic_delete_24dp), contentDescription = stringResource(R.string.acc_delete))
                        }
                    }
                    IconButton(onClick = onSave, enabled = state.editable) {
                        Icon(painterResource(R.drawable.ic_fab_check), contentDescription = stringResource(R.string.acc_save))
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    if (state.editable) onAddMember()
                },
                modifier = Modifier
                    .offset(y = -20.dp)
                    .navigationBarsPadding()
            ) {
                Icon(painterResource(R.drawable.ic_add_24dp), contentDescription = stringResource(R.string.acc_add_member))
            }
        }
    ) { innerPadding ->
        LazyColumn(
            state = lazyListState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding()
                .verticalScrollbar(lazyListState),
            contentPadding = PaddingValues(
                top = 8.dp,
                start = 16.dp,
                end = 16.dp,
                bottom = 36.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            )
        ) {
            item(key = "chain_status") {
                if (state.loading) Text(stringResource(R.string.aether_loading))
                state.errorRes?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                Text(stringResource(R.string.aether_chain_hint))
            }
            item(key = "remarks_field") {
                FormTextField(
                    label = stringResource(R.string.server_lab_remarks),
                    value = remarks,
                    onValueChange = onRemarksChange,
                    enabled = state.editable,
                    isError = isRemarksError
                )
            }

            item {
                Text(
                    text = stringResource(R.string.server_proxy_chain_members),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp)
                )
            }

            itemsIndexed(items = members, key = { index, _ -> memberKeys[index] }) { index, member ->
                val memberKey = memberKeys[index]
                ReorderableItem(reorderableState, key = memberKey) { isDragging ->
                    val elevation by animateDpAsState(if (isDragging) 4.dp else 0.dp)
                    Surface(shadowElevation = elevation) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(with(this) { reorderableDragHandle() })
                                .padding(horizontal = 4.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                stringResource(R.string.chain_hop_number, index + 1),
                                modifier = Modifier
                                    .padding(start = 16.dp)
                                    .width(10.dp)
                            )
                            FormDropdownField(
                                label = stringResource(R.string.server_lab_remarks),
                                placeholder = stringResource(R.string.server_proxy_chain_member_unselected),
                                value = member,
                                options = state.options.keys.toList(),
                                optionLabel = { state.options[it].orEmpty() },
                                onValueChange = { newVal -> onSelectMember(memberKey, newVal) },
                                enabled = state.editable,
                                editable = false,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = {
                                if (member.isBlank()) {
                                    onRemoveMember(memberKey)
                                } else {
                                    memberToDeleteKey = memberKey
                                }
                            }) {
                                Icon(
                                    painterResource(R.drawable.ic_delete_24dp),
                                    contentDescription = stringResource(R.string.acc_remove)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showProfileDeleteConfirm) {
        DeleteConfirmDialog(
            message = stringResource(R.string.confirm_delete_profile),
            itemName = remarks,
            onConfirm = { showProfileDeleteConfirm = false; onDelete() },
            onDismiss = { showProfileDeleteConfirm = false }
        )
    }
    memberToDeleteKey?.let { memberKey ->
        DeleteConfirmDialog(
            message = stringResource(R.string.confirm_delete_proxy_chain_member),
            itemName = state.options[members.getOrNull(memberKeys.indexOf(memberKey))].orEmpty(),
            onConfirm = {
                onRemoveMember(memberKey)
                memberToDeleteKey = null
            },
            onDismiss = { memberToDeleteKey = null }
        )
    }
}
