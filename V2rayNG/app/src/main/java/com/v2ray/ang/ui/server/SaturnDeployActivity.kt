package com.v2ray.ang.ui.server

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.FormTextField
import com.v2ray.ang.ui.server.ProfileEditorResult.finishSaved

class SaturnDeployActivity : BaseComponentActivity() {
    private val viewModel: SaturnDeployViewModel by viewModels()

    @Composable
    override fun ScreenContent() {
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        val scriptContent = remember {
            runCatching {
                assets.open("saturn/install-saturn.sh").bufferedReader().use { it.readText() }
            }.getOrDefault("")
        }

        Scaffold(
            topBar = {
                AppTopBar(
                    title = stringResource(R.string.saturn_deploy_title),
                    onBackClick = { finish() }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header Information
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.saturn_deploy_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.saturn_deploy_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Error message
                state.error?.let { err ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Text(
                            text = err,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                // Server Connection Settings Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FormTextField(
                            label = stringResource(R.string.saturn_deploy_host),
                            value = state.host,
                            onValueChange = viewModel::updateHost,
                            placeholder = "203.0.113.195",
                            enabled = !state.isDeploying && !state.isFinished
                        )

                        Row(modifier = Modifier.fillMaxWidth()) {
                            Box(modifier = Modifier.weight(1f)) {
                                FormTextField(
                                    label = stringResource(R.string.saturn_deploy_ssh_port),
                                    value = state.port,
                                    onValueChange = viewModel::updatePort,
                                    keyboardType = KeyboardType.Number,
                                    enabled = !state.isDeploying && !state.isFinished
                                )
                            }
                            Box(modifier = Modifier.weight(1f)) {
                                FormTextField(
                                    label = stringResource(R.string.saturn_deploy_ssh_user),
                                    value = state.username,
                                    onValueChange = viewModel::updateUsername,
                                    enabled = !state.isDeploying && !state.isFinished
                                )
                            }
                        }

                        // Auth Method Selector
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = state.authMethod == SshAuthMethod.PASSWORD,
                                onClick = { viewModel.updateAuthMethod(SshAuthMethod.PASSWORD) },
                                label = { Text(stringResource(R.string.saturn_deploy_auth_password)) },
                                enabled = !state.isDeploying && !state.isFinished,
                                colors = FilterChipDefaults.filterChipColors()
                            )
                            FilterChip(
                                selected = state.authMethod == SshAuthMethod.PRIVATE_KEY,
                                onClick = { viewModel.updateAuthMethod(SshAuthMethod.PRIVATE_KEY) },
                                label = { Text(stringResource(R.string.saturn_deploy_auth_key)) },
                                enabled = !state.isDeploying && !state.isFinished,
                                colors = FilterChipDefaults.filterChipColors()
                            )
                        }

                        if (state.authMethod == SshAuthMethod.PASSWORD) {
                            FormTextField(
                                label = stringResource(R.string.saturn_deploy_password),
                                value = state.password,
                                onValueChange = viewModel::updatePassword,
                                keyboardType = KeyboardType.Password,
                                enabled = !state.isDeploying && !state.isFinished
                            )
                        } else {
                            FormTextField(
                                label = stringResource(R.string.saturn_deploy_private_key),
                                value = state.privateKey,
                                onValueChange = viewModel::updatePrivateKey,
                                placeholder = "-----BEGIN OPENSSH PRIVATE KEY-----",
                                maxLines = 5,
                                enabled = !state.isDeploying && !state.isFinished
                            )
                            FormTextField(
                                label = stringResource(R.string.saturn_deploy_passphrase),
                                value = state.passphrase,
                                onValueChange = viewModel::updatePassphrase,
                                keyboardType = KeyboardType.Password,
                                enabled = !state.isDeploying && !state.isFinished
                            )
                        }

                        Row(modifier = Modifier.fillMaxWidth()) {
                            Box(modifier = Modifier.weight(1f)) {
                                FormTextField(
                                    label = stringResource(R.string.saturn_deploy_saturn_port),
                                    value = state.saturnPort,
                                    onValueChange = viewModel::updateSaturnPort,
                                    keyboardType = KeyboardType.Number,
                                    enabled = !state.isDeploying && !state.isFinished
                                )
                            }
                            Box(modifier = Modifier.weight(1f)) {
                                FormTextField(
                                    label = stringResource(R.string.saturn_deploy_sni),
                                    value = state.camouflageSni,
                                    onValueChange = viewModel::updateCamouflageSni,
                                    enabled = !state.isDeploying && !state.isFinished
                                )
                            }
                        }
                    }
                }

                // Progress state
                if (state.isDeploying) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = state.progressStage,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            LinearProgressIndicator(
                                progress = { state.progressPercent / 100f },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                // Action Buttons
                if (!state.isDeploying && !state.isFinished) {
                    Button(
                        onClick = {
                            viewModel.startDeploy(
                                scriptContent = scriptContent,
                                binaryProvider = { arch ->
                                    val assetName = if (arch == "arm64") "saturn/saturn-server-arm64" else "saturn/saturn-server-amd64"
                                    runCatching { assets.open(assetName) }.getOrNull()
                                }
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.saturn_deploy_btn_start),
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }
                } else if (state.isDeploying) {
                    OutlinedButton(
                        onClick = { viewModel.cancelDeploy() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Text(stringResource(R.string.saturn_deploy_btn_cancel))
                    }
                } else if (state.isFinished) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val guid = state.deployedGuid
                                if (guid != null) {
                                    MmkvManager.setSelectServer(guid)
                                    finishSaved(guid, restartService = false)
                                } else {
                                    finish()
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.saturn_deploy_btn_add_to_configs),
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }

                        Button(
                            onClick = {
                                val guid = state.deployedGuid
                                if (guid != null) {
                                    MmkvManager.setSelectServer(guid)
                                    LauncherManager.restartServiceOrStart(this@SaturnDeployActivity) {
                                        LauncherManager.startService(this@SaturnDeployActivity)
                                    }
                                    finishSaved(guid, restartService = true)
                                } else {
                                    finish()
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.saturn_deploy_btn_finish),
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }
                    }
                }

                // Live Console Output
                if (state.logs.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.saturn_deploy_console),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    val logListState = rememberLazyListState()
                    LaunchedEffect(state.logs.size) {
                        if (state.logs.isNotEmpty()) {
                            logListState.animateScrollToItem(state.logs.size - 1)
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 160.dp, max = 280.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF141414))
                            .padding(8.dp)
                    ) {
                        LazyColumn(
                            state = logListState,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(state.logs) { logLine ->
                                Text(
                                    text = logLine,
                                    color = if (logLine.contains("error", ignoreCase = true) || logLine.contains("failed", ignoreCase = true)) {
                                        Color(0xFFFF5252)
                                    } else if (logLine.contains("ready", ignoreCase = true) || logLine.contains("success", ignoreCase = true)) {
                                        Color(0xFF69F0AE)
                                    } else {
                                        Color(0xFFE0E0E0)
                                    },
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 15.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
