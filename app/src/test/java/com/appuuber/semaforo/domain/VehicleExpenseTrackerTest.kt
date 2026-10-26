package com.appuuber.semaforo.domain

import com.appuuber.semaforo.data.VehicleExpenseRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Suíte de testes unitários para o módulo REA de Controle de Gastos (Combustível e Manutenção/Peças)
 * do veículo Fiat Argo 2024/25 Vermelho.
 */
class VehicleExpenseTrackerTest {

    @Test
    fun `perfil padrao do veiculo e Fiat Argo 2024-25 Vermelho`() {
        val profile = VehicleProfile()
        assertEquals("Fiat Argo", profile.name)
        assertEquals("2024/25", profile.modelYear)
        assertEquals("Vermelho", profile.color)
        assertEquals("Fiat Argo 2024/25 • Vermelho", profile.badgeTitle)
    }

    @Test
    fun `abastecimento calcula litros automaticamente e valida campos obrigatorios`() {
        val abastecimento = FuelRecord(
            dateBr = "26/10/2026",
            odometerKm = 24500.0,
            totalAmountPaid = 176.70,
            fuelType = FuelType.GASOLINA_COMUM,
            pricePerLiter = 5.89,
            paymentMethod = PaymentMethod.CREDITO,
            stationName = "Posto Ipiranga Centro"
        )
        assertTrue(abastecimento.isValid())
        assertEquals(30.0, abastecimento.liters, 0.01)
        assertEquals(PaymentMethod.CREDITO, abastecimento.paymentMethod)
        assertEquals(FuelType.GASOLINA_COMUM, abastecimento.fuelType)
    }

    @Test
    fun `troca de peca soma valor da peca e mao de obra e preserva todos os detalhes`() {
        val trocaPastilha = MaintenanceRecord(
            partName = "Pastilha de Freio Dianteira Cobreq",
            dateBr = "15/10/2026",
            mechanicOrWorkshop = "Oficina do Mestre João",
            laborCost = 120.00,
            partCost = 235.50,
            whereBought = "Mercado Livre - Loja Oficial",
            odometerKm = 24100.0,
            paymentMethod = PaymentMethod.DEBITO,
            notes = "Garantia de 6 meses"
        )
        assertTrue(trocaPastilha.isValid())
        assertEquals(355.50, trocaPastilha.totalCost, 0.001)
        assertEquals("Oficina do Mestre João", trocaPastilha.mechanicOrWorkshop)
        assertEquals("Mercado Livre - Loja Oficial", trocaPastilha.whereBought)
        assertEquals("15/10/2026", trocaPastilha.dateBr)
    }

    @Test
    fun `resumo financeiro separa credito vs debito, peca vs mao de obra e calcula km por litro por odometro`() {
        val fuels = listOf(
            FuelRecord(
                id = "f1",
                dateBr = "10/10/2026",
                odometerKm = 20000.0,
                totalAmountPaid = 200.00,
                fuelType = FuelType.GASOLINA_COMUM,
                pricePerLiter = 5.00, // 40 L
                paymentMethod = PaymentMethod.CREDITO
            ),
            FuelRecord(
                id = "f2",
                dateBr = "18/10/2026",
                odometerKm = 20500.0, // +500 km rodados
                totalAmountPaid = 200.00,
                fuelType = FuelType.GASOLINA_ADITIVADA,
                pricePerLiter = 5.00, // 40 L consumidos para 500 km => 12.5 km/L
                paymentMethod = PaymentMethod.DEBITO
            ),
            FuelRecord(
                id = "f3",
                dateBr = "26/10/2026",
                odometerKm = 21000.0, // +500 km rodados (total 1000 km entre 20000 e 21000)
                totalAmountPaid = 180.00,
                fuelType = FuelType.ETANOL,
                pricePerLiter = 3.60, // 50 L
                paymentMethod = PaymentMethod.CREDITO
            )
        )

        val maintenances = listOf(
            MaintenanceRecord(
                id = "m1",
                partName = "Óleo 0W20 Selenia + Filtro",
                dateBr = "12/10/2026",
                mechanicOrWorkshop = "Concessionária Fiat",
                laborCost = 90.00,
                partCost = 210.00, // Total 300.00 no Crédito
                whereBought = "Concessionária Fiat",
                odometerKm = 20100.0,
                paymentMethod = PaymentMethod.CREDITO
            ),
            MaintenanceRecord(
                id = "m2",
                partName = " Bieleta da Suspensão",
                dateBr = "22/10/2026",
                mechanicOrWorkshop = "Auto Center Brasil",
                laborCost = 80.00,
                partCost = 120.00, // Total 200.00 no Débito
                whereBought = "AutoZone",
                odometerKm = 20800.0,
                paymentMethod = PaymentMethod.DEBITO
            )
        )

        val summary = VehicleExpenseCalculator.summarize(fuels, maintenances)

        assertEquals(3, summary.fuelCount)
        assertEquals(2, summary.maintenanceCount)
        assertEquals(21000.0, summary.currentOdometerKm, 0.01)
        assertEquals(1000.0, summary.trackedDistanceKm, 0.01)

        // Combustível: 200 + 200 + 180 = 580 (380 Crédito, 200 Débito)
        assertEquals(580.0, summary.totalFuelSpent, 0.01)
        assertEquals(380.0, summary.fuelSpentCredito, 0.01)
        assertEquals(200.0, summary.fuelSpentDebito, 0.01)
        assertEquals(130.0, summary.totalLiters, 0.01)

        // Manutenção: Peças (210 + 120 = 330) + Mão de Obra (90 + 80 = 170) = 500
        assertEquals(330.0, summary.totalPartsSpent, 0.01)
        assertEquals(170.0, summary.totalLaborSpent, 0.01)
        assertEquals(500.0, summary.totalMaintenanceSpent, 0.01)
        assertEquals(300.0, summary.maintenanceSpentCredito, 0.01)
        assertEquals(200.0, summary.maintenanceSpentDebito, 0.01)

        // Totais Gerais e por Cartão
        assertEquals(1080.0, summary.grandTotalSpent, 0.01)
        assertEquals(680.0, summary.totalCredito, 0.01)
        assertEquals(400.0, summary.totalDebito, 0.01)

        // Custo por KM em 1000 km monitorados:
        // Combustível: 580 / 1000 = 0.58/km
        // Manutenção: 500 / 1000 = 0.50/km
        // Custo Real Total: 1.08/km
        assertEquals(0.58, summary.fuelCostPerKm, 0.01)
        assertEquals(0.50, summary.maintenanceCostPerKm, 0.01)
        assertEquals(1.08, summary.realCostPerKm, 0.01)

        // Integração com o Semáforo (RideOfferParser) usando o custo real do Argo
        val thresholds = DriverThresholds(costPerKm = summary.realCostPerKm).sanitized()
        val eval = RideOfferParser.parseScreenNodes(
            listOf("UberX", "R$ 25,00", "3 min (1,0 km) de distância", "Viagem de 17 min (9,0 km)", "Aceitar"),
            thresholds
        )
        assertNotNull(eval)
        // 10 km totais * R$ 1,08/km = R$ 10,80 de custo -> Lucro líquido = 25,00 - 10,80 = R$ 14,20
        assertEquals(14.20, eval!!.netProfit, 0.05)
    }

    @Test
    fun `serializacao e desserializacao JSON preserva todos os campos de abastecimento e pecas`() {
        val fuels = listOf(
            FuelRecord(
                id = "fuel-1",
                dateBr = "26/10/2026",
                odometerKm = 31200.0,
                totalAmountPaid = 150.0,
                fuelType = FuelType.ETANOL,
                pricePerLiter = 3.79,
                paymentMethod = PaymentMethod.DEBITO,
                stationName = "Posto BR",
                timestampMs = 1700000000L
            )
        )
        val rawFuelJson = VehicleExpenseRepository.serializeFuelRecordsJson(fuels)
        val parsedFuels = VehicleExpenseRepository.parseFuelRecordsJson(rawFuelJson)
        assertEquals(1, parsedFuels.size)
        assertEquals("fuel-1", parsedFuels[0].id)
        assertEquals(31200.0, parsedFuels[0].odometerKm, 0.01)
        assertEquals(150.0, parsedFuels[0].totalAmountPaid, 0.01)
        assertEquals(FuelType.ETANOL, parsedFuels[0].fuelType)
        assertEquals(3.79, parsedFuels[0].pricePerLiter, 0.01)
        assertEquals(PaymentMethod.DEBITO, parsedFuels[0].paymentMethod)
        assertEquals("Posto BR", parsedFuels[0].stationName)

        val maints = listOf(
            MaintenanceRecord(
                id = "maint-1",
                partName = "Jogo de Velas Fiat Firefly",
                dateBr = "20/10/2026",
                mechanicOrWorkshop = "Oficina Irmãos Silva",
                laborCost = 100.0,
                partCost = 195.0,
                whereBought = "Auto Peças São Cristóvão",
                odometerKm = 30900.0,
                paymentMethod = PaymentMethod.CREDITO,
                notes = "Original Mopar",
                timestampMs = 1700000500L
            )
        )
        val rawMaintJson = VehicleExpenseRepository.serializeMaintenanceRecordsJson(maints)
        val parsedMaints = VehicleExpenseRepository.parseMaintenanceRecordsJson(rawMaintJson)
        assertEquals(1, parsedMaints.size)
        assertEquals("Jogo de Velas Fiat Firefly", parsedMaints[0].partName)
        assertEquals("20/10/2026", parsedMaints[0].dateBr)
        assertEquals("Oficina Irmãos Silva", parsedMaints[0].mechanicOrWorkshop)
        assertEquals(100.0, parsedMaints[0].laborCost, 0.01)
        assertEquals(195.0, parsedMaints[0].partCost, 0.01)
        assertEquals(295.0, parsedMaints[0].totalCost, 0.01)
        assertEquals("Auto Peças São Cristóvão", parsedMaints[0].whereBought)
        assertEquals(PaymentMethod.CREDITO, parsedMaints[0].paymentMethod)
        assertEquals("Original Mopar", parsedMaints[0].notes)
    }

    @Test
    fun `entradas invalidas ou negativas sao rejeitadas com seguranca`() {
        val badFuel = FuelRecord(
            dateBr = "26/10/2026",
            odometerKm = -10.0,
            totalAmountPaid = 0.0,
            fuelType = FuelType.GASOLINA_COMUM,
            pricePerLiter = 0.0,
            paymentMethod = PaymentMethod.CREDITO
        )
        assertFalse(badFuel.isValid())
        assertEquals(0.0, badFuel.liters, 0.001)

        val badMaint = MaintenanceRecord(
            partName = "",
            dateBr = "",
            mechanicOrWorkshop = "Oficina",
            laborCost = -50.0,
            partCost = 0.0,
            whereBought = "Loja"
        )
        assertFalse(badMaint.isValid())
    }
}
