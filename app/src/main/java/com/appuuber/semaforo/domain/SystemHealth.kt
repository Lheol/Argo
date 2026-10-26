package com.appuuber.semaforo.domain

/** Nível do semáforo de SAÚDE do app (distinto do semáforo de corridas). */
enum class HealthLevel { GREEN, YELLOW, RED }

data class HealthCheck(
    val label: String,
    val ok: Boolean,
    /** Se true e falhar, o nível vira VERMELHO. Se false e falhar, vira AMARELO. */
    val critical: Boolean,
    val hint: String
)

data class SystemHealth(
    val level: HealthLevel,
    val title: String,
    val detail: String,
    val checks: List<HealthCheck>
)

/** Entradas puras (sem Android) para que a regra do semáforo seja testável e determinística. */
data class HealthInputs(
    val overlayGranted: Boolean,
    val accessibilityEnabled: Boolean,
    val serviceConnected: Boolean,
    val selectedInstalledTargets: Int,
    val selectedTargets: Int,
    val batteryOptimizationIgnored: Boolean
)

object SystemHealthEvaluator {

    fun evaluate(i: HealthInputs): SystemHealth {
        val checks = listOf(
            HealthCheck(
                "Sobreposição de tela", i.overlayGranted, critical = true,
                hint = "Ative a permissão para exibir o semáforo sobre o app."
            ),
            HealthCheck(
                "Acessibilidade ligada", i.accessibilityEnabled, critical = true,
                hint = "Ative o serviço em Acessibilidade > Apps instalados."
            ),
            HealthCheck(
                "Serviço conectado", i.serviceConnected, critical = true,
                hint = if (i.accessibilityEnabled) "O Android encerrou o serviço: desligue e ligue de novo."
                else "Aguardando a ativação da acessibilidade."
            ),
            HealthCheck(
                "App de corrida marcado", i.selectedTargets > 0, critical = false,
                hint = "Marque ao menos um app (Uber, 99...) para o leitor atuar."
            ),
            HealthCheck(
                "App marcado instalado", i.selectedTargets == 0 || i.selectedInstalledTargets > 0, critical = false,
                hint = "Os apps marcados não estão instalados neste aparelho."
            ),
            HealthCheck(
                "Sem restrição de bateria", i.batteryOptimizationIgnored, critical = false,
                hint = "Remova a otimização de bateria para evitar que o sistema encerre o serviço."
            )
        )

        val failed = checks.filter { !it.ok }
        return when {
            failed.any { it.critical } -> SystemHealth(
                HealthLevel.RED, "Inoperante",
                failed.first { it.critical }.hint, checks
            )
            failed.isNotEmpty() -> SystemHealth(
                HealthLevel.YELLOW, "Atenção",
                failed.first().hint, checks
            )
            else -> SystemHealth(HealthLevel.GREEN, "Operando", "Tudo certo: lendo corridas dos apps marcados.", checks)
        }
    }
}
