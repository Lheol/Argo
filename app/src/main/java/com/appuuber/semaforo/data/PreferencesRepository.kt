package com.appuuber.semaforo.data

import android.content.Context
import com.appuuber.semaforo.domain.DriverThresholds

/**
 * Armazena as metas personalizadas do motorista localmente (SharedPreferences).
 */
class PreferencesRepository(context: Context) {

    private val prefs = context.getSharedPreferences("semaforo_uber_prefs", Context.MODE_PRIVATE)

    fun getThresholds(): DriverThresholds {
        return DriverThresholds(
            greenMinPerKm = prefs.getFloat(KEY_GREEN_KM, 2.00f).toDouble(),
            yellowMinPerKm = prefs.getFloat(KEY_YELLOW_KM, 1.50f).toDouble(),
            greenMinPerHour = prefs.getFloat(KEY_GREEN_HOUR, 45.0f).toDouble(),
            yellowMinPerHour = prefs.getFloat(KEY_YELLOW_HOUR, 32.0f).toDouble(),
            costPerKm = prefs.getFloat(KEY_COST_KM, 0.75f).toDouble()
        ).sanitized()
    }

    fun saveThresholds(raw: DriverThresholds) {
        val thresholds = raw.sanitized()
        prefs.edit()
            .putFloat(KEY_GREEN_KM, thresholds.greenMinPerKm.toFloat())
            .putFloat(KEY_YELLOW_KM, thresholds.yellowMinPerKm.toFloat())
            .putFloat(KEY_GREEN_HOUR, thresholds.greenMinPerHour.toFloat())
            .putFloat(KEY_YELLOW_HOUR, thresholds.yellowMinPerHour.toFloat())
            .putFloat(KEY_COST_KM, thresholds.costPerKm.toFloat())
            .apply()
    }

    /**
     * Apps MARCADOS pelo usuário. Padrão: nenhum (o leitor só atua depois da marcação manual).
     * Sempre filtrado pelo catálogo fechado e pela política de apps sensíveis.
     */
    fun getEnabledTargets(): Set<String> {
        val stored = prefs.getStringSet(KEY_TARGETS, emptySet()) ?: emptySet()
        return stored.filter { TargetApps.isSelectable(it) }.toSet()
    }

    fun setTargetEnabled(pkg: String, enabled: Boolean) {
        if (!TargetApps.isSelectable(pkg)) return
        val updated = getEnabledTargets().toMutableSet()
        if (enabled) updated.add(pkg) else updated.remove(pkg)
        // putStringSet exige uma NOVA instância de Set para persistir de forma confiável.
        prefs.edit().putStringSet(KEY_TARGETS, HashSet(updated)).apply()
    }

    fun registerChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(l)

    fun unregisterChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(l)

    companion object {
        private const val KEY_TARGETS = "enabled_targets"
        private const val KEY_GREEN_KM = "green_min_per_km"
        private const val KEY_YELLOW_KM = "yellow_min_per_km"
        private const val KEY_GREEN_HOUR = "green_min_per_hour"
        private const val KEY_YELLOW_HOUR = "yellow_min_per_hour"
        private const val KEY_COST_KM = "cost_per_km"
    }
}
