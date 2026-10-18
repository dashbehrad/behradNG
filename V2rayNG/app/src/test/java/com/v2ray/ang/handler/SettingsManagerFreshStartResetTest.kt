package com.v2ray.ang.handler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SettingsManagerFreshStartResetTest {
    @Test
    fun freshStartResetRunsOnlyOnce() {
        assertTrue(SettingsManager.shouldResetProfilesForFreshStart(false))
        assertFalse(SettingsManager.shouldResetProfilesForFreshStart(true))
    }

    @Test
    fun freshStartResetClearsOnlyProfileIndexRelatedMainKeys() {
        val keys = listOf(
            "SELECTED_SERVER",
            "ANG_CONFIGS",
            "SUB_SERVERS_default",
            "SUB_SERVERS_custom",
            "PREF_MODE",
            "SUB_IDS",
            "RANDOM_KEY",
        )
        assertEquals(
            listOf("SELECTED_SERVER", "ANG_CONFIGS", "SUB_SERVERS_default", "SUB_SERVERS_custom"),
            MmkvManager.FreshStartResetPlanner.mainKeysToClear(keys),
        )
    }
}
