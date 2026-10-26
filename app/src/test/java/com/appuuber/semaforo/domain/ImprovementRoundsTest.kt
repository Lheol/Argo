package com.appuuber.semaforo.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImprovementRoundsTest {

    // ---------- Rodada 1: metas sempre coerentes ----------

    @Test
    fun `metas com NaN negativo ou amarelo maior que verde sao saneadas`() {
        val bad = DriverThresholds(
            greenMinPerKm = 1.0, yellowMinPerKm = 3.0,
            greenMinPerHour = Double.NaN, yellowMinPerHour = -5.0,
            costPerKm = Double.POSITIVE_INFINITY
        ).sanitized()
        assertTrue(bad.greenMinPerKm >= bad.yellowMinPerKm)
        assertTrue(bad.greenMinPerHour >= bad.yellowMinPerHour)
        assertTrue(bad.yellowMinPerHour >= 0.0)
        assertTrue(bad.costPerKm.isFinite() && bad.costPerKm >= 0.0)
    }

    @Test
    fun `metas validas permanecem inalteradas`() {
        val ok = DriverThresholds(2.0, 1.5, 45.0, 32.0, 0.75)
        assertEquals(ok, ok.sanitized())
    }

    // ---------- Rodada 2: texto real com espacos especiais e milhar ----------

    private val t = DriverThresholds()

    @Test
    fun `espaco nao separavel NBSP nao quebra o parser`() {
        val nodes = listOf(
            "UberX", "R$\u00A025,00", "3\u00A0min\u00A0(1,2\u00A0km)\u00A0até o passageiro",
            "Viagem de 17\u00A0min (8,8\u00A0km)", "Aceitar"
        )
        val r = RideOfferParser.parseScreenNodes(nodes, t)
        assertNotNull(r)
        assertEquals(25.0, r!!.grossPrice, 0.01)
        assertEquals(10.0, r.totalDistanceKm, 0.01)
    }

    @Test
    fun `preco com separador de milhar e lido por inteiro`() {
        val nodes = listOf(
            "Black", "R$ 1.250,00", "10 min (4,0 km) de distância",
            "Viagem de 120 min (150,0 km)", "Aceitar"
        )
        val r = RideOfferParser.parseScreenNodes(nodes, t)
        assertNotNull(r)
        assertEquals(1250.0, r!!.grossPrice, 0.01)
    }

    // ---------- Rodada 3: falsos positivos de metadados ----------

    @Test
    fun `texto generico com a palavra parada nao marca multiplas paradas`() {
        val nodes = listOf(
            "UberX", "R$ 20,00", "Ponto de parada do passageiro",
            "3 min (1,0 km) até o passageiro", "Viagem de 15 min (7,0 km)", "Aceitar"
        )
        val r = RideOfferParser.parseScreenNodes(nodes, t)
        assertNotNull(r)
        assertEquals(false, r!!.hasMultipleStops)
    }

    @Test
    fun `parada extra explicita continua detectada`() {
        val nodes = listOf(
            "UberX", "R$ 30,00", "2 paradas", "3 min (1,0 km) até o passageiro",
            "Viagem de 25 min (12,0 km)", "Aceitar"
        )
        val r = RideOfferParser.parseScreenNodes(nodes, t)
        assertNotNull(r)
        assertEquals(true, r!!.hasMultipleStops)
    }

    @Test
    fun `parseMoney cobre virgula ponto e milhar`() {
        assertEquals(1250.0, RideOfferParser.parseMoney("1.250,00")!!, 0.001)
        assertEquals(12.5, RideOfferParser.parseMoney("12,50")!!, 0.001)
        assertEquals(12.5, RideOfferParser.parseMoney("12.50")!!, 0.001)
    }

    @Test
    fun `contagem de paradas e rotulo do semaforo`() {
        assertEquals(0, RideOfferParser.countStops("Ponto de parada do passageiro"))
        assertEquals(1, RideOfferParser.countStops("1 parada"))
        assertEquals(3, RideOfferParser.countStops("3 paradas"))
        assertEquals(2, RideOfferParser.countStops("Várias paradas"))
        assertEquals(1, RideOfferParser.countStops("Parada extra"))

        val nodes = listOf(
            "UberX", "R$ 40,00", "3 paradas", "3 min (1,0 km) até o passageiro",
            "Viagem de 20 min (10,0 km)", "Aceitar"
        )
        val r = RideOfferParser.parseScreenNodes(nodes, t)!!
        assertEquals(3, r.stopCount)
        assertEquals(
            "${r.status.label} (paradas 3)",
            com.appuuber.semaforo.overlay.FloatingSemaphoreManager.statusLabelWithStops(r)
        )
        val semParadas = RideOfferParser.parseScreenNodes(
            listOf("UberX", "R$ 25,00", "3 min (1,2 km) até o passageiro", "Viagem de 17 min (8,8 km)", "Aceitar"), t
        )!!
        assertEquals(semParadas.status.label,
            com.appuuber.semaforo.overlay.FloatingSemaphoreManager.statusLabelWithStops(semParadas))
    }

    @Test
    fun `nota de passageiro fora de 1 a 5 e ignorada e nota valida lida`() {
        val nodes = listOf(
            "UberX", "R$ 25,00", "★ 4,92", "3 min (1,2 km) até o passageiro",
            "Viagem de 17 min (8,8 km)", "Aceitar"
        )
        assertEquals(4.92, RideOfferParser.parseScreenNodes(nodes, t)!!.passengerRating!!, 0.001)
    }
}
