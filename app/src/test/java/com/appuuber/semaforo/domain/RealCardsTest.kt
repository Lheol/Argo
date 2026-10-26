package com.appuuber.semaforo.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes baseados em 3 cards REAIS (capturas de tela enviadas pelo usuário):
 *  1) Uber X "Selecionar" com 1 parada, perna de embarque/viagem sem prefixo "Viagem de".
 *  2) Uber Comfort Exclusivo com 1 parada, "de distância" + "Viagem de 26 minutos".
 *  3) 99 "Corrida longa" (Dinheiro) com taxa de deslocamento, "1 parada(s)" e sem "R$ " com espaço.
 */
class RealCardsTest {

    private val t = DriverThresholds()

    private val uberXSelecionar = listOf(
        "UberX", "R$ 15,19", "★ 4,95 (283)",
        "13 min (3.8 km)", "Travessa Honório José dos Santos, Jurunas, Belém",
        "1 parada", "19 minutos (5.4 km)", "Av. Alcindo Cacela, 2144, Nazaré, Belém",
        "Selecionar"
    )

    private val comfortExclusivo = listOf(
        "Comfort", "Exclusivo", "R$ 18,59", "★ 4,92 (2309)", "Verificado",
        "2 minutos (0,5 km) de distância", "Rua Fúlvia, Florianópolis",
        "1 parada", "Viagem de 26 minutos (7.9 km)",
        "R. Adolfo Donato da Silva, S/N - Praia Comprida, São José - SC, 88103-901, Brasil",
        "Aceitar"
    )

    private val noveCorridaLonga = listOf(
        "Corrida longa", "Dinheiro", "R$16,20", "Taxa de deslocamento R$1,11",
        "4,93 · 419 corridas", "Perfil Premium",
        "8min (3,3km)", "Terceira Rua, Rio do Ouro, Rio Bonito - RJ",
        "1 parada(s)", "22min (9,2km)", "Rua Maj. Bezerra Cavalcante, 651, Serra do..."
    )

    @Test
    fun `card real Uber X Selecionar com 1 parada`() {
        val r = RideOfferParser.parseScreenNodes(uberXSelecionar, t)
        assertNotNull(r)
        r!!
        assertEquals(15.19, r.grossPrice, 0.001)
        assertEquals(3.8, r.pickupDistanceKm, 0.001)
        assertEquals(5.4, r.tripDistanceKm, 0.001)
        assertEquals(9.2, r.totalDistanceKm, 0.001)
        assertEquals(32, r.totalDurationMin)
        assertEquals(4.95, r.passengerRating!!, 0.001)
        assertEquals(1, r.stopCount)
        assertTrue(r.isRadarOffer)
        assertEquals("Uber", r.appSource)
    }

    @Test
    fun `card real Comfort Exclusivo com 1 parada`() {
        val r = RideOfferParser.parseScreenNodes(comfortExclusivo, t)
        assertNotNull(r)
        r!!
        assertEquals(18.59, r.grossPrice, 0.001)
        assertEquals(0.5, r.pickupDistanceKm, 0.001)
        assertEquals(7.9, r.tripDistanceKm, 0.001)
        assertEquals(28, r.totalDurationMin)
        assertEquals(4.92, r.passengerRating!!, 0.001)
        assertEquals(1, r.stopCount)
        assertEquals("Comfort", r.category)
        assertEquals("Uber", r.appSource)
    }

    @Test
    fun `card real 99 Corrida longa com taxa de deslocamento e parada(s)`() {
        val r = RideOfferParser.parseScreenNodes(noveCorridaLonga, t)
        assertNotNull(r)
        r!!
        assertEquals(16.20, r.grossPrice, 0.001) // taxa R$1,11 nao e o preco
        assertEquals(3.3, r.pickupDistanceKm, 0.001)
        assertEquals(9.2, r.tripDistanceKm, 0.001)
        assertEquals(12.5, r.totalDistanceKm, 0.001)
        assertEquals(30, r.totalDurationMin)
        assertEquals(4.93, r.passengerRating!!, 0.001)
        assertEquals(1, r.stopCount)
        assertEquals("99", r.appSource)
    }

    @Test
    fun `preco da Uber terminado em 99 nao e confundido com o app 99`() {
        val nodes = uberXSelecionar.map { it.replace("R$ 15,19", "R$ 15,99") }
        val r = RideOfferParser.parseScreenNodes(nodes, t)!!
        assertEquals(15.99, r.grossPrice, 0.001)
        assertEquals("Uber", r.appSource)
    }

    @Test
    fun `view id do pacote 99 identifica o app mesmo sem texto distintivo`() {
        val r = RideOfferParser.parseScreenNodes(
            uberXSelecionar, t, viewIds = listOf("com.app99.driver:id/broad_order_container")
        )!!
        assertEquals("99", r.appSource)
    }

    @Test
    fun `cards reais continuam validos com NBSP e nos separados por palavra`() {
        val nbsp = noveCorridaLonga.map { it.replace(" ", "\u00A0") }
        val r = RideOfferParser.parseScreenNodes(nbsp, t)
        assertNotNull(r)
        assertEquals(16.20, r!!.grossPrice, 0.001)
    }

    @Test
    fun `cards reais embaralhados na ordem dos nos dao o mesmo resultado`() {
        val rnd = java.util.Random(7)
        listOf(uberXSelecionar, comfortExclusivo, noveCorridaLonga).forEach { card ->
            val base = RideOfferParser.parseScreenNodes(card, t)!!
            repeat(300) {
                val shuffled = card.shuffled(rnd)
                val r = RideOfferParser.parseScreenNodes(shuffled, t)
                assertNotNull(r)
                assertEquals(base.grossPrice, r!!.grossPrice, 0.001)
                assertEquals(base.stopCount, r.stopCount)
            }
        }
    }

    @Test
    fun `telas de mapa e sem oferta nao disparam o semaforo`() {
        val mapa = listOf("Não afeta a TA", "Saqua Radical", "Você está online", "R$ 150,00 hoje")
        assertNull(RideOfferParser.parseScreenNodes(mapa, t))
    }

    @Test
    fun `cards reais classificam e rotulam paradas no semaforo`() {
        val r = RideOfferParser.parseScreenNodes(uberXSelecionar, t)!!
        // 15,19 / 9,2km = 1,65/km ; 15,19/32min = 28,5/h -> acima do corte amarelo (27,2/h) => AMARELO
        assertEquals(SemaphoreStatus.YELLOW, r.status)
        assertEquals(
            "TEM POTENCIAL (paradas 1)",
            com.appuuber.semaforo.overlay.FloatingSemaphoreManager.statusLabelWithStops(r)
        )
    }
}
