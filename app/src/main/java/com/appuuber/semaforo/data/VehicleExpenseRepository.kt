package com.appuuber.semaforo.data

import android.content.Context
import com.appuuber.semaforo.domain.FuelRecord
import com.appuuber.semaforo.domain.FuelType
import com.appuuber.semaforo.domain.MaintenanceRecord
import com.appuuber.semaforo.domain.PaymentMethod
import com.appuuber.semaforo.domain.VehicleExpenseCalculator
import com.appuuber.semaforo.domain.VehicleFinancialSummary
import com.appuuber.semaforo.domain.VehicleProfile

/**
 * Repositório local persistente para controle de gastos de Combustível e Manutenção/Peças
 * do veículo Fiat Argo 2024/25 Vermelho.
 * Usa codec Kotlin puro (independente dos stubs de `org.json` na JVM de teste).
 */
class VehicleExpenseRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val profile = VehicleProfile()

    fun getVehicleProfile(): VehicleProfile = profile

    fun getFuelRecords(): List<FuelRecord> {
        val raw = prefs.getString(KEY_FUEL_RECORDS, null) ?: return emptyList()
        return parseFuelRecordsJson(raw)
    }

    fun addFuelRecord(record: FuelRecord): Boolean {
        if (!record.isValid()) return false
        val updated = (listOf(record) + getFuelRecords().filterNot { it.id == record.id })
            .sortedWith(compareByDescending<FuelRecord> { it.timestampMs }.thenByDescending { it.odometerKm })
        prefs.edit().putString(KEY_FUEL_RECORDS, serializeFuelRecordsJson(updated)).apply()
        return true
    }

    fun deleteFuelRecord(id: String) {
        val updated = getFuelRecords().filterNot { it.id == id }
        prefs.edit().putString(KEY_FUEL_RECORDS, serializeFuelRecordsJson(updated)).apply()
    }

    fun getMaintenanceRecords(): List<MaintenanceRecord> {
        val raw = prefs.getString(KEY_MAINT_RECORDS, null) ?: return emptyList()
        return parseMaintenanceRecordsJson(raw)
    }

    fun addMaintenanceRecord(record: MaintenanceRecord): Boolean {
        if (!record.isValid()) return false
        val updated = (listOf(record) + getMaintenanceRecords().filterNot { it.id == record.id })
            .sortedByDescending { it.timestampMs }
        prefs.edit().putString(KEY_MAINT_RECORDS, serializeMaintenanceRecordsJson(updated)).apply()
        return true
    }

    fun deleteMaintenanceRecord(id: String) {
        val updated = getMaintenanceRecords().filterNot { it.id == id }
        prefs.edit().putString(KEY_MAINT_RECORDS, serializeMaintenanceRecordsJson(updated)).apply()
    }

    fun getFinancialSummary(): VehicleFinancialSummary {
        return VehicleExpenseCalculator.summarize(
            fuelRecords = getFuelRecords(),
            maintenanceRecords = getMaintenanceRecords(),
            profile = profile
        )
    }

    companion object {
        private const val PREFS_NAME = "argo_2024_25_expenses_prefs"
        private const val KEY_FUEL_RECORDS = "fuel_records_v1"
        private const val KEY_MAINT_RECORDS = "maintenance_records_v1"

        private fun encodeField(s: String): String =
            s.replace("\\", "\\\\")
                .replace("|", "\\p")
                .replace("\n", "\\n")
                .replace("\r", "")

        private fun decodeField(s: String): String {
            val sb = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    when (val next = s[i + 1]) {
                        '\\' -> sb.append('\\')
                        'p' -> sb.append('|')
                        'n' -> sb.append('\n')
                        else -> sb.append(next)
                    }
                    i += 2
                } else {
                    sb.append(c)
                    i++
                }
            }
            return sb.toString()
        }

        fun serializeFuelRecordsJson(records: List<FuelRecord>): String {
            return records.joinToString(separator = "\n") { r ->
                listOf(
                    encodeField(r.id),
                    encodeField(r.dateBr),
                    r.odometerKm.toString(),
                    r.totalAmountPaid.toString(),
                    encodeField(r.fuelType.name),
                    r.pricePerLiter.toString(),
                    encodeField(r.paymentMethod.name),
                    encodeField(r.stationName),
                    r.timestampMs.toString()
                ).joinToString(separator = "|")
            }
        }

        fun parseFuelRecordsJson(raw: String): List<FuelRecord> {
            if (raw.isBlank()) return emptyList()
            return raw.lines().mapNotNull { line ->
                if (line.isBlank()) return@mapNotNull null
                val parts = line.split("|")
                if (parts.size < 7) return@mapNotNull null
                val record = FuelRecord(
                    id = decodeField(parts[0]).ifBlank { "fuel_id" },
                    dateBr = decodeField(parts[1]),
                    odometerKm = parts[2].toDoubleOrNull() ?: 0.0,
                    totalAmountPaid = parts[3].toDoubleOrNull() ?: 0.0,
                    fuelType = FuelType.fromNameOrDefault(decodeField(parts[4])),
                    pricePerLiter = parts[5].toDoubleOrNull() ?: 0.0,
                    paymentMethod = PaymentMethod.fromNameOrDefault(decodeField(parts[6])),
                    stationName = if (parts.size > 7) decodeField(parts[7]) else "",
                    timestampMs = if (parts.size > 8) parts[8].toLongOrNull() ?: 0L else 0L
                )
                record.takeIf { it.isValid() }
            }
        }

        fun serializeMaintenanceRecordsJson(records: List<MaintenanceRecord>): String {
            return records.joinToString(separator = "\n") { r ->
                listOf(
                    encodeField(r.id),
                    encodeField(r.partName),
                    encodeField(r.dateBr),
                    encodeField(r.mechanicOrWorkshop),
                    r.laborCost.toString(),
                    r.partCost.toString(),
                    encodeField(r.whereBought),
                    r.odometerKm.toString(),
                    encodeField(r.paymentMethod.name),
                    encodeField(r.notes),
                    r.timestampMs.toString()
                ).joinToString(separator = "|")
            }
        }

        fun parseMaintenanceRecordsJson(raw: String): List<MaintenanceRecord> {
            if (raw.isBlank()) return emptyList()
            return raw.lines().mapNotNull { line ->
                if (line.isBlank()) return@mapNotNull null
                val parts = line.split("|")
                if (parts.size < 7) return@mapNotNull null
                val record = MaintenanceRecord(
                    id = decodeField(parts[0]).ifBlank { "maint_id" },
                    partName = decodeField(parts[1]),
                    dateBr = decodeField(parts[2]),
                    mechanicOrWorkshop = decodeField(parts[3]),
                    laborCost = parts[4].toDoubleOrNull() ?: 0.0,
                    partCost = parts[5].toDoubleOrNull() ?: 0.0,
                    whereBought = decodeField(parts[6]),
                    odometerKm = if (parts.size > 7) parts[7].toDoubleOrNull() ?: 0.0 else 0.0,
                    paymentMethod = if (parts.size > 8) PaymentMethod.fromNameOrDefault(decodeField(parts[8])) else PaymentMethod.CREDITO,
                    notes = if (parts.size > 9) decodeField(parts[9]) else "",
                    timestampMs = if (parts.size > 10) parts[10].toLongOrNull() ?: 0L else 0L
                )
                record.takeIf { it.isValid() }
            }
        }
    }
}
