package com.v2ray.ang.ui.main

import com.v2ray.ang.dto.entities.AetherSettings
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.SaturnSettings
import com.v2ray.ang.dto.entities.ServersCache
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MainServerRowModelsTest {
    @Test
    fun saturnProfilesDisplayCarrierInServerRowUiModel() {
        val h2Saturn = ProfileItem.create(EConfigType.SATURN).copy(
            remarks = "Saturn H2",
            saturnSettings = SaturnSettings(carrier = "h2"),
        )
        val h3Saturn = ProfileItem.create(EConfigType.SATURN).copy(
            remarks = "Saturn H3",
            saturnSettings = SaturnSettings(carrier = "h3"),
        )
        val defaultSaturn = ProfileItem.create(EConfigType.SATURN).copy(
            remarks = "Saturn Default",
        )
        val h2Model = buildServerRowUiModel(ServersCache("guid-1", h2Saturn), "")
        val h3Model = buildServerRowUiModel(ServersCache("guid-2", h3Saturn), "")
        val defaultModel = buildServerRowUiModel(ServersCache("guid-3", defaultSaturn), "")

        assertEquals("SATURN / h2", h2Model.typeDescription)
        assertEquals("SATURN / h3", h3Model.typeDescription)
        assertEquals("SATURN / h2", defaultModel.typeDescription)
    }

    @Test
    fun aetherProfilesDisplayCarrierInServerRowUiModel() {
        val h3Aether = ProfileItem.create(EConfigType.AETHER).copy(
            remarks = "Aether H3",
            aetherSettings = AetherSettings(carrier = "h3"),
        )
        val h3Model = buildServerRowUiModel(ServersCache("guid-4", h3Aether), "")
        assertEquals("AETHER / h3", h3Model.typeDescription)
    }
}

