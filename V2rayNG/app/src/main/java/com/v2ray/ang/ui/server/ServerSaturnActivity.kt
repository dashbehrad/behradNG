package com.v2ray.ang.ui.server

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.FormDropdownField
import com.v2ray.ang.ui.compose.FormTextField

class ServerSaturnActivity : BaseComponentActivity() {
    private val viewModel: SaturnEditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.initialize(intent.getStringExtra("guid").orEmpty(), intent.getStringExtra("subscriptionId").orEmpty())
    }

    @Composable
    override fun ScreenContent() {
        val state by viewModel.state.collectAsStateWithLifecycle()
        LaunchedEffect(state.savedGuid) {
            state.savedGuid?.let { with(ProfileEditorResult) { finishSaved(it, restartService = false) } }
        }
        val settings = state.settings

        Scaffold { padding ->
            Column(
                Modifier
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    stringResource(R.string.saturn_title),
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    stringResource(R.string.saturn_deploy_desc),
                    Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )

                OutlinedButton(
                    onClick = {
                        startActivity(Intent(this@ServerSaturnActivity, SaturnDeployActivity::class.java))
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Text(stringResource(R.string.saturn_deploy_button))
                }

                if (state.loading) {
                    Text(stringResource(R.string.saturn_loading), Modifier.padding(16.dp))
                }

                FormTextField(
                    label = stringResource(R.string.server_lab_remarks),
                    value = state.remarks,
                    onValueChange = viewModel::changeRemarks,
                    enabled = state.editable
                )

                FormTextField(
                    label = stringResource(R.string.server_lab_address),
                    value = settings.server,
                    onValueChange = { value -> viewModel.changeSettings { it.copy(server = value) } },
                    enabled = state.editable
                )
                FormTextField(
                    label = stringResource(R.string.server_lab_port),
                    value = settings.serverPort.toString(),
                    onValueChange = { value ->
                        val port = value.toIntOrNull() ?: settings.serverPort
                        viewModel.changeSettings { it.copy(serverPort = port) }
                    },
                    enabled = state.editable
                )
                FormTextField(
                    label = stringResource(R.string.server_lab_id),
                    value = settings.uuid,
                    onValueChange = { value -> viewModel.changeSettings { it.copy(uuid = value) } },
                    enabled = state.editable
                )
                FormTextField(
                    label = stringResource(R.string.server_lab_public_key),
                    value = settings.publicKey,
                    onValueChange = { value -> viewModel.changeSettings { it.copy(publicKey = value) } },
                    enabled = state.editable
                )
                FormTextField(
                    label = stringResource(R.string.saturn_deploy_sni),
                    value = settings.sni,
                    onValueChange = { value -> viewModel.changeSettings { it.copy(sni = value) } },
                    enabled = state.editable
                )
                FormTextField(
                    label = stringResource(R.string.server_lab_short_id),
                    value = settings.shortId,
                    onValueChange = { value -> viewModel.changeSettings { it.copy(shortId = value) } },
                    enabled = state.editable
                )
                FormDropdownField(
                    label = stringResource(R.string.server_lab_stream_fingerprint),
                    value = settings.fingerprint,
                    options = listOf("chrome", "firefox", "ios", "edge", "safari"),
                    onValueChange = { value -> viewModel.changeSettings { it.copy(fingerprint = value) } },
                    enabled = state.editable
                )
                FormTextField(
                    label = stringResource(R.string.title_pref_fragment_length),
                    value = settings.fragmentation,
                    onValueChange = { value -> viewModel.changeSettings { it.copy(fragmentation = value) } },
                    enabled = state.editable
                )

                state.errorRes?.let {
                    Text(stringResource(it), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                }

                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TextButton(onClick = { finish() }) { Text(stringResource(R.string.aether_cancel)) }
                    Button(onClick = viewModel::save, enabled = state.editable) { Text(stringResource(R.string.aether_save)) }
                }
            }
        }
    }
}
