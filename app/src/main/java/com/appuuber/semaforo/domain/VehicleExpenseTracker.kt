package com.appuuber.semaforo.domain

import java.util.UUID
import kotlin.math.max

/**
 * Perfil fixo e dedicado do veículo do motorista conforme especificação:
 * Fiat Argo 2024/2025 - Cor Vermelho.
 */
data class VehicleProfile(
    val name: String = "Fiat Argo",
    val modelYear: String = "2024/25",
    val color: String = "Vermelho",
    val defaultKmPerLiterGasoline: Double = 12.8,
    val defaultKmPerLiterEthanol: Double = 9.0
) {
    val badgeTitle: String get() = "$name $modelYear • $color"
}

/**
 * Tipos de combustível suportados no abastecimento.
 */
enum class FuelType(val label: String) {
    GASOLINA_COMUM("Gasolina Comum"),
    GASOLINA_ADITIVADA("Gasolina Aditivada"),
    ETANOL("Etanol"),
    GNV("GNV");

    companion object {
        fun fromNameOrDefault(name: String?): FuelType =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) || it.label.equals(name, ignoreCase = true) }
                ?: GASOLINA_COMUM
    }
}

/**
 * Forma de pagamento utilizada no abastecimento ou na manutenção.
 */
enum class PaymentMethod(val label: String) {
    CREDITO("Crédito"),
    DEBITO("Débito"),
    PIX_DINHEIRO("PIX / Dinheiro");

    companion object {
        fun fromNameOrDefault(name: String?): PaymentMethod =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) || it.label.equals(name, ignoreCase = true) }
                ?: CREDITO
    }
}

/**
 * Registro individual de abastecimento (Padrão REA Rota Pro: `Abastecimento` + `kmPorOdometro`).
 *
 * Campos obrigatórios solicitados:
 * - [odometerKm]: Quilometragem atual no momento do abastecimento
 * - [totalAmountPaid]: Quanto vai abastecer (valor total em R$)
 * - [fuelType]: Qual tipo de combustível (Gasolina, Etanol, etc.)
 * - [pricePerLiter]: Valor do combustível por litro (R$/L)
 * - [paymentMethod]: Se pagou no Crédito ou Débito (ou PIX/Dinheiro)
 */
data class FuelRecord(
    val id: String = UUID.randomUUID().toString(),
    val dateBr: String,
    val odometerKm: Double,
    val totalAmountPaid: Double,
    val fuelType: FuelType,
    val pricePerLiter: Double,
    val paymentMethod: PaymentMethod,
    val stationName: String = "",
    val timestampMs: Long = System.currentTimeMillis()
) {
    /** Quantidade de litros calculada automaticamente (Quanto abasteceu R$ / Valor do litro R$). */
    val liters: Double
        get() = if (pricePerLiter > 0.0 && totalAmountPaid > 0.0 && pricePerLiter.isFinite() && totalAmountPaid.isFinite()) {
            totalAmountPaid / pricePerLiter
        } else {
            0.0
        }

    fun isValid(): Boolean =
        dateBr.isNotBlank() &&
            odometerKm.isFinite() && odometerKm >= 0.0 &&
            totalAmountPaid.isFinite() && totalAmountPaid > 0.0 &&
            pricePerLiter.isFinite() && pricePerLiter > 0.0
}

/**
 * Registro de troca de peça / manutenção do veículo (Padrão REA Rota Pro: `wearItems` / `fin_manutencao`).
 *
 * Campos obrigatórios solicitados:
 * - [partName]: Qual peça do veículo foi trocada
 * - [dateBr]: Data em que foi feita a troca
 * - [mechanicOrWorkshop]: Por quem foi feita a troca (mecânico / oficina)
 * - [laborCost]: Quanto foi de mão de obra (R$)
 * - [partCost]: Quanto foi da peça (R$)
 * - [whereBought]: Onde comprou a peça (loja / fornecedor / site)
 * - [odometerKm]: Quilometragem do veículo na troca (opcional)
 * - [paymentMethod]: Forma de pagamento (Crédito / Débito / PIX)
 * - [notes]: Detalhes adicionais (marca da peça, garantia, observações)
 */
data class MaintenanceRecord(
    val id: String = UUID.randomUUID().toString(),
    val partName: String,
    val dateBr: String,
    val mechanicOrWorkshop: String,
    val laborCost: Double,
    val partCost: Double,
    val whereBought: String,
    val odometerKm: Double = 0.0,
    val paymentMethod: PaymentMethod = PaymentMethod.CREDITO,
    val notes: String = "",
    val timestampMs: Long = System.currentTimeMillis()
) {
    /** Custo total do serviço (Peça + Mão de Obra). */
    val totalCost: Double
        get() {
            val l = if (laborCost.isFinite() && laborCost >= 0.0) laborCost else 0.0
            val p = if (partCost.isFinite() && partCost >= 0.0) partCost else 0.0
            return l + p
        }

    fun isValid(): Boolean =
        partName.isNotBlank() &&
            dateBr.isNotBlank() &&
            laborCost.isFinite() && laborCost >= 0.0 &&
            partCost.isFinite() && partCost >= 0.0 &&
            (laborCost + partCost) > 0.0 &&
            odometerKm.isFinite() && odometerKm >= 0.0
}

/**
 * Consolidação financeira e operacional do veículo Argo 2024/25 Vermelho.
 */
data class VehicleFinancialSummary(
    val vehicle: VehicleProfile = VehicleProfile(),
    val fuelCount: Int = 0,
    val maintenanceCount: Int = 0,
    val currentOdometerKm: Double = 0.0,
    val trackedDistanceKm: Double = 0.0,
    val totalFuelSpent: Double = 0.0,
    val totalLiters: Double = 0.0,
    val averageFuelPricePerLiter: Double = 0.0,
    val averageConsumptionKmPerLiter: Double = 0.0,
    val fuelSpentCredito: Double = 0.0,
    val fuelSpentDebito: Double = 0.0,
    val fuelSpentPix: Double = 0.0,
    val totalPartsSpent: Double = 0.0,
    val totalLaborSpent: Double = 0.0,
    val totalMaintenanceSpent: Double = 0.0,
    val maintenanceSpentCredito: Double = 0.0,
    val maintenanceSpentDebito: Double = 0.0,
    val grandTotalSpent: Double = 0.0,
    val totalCredito: Double = 0.0,
    val totalDebito: Double = 0.0,
    val fuelCostPerKm: Double = 0.0,
    val maintenanceCostPerKm: Double = 0.0,
    val realCostPerKm: Double = 0.0
)

/**
 * Motor de cálculo de custos reais do veículo (Engenharia Reversa Rota Pro / GiGu).
 * Converte o histórico de abastecimentos (`kmPorOdometro`) e trocas de peças (`wearItems`)
 * em métricas de consumo real (km/L) e custo por km rodado (`realCostPerKm`).
 */
object VehicleExpenseCalculator {

    fun summarize(
        fuelRecords: List<FuelRecord>,
        maintenanceRecords: List<MaintenanceRecord>,
        profile: VehicleProfile = VehicleProfile()
    ): VehicleFinancialSummary {
        val validFuels = fuelRecords.filter { it.isValid() }
        val validMaint = maintenanceRecords.filter { it.isValid() }

        val totalFuel = validFuels.sumOf { it.totalAmountPaid }
        val totalLiters = validFuels.sumOf { it.liters }
        val avgPricePerLiter = if (totalLiters > 0.0) totalFuel / totalLiters else 0.0

        val fuelCredito = validFuels.filter { it.paymentMethod == PaymentMethod.CREDITO }.sumOf { it.totalAmountPaid }
        val fuelDebito = validFuels.filter { it.paymentMethod == PaymentMethod.DEBITO }.sumOf { it.totalAmountPaid }
        val fuelPix = validFuels.filter { it.paymentMethod == PaymentMethod.PIX_DINHEIRO }.sumOf { it.totalAmountPaid }

        val totalParts = validMaint.sumOf { it.partCost }
        val totalLabor = validMaint.sumOf { it.laborCost }
        val totalMaint = totalParts + totalLabor

        val maintCredito = validMaint.filter { it.paymentMethod == PaymentMethod.CREDITO }.sumOf { it.totalCost }
        val maintDebito = validMaint.filter { it.paymentMethod == PaymentMethod.DEBITO }.sumOf { it.totalCost }

        val allOdometers = (validFuels.map { it.odometerKm } + validMaint.map { it.odometerKm })
            .filter { it > 0.0 }

        val currentOdometer = allOdometers.maxOrNull() ?: 0.0

        // Cálculo de distância percorrida e consumo real (km/L) pelo odômetro (kmPorOdometro)
        val fuelsByOdometer = validFuels.filter { it.odometerKm > 0.0 }.sortedBy { it.odometerKm }
        val odometerSpanKm = if (fuelsByOdometer.size >= 2) {
            max(0.0, fuelsByOdometer.last().odometerKm - fuelsByOdometer.first().odometerKm)
        } else if (allOdometers.size >= 2) {
            max(0.0, (allOdometers.maxOrNull() ?: 0.0) - (allOdometers.minOrNull() ?: 0.0))
        } else {
            0.0
        }

        // Quando há 2+ abastecimentos com odômetro crescente, o consumo real é:
        // distância entre o primeiro e o último abastecimento / litros consumidos nos abastecimentos intermediários+finais
        val consumptionKmPerL: Double
        val effectiveDistanceKm: Double

        if (fuelsByOdometer.size >= 2 && odometerSpanKm > 0.0) {
            // Soma dos litros após o primeiro registro (ou todos se preferir estimativa direta de ciclo)
            val litersAfterFirst = fuelsByOdometer.drop(1).sumOf { it.liters }.takeIf { it > 0.0 } ?: totalLiters
            consumptionKmPerL = (odometerSpanKm / litersAfterFirst).coerceIn(3.0, 35.0)
            effectiveDistanceKm = odometerSpanKm
        } else if (validFuels.isNotEmpty() && totalLiters > 0.0) {
            // Apenas 1 abastecimento: estima autonomia usando o consumo médio padrão do Argo 2024/25
            val lastFuel = validFuels.last()
            consumptionKmPerL = if (lastFuel.fuelType == FuelType.ETANOL) {
                profile.defaultKmPerLiterEthanol
            } else {
                profile.defaultKmPerLiterGasoline
            }
            effectiveDistanceKm = if (odometerSpanKm > 0.0) odometerSpanKm else totalLiters * consumptionKmPerL
        } else {
            consumptionKmPerL = profile.defaultKmPerLiterGasoline
            effectiveDistanceKm = odometerSpanKm
        }

        val fuelCostPerKm = when {
            effectiveDistanceKm > 0.0 && totalFuel > 0.0 -> totalFuel / effectiveDistanceKm
            avgPricePerLiter > 0.0 && consumptionKmPerL > 0.0 -> avgPricePerLiter / consumptionKmPerL
            else -> 0.0
        }

        // Para diluir manutenção por km: usa a distância monitorada ou, se o usuário cadastrou peça antes de rodar,
        // dilui pela janela preventiva padrão de 10.000 km (revisão padrão Fiat Argo) para não distorcer o semáforo.
        val maintBaseKm = when {
            effectiveDistanceKm >= 1000.0 -> effectiveDistanceKm
            currentOdometer >= 1000.0 && odometerSpanKm > 0.0 -> odometerSpanKm
            else -> 10_000.0
        }
        val maintenanceCostPerKm = if (totalMaint > 0.0) totalMaint / maintBaseKm else 0.0

        val realCostPerKm = (fuelCostPerKm + maintenanceCostPerKm).let {
            if (it.isFinite() && it > 0.0) it else 0.0
        }

        return VehicleFinancialSummary(
            vehicle = profile,
            fuelCount = validFuels.size,
            maintenanceCount = validMaint.size,
            currentOdometerKm = currentOdometer,
            trackedDistanceKm = effectiveDistanceKm,
            totalFuelSpent = totalFuel,
            totalLiters = totalLiters,
            averageFuelPricePerLiter = avgPricePerLiter,
            averageConsumptionKmPerLiter = if (validFuels.isNotEmpty()) consumptionKmPerL else 0.0,
            fuelSpentCredito = fuelCredito,
            fuelSpentDebito = fuelDebito,
            fuelSpentPix = fuelPix,
            totalPartsSpent = totalParts,
            totalLaborSpent = totalLabor,
            totalMaintenanceSpent = totalMaint,
            maintenanceSpentCredito = maintCredito,
            maintenanceSpentDebito = maintDebito,
            grandTotalSpent = totalFuel + totalMaint,
            totalCredito = fuelCredito + maintCredito,
            totalDebito = fuelDebito + maintDebito,
            fuelCostPerKm = fuelCostPerKm,
            maintenanceCostPerKm = maintenanceCostPerKm,
            realCostPerKm = realCostPerKm
        )
    }
}
