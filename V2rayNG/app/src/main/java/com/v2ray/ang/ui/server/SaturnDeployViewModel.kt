package com.v2ray.ang.ui.server

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.handler.SaturnSshProvisioner
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SshAuthMethod {
    PASSWORD,
    PRIVATE_KEY
}

data class SaturnDeployUiState(
    val host: String = "",
    val port: String = "22",
    val username: String = "root",
    val authMethod: SshAuthMethod = SshAuthMethod.PASSWORD,
    val password: String = "",
    val privateKey: String = "",
    val passphrase: String = "",
    val saturnPort: String = "443",
    val camouflageSni: String = "dl.google.com",
    val isDeploying: Boolean = false,
    val progressStage: String = "",
    val progressPercent: Int = 0,
    val logs: List<String> = emptyList(),
    val deployedProfile: ProfileItem? = null,
    val deployedGuid: String? = null,
    val error: String? = null,
    val isFinished: Boolean = false
)

class SaturnDeployViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(SaturnDeployUiState())
    val uiState: StateFlow<SaturnDeployUiState> = _uiState.asStateFlow()

    private var deployJob: Job? = null

    fun updateHost(host: String) = _uiState.update { it.copy(host = host.trim(), error = null) }
    fun updatePort(port: String) = _uiState.update { it.copy(port = port.trim()) }
    fun updateUsername(user: String) = _uiState.update { it.copy(username = user.trim()) }
    fun updateAuthMethod(method: SshAuthMethod) = _uiState.update { it.copy(authMethod = method, error = null) }
    fun updatePassword(pass: String) = _uiState.update { it.copy(password = pass, error = null) }
    fun updatePrivateKey(key: String) = _uiState.update { it.copy(privateKey = key, error = null) }
    fun updatePassphrase(phrase: String) = _uiState.update { it.copy(passphrase = phrase) }
    fun updateSaturnPort(port: String) = _uiState.update { it.copy(saturnPort = port.trim()) }
    fun updateCamouflageSni(sni: String) = _uiState.update { it.copy(camouflageSni = sni.trim()) }
    fun clearError() = _uiState.update { it.copy(error = null) }

    fun startDeploy(
        scriptContent: String,
        binaryProvider: ((String) -> java.io.InputStream?)? = null
    ) {
        val state = _uiState.value
        if (state.host.isBlank()) {
            _uiState.update { it.copy(error = "Server IP / Hostname is required") }
            return
        }
        val sshPort = state.port.toIntOrNull() ?: 22
        val saturnPort = state.saturnPort.toIntOrNull() ?: 443

        if (state.authMethod == SshAuthMethod.PASSWORD && state.password.isBlank()) {
            _uiState.update { it.copy(error = "SSH Password is required") }
            return
        }
        if (state.authMethod == SshAuthMethod.PRIVATE_KEY && state.privateKey.isBlank()) {
            _uiState.update { it.copy(error = "SSH Private Key is required") }
            return
        }

        val creds = SaturnSshProvisioner.SshCredentials(
            host = state.host,
            port = sshPort,
            username = state.username.ifBlank { "root" },
            password = if (state.authMethod == SshAuthMethod.PASSWORD) state.password else null,
            privateKey = if (state.authMethod == SshAuthMethod.PRIVATE_KEY) state.privateKey else null,
            passphrase = state.passphrase.takeIf { it.isNotBlank() },
            saturnPort = saturnPort,
            camouflageSni = state.camouflageSni.ifBlank { "dl.google.com" }
        )

        _uiState.update {
            it.copy(
                isDeploying = true,
                progressStage = "Initializing...",
                progressPercent = 5,
                logs = listOf("Starting Saturn server automated deployment..."),
                error = null,
                isFinished = false
            )
        }

        deployJob = viewModelScope.launch {
            val result = SaturnSshProvisioner.deploy(creds, scriptContent, binaryProvider) { event ->
                when (event) {
                    is SaturnSshProvisioner.DeployEvent.Log -> {
                        _uiState.update { current ->
                            val updatedLogs = (current.logs + event.message).takeLast(200)
                            current.copy(logs = updatedLogs)
                        }
                    }
                    is SaturnSshProvisioner.DeployEvent.Progress -> {
                        _uiState.update {
                            it.copy(progressStage = event.stage, progressPercent = event.percent)
                        }
                    }
                    is SaturnSshProvisioner.DeployEvent.Success -> {
                        _uiState.update {
                            it.copy(
                                isDeploying = false,
                                isFinished = true,
                                progressPercent = 100,
                                progressStage = "Saturn Server Deployed & Config Imported!",
                                deployedGuid = event.guid,
                                deployedProfile = event.profile
                            )
                        }
                    }
                    is SaturnSshProvisioner.DeployEvent.Error -> {
                        _uiState.update {
                            it.copy(
                                isDeploying = false,
                                progressStage = "Deployment Failed",
                                error = event.message
                            )
                        }
                    }
                }
            }
        }
    }

    fun cancelDeploy() {
        deployJob?.cancel()
        _uiState.update { it.copy(isDeploying = false, progressStage = "Deployment cancelled") }
    }
}
