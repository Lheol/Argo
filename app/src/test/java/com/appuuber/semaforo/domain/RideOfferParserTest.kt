package com.appuuber.semaforo.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RideOfferParserTest {

    private val defaultThresholds = DriverThresholds(
        greenMinPerKm = 2.00,
        yellowMinPerKm = 1.50,
        greenMinPerHour = 45.0,
        yellowMinPerHour = 32.0,
        costPerKm = 0.75
    )

    @Test
    fun `deve classificar como VERDE corrida que paga acima de R$2 por km e R$45 por hora`() {
        val nodes = listOf(
            "UberX",
            "Exclusivo",
            "R$ 25,00",
            "3 min (1,2 km) até o passageiro",
            "Viagem de 17 min (8,8 km)",
            "Aceitar"
        )

        val result = RideOfferParser.parseScreenNodes(nodes, defaultThresholds)
        assertNotNull(result)
        result!!

        // Distância total = 1.2 + 8.8 = 10.0 km -> R$ 2,50/km
        // Tempo total = 3 + 17 = 20 min -> R$ 75,00/h
        assertEquals(25.00, result.grossPrice, 0.01)
        assertEquals(10.0, result.totalDistanceKm, 0.01)
        assertEquals(20, result.totalDurationMin)
        assertEquals(2.50, result.earningsPerKm, 0.01)
        assertEquals(75.00, result.earningsPerHour, 0.01)
        assertEquals(17.50, result.netProfit, 0.01) // 25.00 - (10 * 0.75)
        assertEquals(SemaphoreStatus.GREEN, result.status)
    }

    @Test
    fun `deve classificar como AMARELO corrida intermediaria com potencial`() {
        val nodes = listOf(
            "UberX",
            "R$ 16,50",
            "4 min (1,5 km)",
            "Viagem de 21 min (8,5 km)",
            "Aceitar"
        )

        val result = RideOfferParser.parseScreenNodes(nodes, defaultThresholds)
        assertNotNull(result)
        result!!

        // Distância total = 10.0 km -> R$ 1,65/km (>= 1.50 e < 2.00)
        // Tempo total = 25 min -> R$ 39,60/h (>= 32.00 e < 45.00)
        assertEquals(1.65, result.earningsPerKm, 0.01)
        assertEquals(39.60, result.earningsPerHour, 0.01)
        assertEquals(SemaphoreStatus.YELLOW, result.status)
    }

    @Test
    fun `deve classificar como VERMELHO corrida pagando mal`() {
        val nodes = listOf(
            "UberX Poupa",
            "R$ 9,50",
            "8 min (3,0 km) de distância",
            "Viagem de 22 min (7,0 km)",
            "Aceitar"
        )

        val result = RideOfferParser.parseScreenNodes(nodes, defaultThresholds)
        assertNotNull(result)
        result!!

        // Distância total = 10.0 km -> R$ 0,95/km
        // Tempo total = 30 min -> R$ 19,00/h
        assertEquals(0.95, result.earningsPerKm, 0.01)
        assertEquals(19.00, result.earningsPerHour, 0.01)
        assertEquals(SemaphoreStatus.RED, result.status)
    }

    @Test
    fun `deve ignorar tarifa por km sem centavos e perna separada por espaco (Rota Pro 1_1_66)`() {
        val nodes = listOf(
            "UberX",
            "R$ 22,00",
            "R$ 2,2/km",
            "4 min 1.5 km",
            "Viagem de 16 min (8,5 km)",
            "Aceitar"
        )
        val result = RideOfferParser.parseScreenNodes(nodes, defaultThresholds)
        assertNotNull(result)
        assertEquals(22.00, result!!.grossPrice, 0.01)
    }

    @Test
    fun `nao deve processar pacotes fora da allowlist`() {
        val allowed = com.appuuber.semaforo.service.UberAccessibilityService.ALLOWED_PACKAGES
        assertEquals(
            setOf("com.ubercab.driver", "com.app99.driver", "sinet.startup.inDriver"),
            allowed
        )
    }

    @Test
    fun `deve ignorar telas que nao sao chamadas de corrida`() {
        val randomScreenNodes = listOf(
            "Configurações da Conta",
            "Saldo disponível R$ 150,00",
            "Sair do aplicativo"
        )

        val result = RideOfferParser.parseScreenNodes(randomScreenNodes, defaultThresholds)
        assertNull(result)
    }
}
