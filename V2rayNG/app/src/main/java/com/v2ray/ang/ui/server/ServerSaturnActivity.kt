package com.v2ray.ang.ui.server

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
        val turbo = stringResource(R.string.saturn_turbo)
        val deep = stringResource(R.string.saturn_deep)
        Scaffold { padding ->
            Column(Modifier.padding(padding).consumeWindowInsets(padding)
                .verticalScroll(rememberScrollState()).imePadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.saturn_title), Modifier.padding(16.dp), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.saturn_hint), Modifier.padding(horizontal = 16.dp))
                if (state.loading) Text(stringResource(R.string.saturn_loading), Modifier.padding(16.dp))
                FormTextField(stringResource(R.string.server_lab_remarks), state.remarks, viewModel::changeRemarks, enabled = state.editable)
                FormTextField(stringResource(R.string.saturn_doh), settings.dohUrl,
                    { value -> viewModel.changeSettings { it.copy(dohUrl = value) } }, enabled = state.editable)
                FormTextField(stringResource(R.string.saturn_bootstrap), settings.bootstrapIps,
                    { value -> viewModel.changeSettings { it.copy(bootstrapIps = value) } }, enabled = state.editable)
                FormTextField(stringResource(R.string.saturn_bridges), settings.bridges,
                    { value -> viewModel.changeSettings { it.copy(bridges = value) } }, enabled = state.editable, maxLines = 10)
                FormDropdownField(stringResource(R.string.saturn_scan), settings.scanMode, listOf("turbo", "deep"),
                    { value -> viewModel.changeSettings { it.copy(scanMode = value) } }, enabled = state.editable,
                    optionLabel = { if (it == "deep") deep else turbo })
                FormTextField(stringResource(R.string.saturn_country), settings.country,
                    { value -> viewModel.changeSettings { it.copy(country = value.lowercase().trim()) } }, enabled = state.editable)
                state.errorRes?.let { Text(stringResource(it), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TextButton(onClick = { finish() }) { Text(stringResource(R.string.aether_cancel)) }
                    Button(onClick = viewModel::save, enabled = state.editable) { Text(stringResource(R.string.aether_save)) }
                }
            }
        }
    }
}
