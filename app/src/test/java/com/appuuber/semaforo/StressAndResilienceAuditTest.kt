package com.appuuber.semaforo

import com.appuuber.semaforo.data.SensitiveAppPolicy
import com.appuuber.semaforo.data.TargetApps
import com.appuuber.semaforo.domain.DriverThresholds
import com.appuuber.semaforo.domain.HealthInputs
import com.appuuber.semaforo.domain.HealthLevel
import com.appuuber.semaforo.domain.RideOfferParser
import com.appuuber.semaforo.domain.SemaphoreStatus
import com.appuuber.semaforo.domain.SystemHealthEvaluator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Suite Massiva de Teste de Estresse, Auditoria e Resiliência (1.000 a 2.000 Ciclos Consecutivos).
 * Executa simulações contínuas de segurança anti-block (foco em isolamento BRB e bancos),
 * estabilidade aritmética (zero crashes, NaN ou Infinity) e robustez do algoritmo de parsing.
 */
class StressAndResilienceAuditTest {

    private val random = Random(42L)

    @Test
    fun `ciclo massivo de 2000 iteracoes - auditoria de seguranca anti-block e isolamento financeiro`() {
        val knownFinancialPackages = listOf(
            "br.com.brb.digital",
            "br.com.brb.mobile",
            "com.nu.production",
            "com.itau",
            "com.itau.personnalite",
            "br.com.bb.android",
            "com.bancobradesco.cartoes",
            "com.santander.app",
            "br.com.intermedium",
            "com.c6bank.app",
            "com.mercadopago.wallet",
            "br.com.caixa.mobi",
            "com.picpay"
        )

        val supportedRidePackages = listOf(
            "com.ubercab.driver",
            "com.app99.driver",
            "sinet.startup.inDriver"
        )

        for (cycle in 1..20000) {
            // Teste 1: Garantir que absolutamente nenhum pacote financeiro conhecido consiga furar a barreira
            val targetBank = knownFinancialPackages[random.nextInt(knownFinancialPackages.size)]
            assertTrue(
                "Falha de segurança no ciclo $cycle: Banco $targetBank deve ser identificado como sensível",
                SensitiveAppPolicy.isSensitive(targetBank)
            )
            assertFalse(
                "Falha de segurança no ciclo $cycle: Banco $targetBank jamais pode ser selecionável",
                TargetApps.isSelectable(targetBank)
            )

            // Teste 2: Mutações e permutações de nomes de pacotes bancários com ruídos
            val mutatedBank = targetBank + if (random.nextBoolean()) ".submodule" else "_test_${cycle}"
            assertTrue(
                "Falha na mutação no ciclo $cycle: $mutatedBank deve permanecer sensível",
                SensitiveAppPolicy.isSensitive(mutatedBank)
            )
            assertFalse(
                "Falha na mutação no ciclo $cycle: $mutatedBank jamais pode ser selecionável",
                TargetApps.isSelectable(mutatedBank)
            )

            // Teste 3: Garantir que apenas pacotes estritamente catalogados como corrida sejam aceitos
            val randomGenericPkg = "com.random.app.p${random.nextInt(10000)}"
            assertFalse(
                "Ciclo $cycle: Pacote aleatório $randomGenericPkg não pode ser selecionável",
                TargetApps.isSelectable(randomGenericPkg)
            )

            // Teste 4: Validar pacotes de corrida legítimos
            val validRide = supportedRidePackages[random.nextInt(supportedRidePackages.size)]
            assertTrue(
                "Ciclo $cycle: App de corrida legítimo $validRide deve ser selecionável",
                TargetApps.isSelectable(validRide)
            )
            assertFalse(
                "Ciclo $cycle: App de corrida não pode ser marcado como banco/sensível",
                SensitiveAppPolicy.isSensitive(validRide)
            )
        }
    }

    @Test
    fun `ciclo massivo de 2000 iteracoes - stress-test matematico de metricas e semaforo`() {
        val thresholds = DriverThresholds(
            greenMinPerKm = 2.00,
            yellowMinPerKm = 1.50,
            greenMinPerHour = 45.0,
            yellowMinPerHour = 32.0,
            costPerKm = 0.75
        )

        for (cycle in 1..20000) {
            // Gera valores estressantes: desde números minúsculos até milhões, além de zeros e limites
            val grossPrice = when (cycle % 5) {
                0 -> random.nextDouble() * 10.0 // 0 a 10
                1 -> 10.0 + random.nextDouble() * 100.0 // 10 a 110
                2 -> 100.0 + random.nextDouble() * 1000.0 // 100 a 1100
                3 -> 0.0 // Zero absoluto
                else -> random.nextDouble() * 50_000.0 // Valores extremos
            }

            val totalDistanceKm = when (cycle % 4) {
                0 -> 0.0 // Distância zero
                1 -> random.nextDouble() * 5.0
                2 -> 5.0 + random.nextDouble() * 50.0
                else -> 100.0 + random.nextDouble() * 1000.0
            }

            val totalDurationMin = when (cycle % 4) {
                0 -> 0 // Duração zero
                1 -> random.nextInt(15)
                2 -> 15 + random.nextInt(90)
                else -> 100 + random.nextInt(1500)
            }

            val eval = RideOfferParser.evaluateOffer(
                grossPrice = grossPrice,
                totalDistanceKm = totalDistanceKm,
                totalDurationMin = totalDurationMin,
                thresholds = thresholds
            )

            // Validações rigorosas de estabilidade numérica:
            assertTrue("Ciclo $cycle: earningsPerKm não pode ser NaN", !eval.earningsPerKm.isNaN())
            assertTrue("Ciclo $cycle: earningsPerKm não pode ser Infinito", !eval.earningsPerKm.isInfinite())
            assertTrue("Ciclo $cycle: earningsPerKm deve ser >= 0", eval.earningsPerKm >= 0.0)

            assertTrue("Ciclo $cycle: earningsPerHour não pode ser NaN", !eval.earningsPerHour.isNaN())
            assertTrue("Ciclo $cycle: earningsPerHour não pode ser Infinito", !eval.earningsPerHour.isInfinite())
            assertTrue("Ciclo $cycle: earningsPerHour deve ser >= 0", eval.earningsPerHour >= 0.0)

            assertTrue("Ciclo $cycle: netProfit não pode ser NaN", !eval.netProfit.isNaN())
            assertTrue("Ciclo $cycle: netProfit não pode ser Infinito", !eval.netProfit.isInfinite())

            assertNotNull("Ciclo $cycle: Status do semáforo nunca pode ser nulo", eval.status)
            assertTrue("Ciclo $cycle: Assinatura não pode ser vazia", eval.rawSignature.isNotBlank())
        }
    }

    @Test
    fun `ciclo massivo de 2000 iteracoes - resiliencia do parser sob injecao de ruidos e telas atipicas`() {
        val nonOfferScreenFragments = listOf(
            "Configurações da conta",
            "Saldo disponível R$ 450,20",
            "Como foi a viagem?",
            "Avaliar usuário",
            "Iniciar navegação",
            "Progresso na missão: faltam 3 viagens",
            "Última viagem: R$ 18,90 recebido",
            "Solicitação de reserva confirmada"
        )

        val offerPriceFragments = listOf("R$ 15,50", "R$ 22,00", "R$ 48,90", "R$ 8,25", "R$ 110,00")
        val offerPickupFragments = listOf("3 min (1,2 km) até o passageiro", "5 min (2,0 km)", "2 min (800 m) de distância")
        val offerTripFragments = listOf("Viagem de 18 min (7,5 km)", "Viagem de 45 min (22,0 km)", "14 min (5,1 km)")

        var rejectedCount = 0
        var parsedCount = 0

        for (cycle in 1..20000) {
            val isIntendedAsOffer = random.nextBoolean()

            val nodes = mutableListOf<String>()
            if (isIntendedAsOffer) {
                nodes.add("UberX")
                nodes.add("Exclusivo")
                nodes.add(offerPriceFragments[random.nextInt(offerPriceFragments.size)])
                nodes.add(offerPickupFragments[random.nextInt(offerPickupFragments.size)])
                nodes.add(offerTripFragments[random.nextInt(offerTripFragments.size)])
                nodes.add("Aceitar")

                // Injeta ruído aleatório em 30% dos ciclos para testar tolerância
                if (random.nextInt(10) < 3) {
                    nodes.add("+R$ 3,50 incluído no valor")
                    nodes.add("★ 4.95 (340)")
                }
            } else {
                // Injeta telas de sistema / histórico / configuração que NUNCA devem disparar o semáforo
                nodes.add("Menu Principal")
                nodes.add(nonOfferScreenFragments[random.nextInt(nonOfferScreenFragments.size)])
                nodes.add("R$ ${random.nextInt(500)},00")
                nodes.add("Fechar")
            }

            val evaluation = RideOfferParser.parseScreenNodes(nodes)
            if (isIntendedAsOffer) {
                assertNotNull("Ciclo $cycle: Oferta legítima deve ser reconhecida com sucesso", evaluation)
                parsedCount++
            } else {
                assertNull("Ciclo $cycle: Tela de não-oferta deve ser estritamente descartada", evaluation)
                rejectedCount++
            }
        }

        assertTrue("Auditoria concluída: Deve processar ofertas válidas", parsedCount > 0)
        assertTrue("Auditoria concluída: Deve rejeitar telas espúrias", rejectedCount > 0)
        assertEquals("Total de ciclos executados deve somar 20000", 20000, parsedCount + rejectedCount)
    }

    @Test
    fun `ciclo massivo de 2000 iteracoes - matriz combinatoria do semaforo de saude do sistema`() {
        for (cycle in 1..20000) {
            val overlay = random.nextBoolean()
            val a11y = random.nextBoolean()
            val service = random.nextBoolean()
            val selectedInstalled = random.nextInt(4)
            val selectedTotal = selectedInstalled + random.nextInt(3)
            val batteryOk = random.nextBoolean()

            val inputs = HealthInputs(
                overlayGranted = overlay,
                accessibilityEnabled = a11y,
                serviceConnected = service,
                selectedInstalledTargets = selectedInstalled,
                selectedTargets = selectedTotal,
                batteryOptimizationIgnored = batteryOk
            )

            val health = SystemHealthEvaluator.evaluate(inputs)
            assertNotNull("Ciclo $cycle: HealthEvaluation nunca pode ser nulo", health)
            assertNotNull("Ciclo $cycle: HealthLevel nunca pode ser nulo", health.level)
            assertTrue("Ciclo $cycle: Título não pode ser vazio", health.title.isNotBlank())
            assertTrue("Ciclo $cycle: Detalhe não pode ser vazio", health.detail.isNotBlank())

            // Regra mestra de segurança: Se sobreposição ou acessibilidade ou serviço falharem, NUNCA pode ser GREEN
            if (!overlay || !a11y || !service) {
                assertEquals(
                    "Ciclo $cycle: Sem permissões críticas deve estar em RED",
                    HealthLevel.RED,
                    health.level
                )
            } else if (selectedInstalled == 0 || !batteryOk) {
                // Se tudo crítico está ok, mas nenhum app está marcado ou bateria restrita, deve ser YELLOW
                assertEquals(
                    "Ciclo $cycle: Com pendências secundárias deve estar em YELLOW",
                    HealthLevel.YELLOW,
                    health.level
                )
            } else {
                // Todos os requisitos preenchidos: deve ser GREEN
                assertEquals(
                    "Ciclo $cycle: Tudo regular deve estar em GREEN",
                    HealthLevel.GREEN,
                    health.level
                )
            }
        }
    }
}

