package com.v2ray.ang.ui.server

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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.core.UrnetworkManager
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
        val uriHandler = LocalUriHandler.current
        LaunchedEffect(state.savedGuid) {
            state.savedGuid?.let { with(ProfileEditorResult) { finishSaved(it, restartService = false) } }
        }
        val settings = state.settings

        val aetherProtocolWg = stringResource(R.string.saturn_aether_protocol_wg)
        val aetherProtocolMasque = stringResource(R.string.saturn_aether_protocol_masque)
        val carrierH2 = stringResource(R.string.saturn_carrier_h2)
        val carrierH3 = stringResource(R.string.saturn_carrier_h3)
        val urModeWeb = stringResource(R.string.saturn_ur_mode_web)
        val urModeStreaming = stringResource(R.string.saturn_ur_mode_streaming)

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
                    stringResource(R.string.saturn_chain_hint),
                    Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
                if (state.loading) {
                    Text(stringResource(R.string.saturn_loading), Modifier.padding(16.dp))
                }

                FormTextField(
                    label = stringResource(R.string.server_lab_remarks),
                    value = state.remarks,
                    onValueChange = viewModel::changeRemarks,
                    enabled = state.editable
                )

                // Section 1: Aether Settings
                Text(
                    stringResource(R.string.saturn_aether_section),
                    Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp),
                    style = MaterialTheme.typography.titleMedium
                )
                FormDropdownField(
                    label = stringResource(R.string.saturn_aether_protocol),
                    value = settings.normalizedAetherProtocol(),
                    options = listOf("masque", "wg"),
                    onValueChange = { value -> viewModel.changeSettings { it.copy(aetherProtocol = value) } },
                    enabled = state.editable,
                    optionLabel = { if (it == "wg") aetherProtocolWg else aetherProtocolMasque }
                )
                FormDropdownField(
                    label = stringResource(R.string.saturn_aether_carrier),
                    value = settings.normalizedAetherCarrier(),
                    options = listOf("h2", "h3"),
                    onValueChange = { value -> viewModel.changeSettings { it.copy(aetherCarrier = value, carrier = value) } },
                    enabled = state.editable && settings.normalizedAetherProtocol() != "wg",
                    optionLabel = { if (it == "h3") carrierH3 else carrierH2 }
                )

                // Section 2: URnetwork Settings
                Text(
                    stringResource(R.string.saturn_urnetwork_section),
                    Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp),
                    style = MaterialTheme.typography.titleMedium
                )

                if (!settings.hasUrAccount()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = settings.urTermsAgreed,
                            onCheckedChange = viewModel::agreeTerms,
                            enabled = state.editable
                        )
                        Text(
                            stringResource(R.string.saturn_ur_agree_terms),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall
                        )
                        TextButton(onClick = { uriHandler.openUri(UrnetworkManager.TERMS_URL) }) {
                            Text(stringResource(R.string.saturn_ur_terms_link))
                        }
                    }

                    Button(
                        onClick = viewModel::createQuickAccount,
                        enabled = state.editable && settings.urTermsAgreed,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        Text(stringResource(R.string.saturn_ur_create_account))
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                stringResource(R.string.saturn_ur_account_active, settings.urAccountId.orEmpty()),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                stringResource(R.string.saturn_ur_quota_info),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        OutlinedButton(
                            onClick = viewModel::resetAccount,
                            enabled = state.editable
                        ) {
                            Text(stringResource(R.string.saturn_ur_reset_account))
                        }
                    }

                    FormDropdownField(
                        label = stringResource(R.string.saturn_ur_mode),
                        value = settings.normalizedUrMode(),
                        options = listOf("web", "streaming"),
                        onValueChange = { value -> viewModel.changeSettings { it.copy(urMode = value) } },
                        enabled = state.editable,
                        optionLabel = { if (it == "streaming") urModeStreaming else urModeWeb }
                    )

                    FormDropdownField(
                        label = stringResource(R.string.saturn_ur_carrier),
                        value = settings.normalizedUrCarrier(),
                        options = listOf("h2", "h3"),
                        onValueChange = { value -> viewModel.changeSettings { it.copy(urCarrier = value) } },
                        enabled = state.editable,
                        optionLabel = { if (it == "h3") carrierH3 else carrierH2 }
                    )

                    val locationOptions = state.locations.map { it.code }
                    FormDropdownField(
                        label = stringResource(R.string.saturn_ur_locations),
                        value = settings.urLocation,
                        options = locationOptions,
                        onValueChange = { value -> viewModel.changeSettings { it.copy(urLocation = value) } },
                        enabled = state.editable,
                        optionLabel = { code ->
                            state.locations.firstOrNull { it.code == code }?.name ?: code
                        }
                    )

                    OutlinedButton(
                        onClick = viewModel::updateLocations,
                        enabled = state.editable && !state.updatingLocations,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    ) {
                        Text(
                            if (state.updatingLocations) stringResource(R.string.saturn_loading)
                            else stringResource(R.string.saturn_ur_update_locations)
                        )
                    }
                }

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
