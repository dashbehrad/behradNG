package com.v2ray.ang.ui.server

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SaturnDeployViewModelTest {
    @Test
    fun initialStateHasExpectedDefaults() {
        val vm = SaturnDeployViewModel()
        val state = vm.uiState.value

        assertEquals("", state.host)
        assertEquals("22", state.port)
        assertEquals("root", state.username)
        assertEquals(SshAuthMethod.PASSWORD, state.authMethod)
        assertEquals("", state.password)
        assertEquals("", state.privateKey)
        assertEquals("443", state.saturnPort)
        assertEquals("dl.google.com", state.camouflageSni)
        assertFalse(state.isDeploying)
        assertFalse(state.isFinished)
        assertNull(state.error)
    }

    @Test
    fun stateUpdatesCorrectly() {
        val vm = SaturnDeployViewModel()

        vm.updateHost("198.51.100.10")
        vm.updatePort("2222")
        vm.updateUsername("admin")
        vm.updateAuthMethod(SshAuthMethod.PRIVATE_KEY)
        vm.updatePrivateKey("-----BEGIN RSA PRIVATE KEY-----")
        vm.updateSaturnPort("8443")
        vm.updateCamouflageSni("www.microsoft.com")

        val state = vm.uiState.value
        assertEquals("198.51.100.10", state.host)
        assertEquals("2222", state.port)
        assertEquals("admin", state.username)
        assertEquals(SshAuthMethod.PRIVATE_KEY, state.authMethod)
        assertEquals("-----BEGIN RSA PRIVATE KEY-----", state.privateKey)
        assertEquals("8443", state.saturnPort)
        assertEquals("www.microsoft.com", state.camouflageSni)
    }

    @Test
    fun validationRejectsBlankHost() {
        val vm = SaturnDeployViewModel()
        vm.updateHost("")
        vm.startDeploy("echo test")

        val state = vm.uiState.value
        assertFalse(state.isDeploying)
        assertNotNull(state.error)
        assertTrue(state.error!!.contains("Server IP", ignoreCase = true))
    }

    @Test
    fun validationRejectsBlankPasswordWhenPasswordAuthSelected() {
        val vm = SaturnDeployViewModel()
        vm.updateHost("198.51.100.1")
        vm.updateAuthMethod(SshAuthMethod.PASSWORD)
        vm.updatePassword("")
        vm.startDeploy("echo test")

        val state = vm.uiState.value
        assertFalse(state.isDeploying)
        assertNotNull(state.error)
        assertTrue(state.error!!.contains("Password is required", ignoreCase = true))
    }

    @Test
    fun validationRejectsBlankPrivateKeyWhenKeyAuthSelected() {
        val vm = SaturnDeployViewModel()
        vm.updateHost("198.51.100.1")
        vm.updateAuthMethod(SshAuthMethod.PRIVATE_KEY)
        vm.updatePrivateKey("")
        vm.startDeploy("echo test")

        val state = vm.uiState.value
        assertFalse(state.isDeploying)
        assertNotNull(state.error)
        assertTrue(state.error!!.contains("Private Key is required", ignoreCase = true))
    }

    @Test
    fun cancelDeployResetsDeployingFlag() {
        val vm = SaturnDeployViewModel()
        vm.cancelDeploy()
        assertFalse(vm.uiState.value.isDeploying)
    }
}
