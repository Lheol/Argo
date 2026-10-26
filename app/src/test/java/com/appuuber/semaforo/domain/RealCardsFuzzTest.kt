package com.appuuber.semaforo.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** Testes de robustez (fuzz / mutação / propriedades) sobre os cards reais. */
class RealCardsFuzzTest {

    private val t = DriverThresholds()
    private val rnd = Random(2026)

    private val cards = listOf(
        listOf("UberX", "R$ 15,19", "★ 4,95 (283)", "13 min (3.8 km)", "Travessa Honório José dos Santos, Jurunas, Belém",
            "1 parada", "19 minutos (5.4 km)", "Av. Alcindo Cacela, 2144, Nazaré, Belém", "Selecionar"),
        listOf("Comfort", "Exclusivo", "R$ 18,59", "★ 4,92 (2309)", "Verificado", "2 minutos (0,5 km) de distância",
            "Rua Fúlvia, Florianópolis", "1 parada", "Viagem de 26 minutos (7.9 km)", "88103-901, Brasil", "Aceitar"),
        listOf("Corrida longa", "Dinheiro", "R$16,20", "Taxa de deslocamento R$1,11", "4,93 · 419 corridas",
            "Perfil Premium", "8min (3,3km)", "Terceira Rua, Rio do Ouro, Rio Bonito - RJ", "1 parada(s)",
            "22min (9,2km)", "Rua Maj. Bezerra Cavalcante, 651, Serra do...")
    )
    private val prices = listOf(15.19, 18.59, 16.20)

    private val noise = listOf(
        "Não afeta a TA", "Saqua Radical", "Offline", "×", "Ganhos de hoje", "R$ 0,00", "12:34",
        "Menu", "Rua das Flores, 100", "Av. Brasil 99", "Promoção 2x", "Lojas Americanas 10 km"
    )

    @Test
    fun `ruido aleatorio entre os nos do card nao muda preco nem paradas`() {
        cards.forEachIndexed { idx, card ->
            repeat(2000) {
                val nodes = card.toMutableList()
                repeat(rnd.nextInt(4)) { nodes.add(rnd.nextInt(nodes.size + 1), noise[rnd.nextInt(noise.size)]) }
                val r = RideOfferParser.parseScreenNodes(nodes, t)
                assertNotNull("card $idx falhou com $nodes", r)
                assertEquals("card $idx preco com $nodes", prices[idx], r!!.grossPrice, 0.001)
                assertEquals("card $idx paradas", 1, r.stopCount)
            }
        }
    }

    @Test
    fun `entradas aleatorias de lixo nunca lancam excecao`() {
        val chars = "abcR\$ 0123456789,.()|-·★ kmin\u00A0parada\n\t"
        repeat(20000) {
            val nodes = List(rnd.nextInt(8)) {
                buildString { repeat(rnd.nextInt(40)) { append(chars[rnd.nextInt(chars.length)]) } }
            }
            val r = RideOfferParser.parseScreenNodes(nodes, t)
            if (r != null) {
                assertTrue(r.earningsPerKm.isFinite() && r.earningsPerHour.isFinite() && r.netProfit.isFinite())
                assertTrue(r.stopCount in 0..9)
            }
        }
    }

    @Test
    fun `texto truncado do card nunca gera valores invalidos`() {
        cards.forEach { card ->
            val full = card.joinToString(" | ")
            for (end in 1..full.length) {
                val r = RideOfferParser.parseScreenNodes(listOf(full.substring(0, end)), t)
                if (r != null) {
                    assertTrue(r.totalDistanceKm >= 0.1)
                    assertTrue(r.totalDurationMin >= 1)
                    assertTrue(r.grossPrice > 0.0)
                }
            }
        }
    }

    @Test
    fun `mesmo card gera sempre a mesma assinatura e o mesmo semaforo`() {
        cards.forEach { card ->
            val a = RideOfferParser.parseScreenNodes(card, t)!!
            val b = RideOfferParser.parseScreenNodes(card, t)!!
            assertEquals(a.rawSignature, b.rawSignature)
            assertEquals(a.status, b.status)
        }
    }

    @Test
    fun `metas mais exigentes nunca deixam um card mais verde`() {
        val order = listOf(SemaphoreStatus.RED, SemaphoreStatus.YELLOW, SemaphoreStatus.GREEN)
        cards.forEach { card ->
            var prev = order.indexOf(RideOfferParser.parseScreenNodes(card, DriverThresholds(0.5, 0.2, 5.0, 3.0, 0.5))!!.status)
            for (k in 1..30) {
                val th = DriverThresholds(0.5 + k * 0.2, 0.2 + k * 0.15, 5.0 + k * 3, 3.0 + k * 2, 0.5)
                val cur = order.indexOf(RideOfferParser.parseScreenNodes(card, th)!!.status)
                assertTrue("semaforo ficou mais verde com meta maior", cur <= prev)
                prev = cur
            }
        }
    }
}
