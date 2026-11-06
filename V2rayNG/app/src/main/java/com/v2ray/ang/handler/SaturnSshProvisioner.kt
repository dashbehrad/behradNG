package com.v2ray.ang.handler

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.fmt.SaturnFmt
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.Properties

/**
 * Automates remote Linux VPS deployment of Saturn Server over SSH.
 */
object SaturnSshProvisioner {
    private const val TAG = "SaturnSshProvisioner"
    const val MARKER_START = "===SATURN_CONFIG_START==="
    const val MARKER_END = "===SATURN_CONFIG_END==="

    data class SshCredentials(
        val host: String,
        val port: Int = 22,
        val username: String = "root",
        val password: String? = null,
        val privateKey: String? = null,
        val passphrase: String? = null,
        val saturnPort: Int = 443,
        val camouflageSni: String = "dl.google.com"
    )

    sealed interface DeployEvent {
        data class Log(val message: String) : DeployEvent
        data class Progress(val stage: String, val percent: Int) : DeployEvent
        data class Success(val guid: String, val profile: ProfileItem, val uri: String) : DeployEvent
        data class Error(val message: String, val cause: Throwable? = null) : DeployEvent
    }

    fun buildExecutionCommand(creds: SshCredentials): String {
        val isRoot = creds.username.trim().equals("root", ignoreCase = true)
        val runner = "bash -s -- --auto --port ${creds.saturnPort} --sni ${creds.camouflageSni}"
        return if (isRoot) {
            runner
        } else {
            "sudo -E env DEBIAN_FRONTEND=noninteractive $runner"
        }
    }

    suspend fun deploy(
        creds: SshCredentials,
        scriptContent: String,
        onEvent: (DeployEvent) -> Unit
    ): Result<ProfileItem> = withContext(Dispatchers.IO) {
        var session: Session? = null
        var channel: ChannelExec? = null

        try {
            onEvent(DeployEvent.Progress("Connecting via SSH...", 10))
            onEvent(DeployEvent.Log("Connecting to ${creds.username}@${creds.host}:${creds.port}..."))

            val jsch = JSch()

            if (!creds.privateKey.isNullOrBlank()) {
                val keyBytes = creds.privateKey.trim().toByteArray(Charsets.UTF_8)
                val passBytes = creds.passphrase?.takeIf { it.isNotBlank() }?.toByteArray(Charsets.UTF_8)
                jsch.addIdentity("saturn-key", keyBytes, null, passBytes)
                onEvent(DeployEvent.Log("Loaded SSH private key authentication."))
            }

            session = jsch.getSession(creds.username, creds.host, creds.port).apply {
                if (!creds.password.isNullOrBlank()) {
                    setPassword(creds.password)
                }
                val config = Properties().apply {
                    put("StrictHostKeyChecking", "no")
                    put("PreferredAuthentications", "publickey,password,keyboard-interactive")
                }
                setConfig(config)
                timeout = 25000
                connect(25000)
            }

            onEvent(DeployEvent.Progress("Connected. Initializing Installer...", 25))
            onEvent(DeployEvent.Log("SSH session authenticated successfully."))

            val isRoot = creds.username.trim().equals("root", ignoreCase = true)
            if (!isRoot) {
                onEvent(DeployEvent.Log("Non-root user '${creds.username}' detected. Escalating privileges to root via 'sudo -i'..."))
            }

            // Run installer with script piped to bash (or escalated via sudo -i) in non-interactive batch mode
            channel = (session.openChannel("exec") as ChannelExec).apply {
                val cmd = buildExecutionCommand(creds)
                setCommand(cmd)
                setPty(false)
            }

            val outStream = channel.outputStream
            val inStream = channel.inputStream
            channel.connect(15000)

            // Pipe script into bash stdin
            OutputStreamWriter(outStream, Charsets.UTF_8).use { writer ->
                writer.write(scriptContent)
                writer.flush()
            }

            val reader = BufferedReader(InputStreamReader(inStream, Charsets.UTF_8))
            var capturingUri = false
            var capturedUri: String? = null

            reader.forEachLine { rawLine ->
                val cleanLine = rawLine.replace(Regex("\u001B\\[[0-9;?]*[a-zA-Z]"), "")
                    .replace(Regex("^\\[\\?[0-9]+[a-zA-Z]>?\\s*"), "")
                    .trim()
                val displayLine = cleanLine.replace(Regex("^([a-zA-Z0-9._-]+@[a-zA-Z0-9._-]+:[^#$]*[#$]\\s*)"), "").trim()
                if (displayLine.isNotEmpty() && !displayLine.startsWith("[?2004")) {
                    onEvent(DeployEvent.Log(displayLine))
                }

                val line = if (displayLine.isNotEmpty()) displayLine else cleanLine

                // Detect stages for user progress
                when {
                    line.contains("kernel and BBR", ignoreCase = true) ->
                        onEvent(DeployEvent.Progress("Optimizing Linux Kernel & BBR...", 40))
                    line.contains("saturn-server binary", ignoreCase = true) || line.contains("Deploying saturn-server", ignoreCase = true) ->
                        onEvent(DeployEvent.Progress("Installing Saturn Core...", 60))
                    line.contains("Generating Saturn X25519", ignoreCase = true) ->
                        onEvent(DeployEvent.Progress("Generating X25519 Keys & ShortID...", 75))
                    line.contains("systemd service", ignoreCase = true) || line.contains("firewall", ignoreCase = true) ->
                        onEvent(DeployEvent.Progress("Configuring Firewall & Systemd...", 88))
                    line.contains("Detecting server public IP", ignoreCase = true) ->
                        onEvent(DeployEvent.Progress("Verifying Server...", 95))
                }

                if (line.contains(MARKER_START)) {
                    capturingUri = true
                } else if (line.contains(MARKER_END)) {
                    capturingUri = false
                } else if (capturingUri && line.startsWith("saturn://")) {
                    capturedUri = line
                }
            }

            while (!channel.isClosed) {
                Thread.sleep(100)
            }

            val exitStatus = channel.exitStatus
            if (capturedUri.isNullOrBlank()) {
                val err = "Deployment finished with exit code $exitStatus but no Saturn config was generated."
                onEvent(DeployEvent.Error(err))
                return@withContext Result.failure(IllegalStateException(err))
            }

            onEvent(DeployEvent.Progress("Importing Profile...", 98))
            val profile = SaturnFmt.parse(capturedUri!!)
                ?: throw IllegalStateException("Failed to parse generated Saturn URI: $capturedUri")

            val remarks = "Saturn-${creds.host}"
            val finalProfile = profile.copy(remarks = remarks)
            val guid = MmkvManager.encodeServerConfig("", finalProfile)
            if (guid.isNotBlank()) {
                MmkvManager.setSelectServer(guid)
            }

            onEvent(DeployEvent.Progress("Saturn Ready!", 100))
            onEvent(DeployEvent.Success(guid, finalProfile, capturedUri!!))
            Result.success(finalProfile)
        } catch (t: Throwable) {
            LogUtil.e(TAG, "SSH Deployment failed", t)
            onEvent(DeployEvent.Error(t.localizedMessage ?: "SSH Deployment failed", t))
            Result.failure(t)
        } finally {
            try {
                channel?.disconnect()
            } catch (_: Exception) {}
            try {
                session?.disconnect()
            } catch (_: Exception) {}
        }
    }
}
