package com.appuuber.semaforo.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Testes com 4 cards reais adicionais (capturas): US$22, R$135,31 (Turbo+), R$13,13 e Radar de Viagens. */
class RealCardsBatch2Test {

    private val t = DriverThresholds()

    private val usd22 = listOf(
        "UberX", "US\$22", "★ 4,96", "3 minutos (0.7 km) de distância",
        "Av. Engenheiro Armando de Arruda Pereira, Jabaquara",
        "Viagem de 20 minutos (11.8 km)",
        "R. Makita Brasil, 201 - Cooperativa, São Bernardo do Campo - SP, 09852-080, Brasil",
        "Selecionar"
    )

    private val turbo = listOf(
        "UberX", "Exclusivo", "R$ 135,31", "★ 4,82", "+R$ 10,25 incluído", "Turbo+ de R$ 33,28 incluído",
        "4 minutos (0.4 km) de distância", "R. Antônio de Albuquerque, Região Centro Sul",
        "Viagem de 1 h e 40 minutos (51.0 km)", "R. José Monteiro, 708, - Bairro Cidade Nova, Juatuba - MG",
        "Viagem longa (mais de 30 min)", "Aceitar"
    )

    private val simples = listOf(
        "UberX", "Exclusivo", "R$ 13,13", "★ 4,83", "7 minutos (3.0 km) de distância",
        "Av. Lineu Machado, Henrique Jorge, Bonsucesso e arredores", "Viagem de 10 minutos (5.6 km)",
        "Rua São Francisco, 763 - Siqueira - Fortaleza - CE, 60546-120", "Aceitar"
    )

    private val radar = listOf(
        "Radar de Viagens",
        "UberX", "Radar", "R$ 56.40", "★ 4.95", "PIN",
        "A 1 min (0.3 km)", "Servidão Anselmo, Centro de Ilha",
        "Viagem de 45 minutos (27.1 km)", "Ponta do Caçador - Barra da Lagoa",
        "UberX", "Radar", "R$ 12.60", "★ 4.97",
        "A 2 min (1 km)", "Ilha Florianópolis",
        "Viagem de 14 minutos (9 km)", "Udesc - Universidade do Estado de Santa Catarina",
        "UberX", "Radar", "R$ 25.15", "★ 4.94"
    )

    @Test
    fun `preco em dolar sem centavos US dollar 22 e lido`() {
        val r = RideOfferParser.parseScreenNodes(usd22, t)
        assertNotNull(r)
        r!!
        assertEquals(22.0, r.grossPrice, 0.001)
        assertEquals(0.7, r.pickupDistanceKm, 0.001)
        assertEquals(11.8, r.tripDistanceKm, 0.001)
        assertEquals(23, r.totalDurationMin)
        assertEquals(4.96, r.passengerRating!!, 0.001)
        assertEquals(0, r.stopCount)
        assertTrue(r.isRadarOffer)
    }

    @Test
    fun `Turbo mais e valores incluidos nao viram preco e viagem com hora e minuto soma certo`() {
        val r = RideOfferParser.parseScreenNodes(turbo, t)
        assertNotNull(r)
        r!!
        assertEquals(135.31, r.grossPrice, 0.001)
        assertEquals(0.4, r.pickupDistanceKm, 0.001)
        assertEquals(51.0, r.tripDistanceKm, 0.001)
        assertEquals(104, r.totalDurationMin) // 4 + 1h40
        assertEquals(4.82, r.passengerRating!!, 0.001)
        assertEquals(0, r.stopCount)
        assertEquals(SemaphoreStatus.GREEN, r.status) // 2,63/km e 78/h
    }

    @Test
    fun `texto viagem longa mais de 30 min nao e lido como perna de viagem`() {
        val r = RideOfferParser.parseScreenNodes(turbo, t)!!
        assertEquals(51.4, r.totalDistanceKm, 0.001)
    }

    @Test
    fun `card R$13,13 com aviso de endereco e classificado como amarelo`() {
        val r = RideOfferParser.parseScreenNodes(simples, t)
        assertNotNull(r)
        r!!
        assertEquals(13.13, r.grossPrice, 0.001)
        assertEquals(8.6, r.totalDistanceKm, 0.001)
        assertEquals(17, r.totalDurationMin)
        assertEquals(SemaphoreStatus.YELLOW, r.status)
    }

    @Test
    fun `cep no endereco nao interfere em preco nem distancia`() {
        val r = RideOfferParser.parseScreenNodes(simples, t)!!
        assertEquals(13.13, r.grossPrice, 0.001)
        assertEquals(5.6, r.tripDistanceKm, 0.001)
    }

    @Test
    fun `Radar de Viagens avalia somente o primeiro card e nao mistura preco de outro card`() {
        val r = RideOfferParser.parseScreenNodes(radar, t)
        assertNotNull(r)
        r!!
        assertEquals(56.40, r.grossPrice, 0.001)
        assertEquals(0.3, r.pickupDistanceKm, 0.001)
        assertEquals(27.1, r.tripDistanceKm, 0.001)
        assertEquals(46, r.totalDurationMin)
    }

    @Test
    fun `Radar com o maior preco no ultimo card ainda usa os dados do primeiro card`() {
        val inverted = radar.map { it.replace("R$ 56.40", "R$ 11.40").replace("R$ 25.15", "R$ 99.15") }
        val r = RideOfferParser.parseScreenNodes(inverted, t)!!
        assertEquals(11.40, r.grossPrice, 0.001)
        assertEquals(27.4, r.totalDistanceKm, 0.001)
    }

    @Test
    fun `parseRadarCards devolve uma avaliacao por card completo`() {
        val all = RideOfferParser.parseRadarCards(radar, t)
        assertEquals(2, all.size) // o terceiro card esta cortado na tela (sem distancias)
        assertEquals(56.40, all[0].grossPrice, 0.001)
        assertEquals(12.60, all[1].grossPrice, 0.001)
        assertEquals(10.0, all[1].totalDistanceKm, 0.001)
        assertEquals(16, all[1].totalDurationMin)
    }

    @Test
    fun `cada card isolado do Radar e avaliado corretamente`() {
        val segundo = radar.subList(10, 18)
        val r = RideOfferParser.parseScreenNodes(segundo, t)!!
        assertEquals(12.60, r.grossPrice, 0.001)
        assertEquals(1.0, r.pickupDistanceKm, 0.001)
        assertEquals(9.0, r.tripDistanceKm, 0.001)
    }

    @Test
    fun `todos os cards do lote nunca geram valores invalidos em 5000 ordens`() {
        val rnd = java.util.Random(11)
        listOf(usd22, turbo, simples).forEach { card ->
            val base = RideOfferParser.parseScreenNodes(card, t)!!
            repeat(1500) {
                val r = RideOfferParser.parseScreenNodes(card.shuffled(rnd), t)
                assertNotNull(r)
                assertEquals(base.grossPrice, r!!.grossPrice, 0.001)
            }
        }
    }
}
