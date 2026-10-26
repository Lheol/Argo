package com.appuuber.semaforo.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.appuuber.semaforo.data.PreferencesRepository
import com.appuuber.semaforo.data.SensitiveAppPolicy
import com.appuuber.semaforo.data.TargetApps
import com.appuuber.semaforo.domain.RideEvaluation
import com.appuuber.semaforo.domain.RideOfferParser
import com.appuuber.semaforo.overlay.FloatingSemaphoreManager

/**
 * Serviço de Acessibilidade que realiza a leitura automática dos cards da Uber e 99 em tempo real.
 *
 * Funcionamento (Engenharia Reversa de `app.rotapro.RotaAccessibilityService` e `receita_android.json`):
 * 1. Escuta eventos TYPE_WINDOW_CONTENT_CHANGED e TYPE_WINDOW_STATE_CHANGED.
 * 2. Percorre a árvore de nós (`AccessibilityNodeInfo`) extraindo textos, descrições e `viewIdResourceName`
 *    (como `com.ubercab.driver:id/primary_touch_area` e `com.app99.driver:id/broad_order_container`).
 * 3. Aciona [RideOfferParser] com as expressões regulares reais do Rota Pro para detectar valor (R$),
 *    distância de embarque + viagem (km), tempo (min), nota do passageiro e múltiplas paradas.
 * 4. Exibe o semáforo pequeno via [FloatingSemaphoreManager].
 */
class UberAccessibilityService : AccessibilityService() {

    private lateinit var prefsRepository: PreferencesRepository
    private lateinit var semaphoreManager: FloatingSemaphoreManager

    private var lastSignature: String? = null
    private var lastTriggerTimestamp: Long = 0L

    @Volatile
    private var activeScope: Set<String> = emptySet()

    // Referência forte: o SharedPreferences guarda listeners apenas por referência fraca.
    private val prefsListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> applyScope() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefsRepository = PreferencesRepository(this)
        semaphoreManager = FloatingSemaphoreManager(this)
        instance = this
        prefsRepository.registerChangeListener(prefsListener)
        applyScope()
        notifyConnection(true)
    }

    /**
     * Escopo efetivo = apps MARCADOS pelo usuário ∩ catálogo suportado − apps sensíveis.
     * O próprio app NUNCA entra no escopo (senão o serviço leria o simulador e dispararia o semáforo
     * sozinho). Também é aplicado ao sistema via serviceInfo.packageNames. Se nada estiver marcado,
     * usa um pacote sentinela inexistente (lista vazia/nula poderia significar "todos os apps").
     */
    private fun applyScope() {
        val scope = prefsRepository.getEnabledTargets()
            .filter { TargetApps.isSelectable(it) }
            .toSet()
        activeScope = scope
        try {
            val info = serviceInfo ?: return
            info.packageNames = if (scope.isEmpty()) arrayOf(NO_SCOPE_SENTINEL) else scope.toTypedArray()
            serviceInfo = info
        } catch (_: Exception) {
            // O filtro em onAccessibilityEvent continua garantindo o isolamento.
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: return

        // Defesa em profundidade: pacote precisa estar MARCADO e nunca ser sensível, ANTES de
        // tocar na árvore de nós.
        if (packageName !in activeScope || SensitiveAppPolicy.isSensitive(packageName)) return

        // Usa apenas a janela que originou o evento (nunca varre janelas de outros apps).
        val rootNode = try { event.source } catch (_: Exception) { null } ?: return
        try {
            val rootPkg = try { rootNode.packageName?.toString() } catch (_: Exception) { null }
            if (rootPkg !in activeScope || SensitiveAppPolicy.isSensitive(rootPkg ?: "")) return

            val extractedTexts = mutableListOf<String>()
            val extractedViewIds = mutableListOf<String>()

            try {
                collectNodeTree(rootNode, extractedTexts, extractedViewIds, depth = 0)
            } catch (_: Exception) {
                return
            }

            if (extractedTexts.isEmpty()) return

            val thresholds = prefsRepository.getThresholds()
            val evaluation = RideOfferParser.parseScreenNodes(
                nodeTexts = extractedTexts,
                thresholds = thresholds,
                viewIds = extractedViewIds
            ) ?: return

            val now = System.currentTimeMillis()
            // Evita redesenhar repetidamente o mesmo card em menos de 2.5 segundos (exceto Radar -> Exclusiva)
            if (evaluation.rawSignature == lastSignature && (now - lastTriggerTimestamp) < 2500L) {
                return
            }

            lastSignature = evaluation.rawSignature
            lastTriggerTimestamp = now
            lastEvaluation = evaluation

            semaphoreManager.showEvaluation(evaluation)
            try {
                onEvaluationListener?.invoke(evaluation)
            } catch (_: Exception) {}
        } finally {
            try {
                @Suppress("DEPRECATION")
                if (android.os.Build.VERSION.SDK_INT < 34) {
                    rootNode.recycle()
                }
            } catch (_: Exception) {}
        }
    }

    private fun collectNodeTree(
        node: AccessibilityNodeInfo?,
        textsOutput: MutableList<String>,
        viewIdsOutput: MutableList<String>,
        depth: Int
    ) {
        if (node == null || depth > MAX_TREE_DEPTH || textsOutput.size >= MAX_TEXT_ITEMS) return

        try {
            node.viewIdResourceName?.trim()?.takeIf { it.isNotEmpty() }?.let {
                viewIdsOutput.add(it)
            }
            node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                textsOutput.add(it)
            }
            node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                textsOutput.add(it)
            }

            val childCount = try { node.childCount } catch (_: Exception) { 0 }
            for (i in 0 until childCount) {
                if (textsOutput.size >= MAX_TEXT_ITEMS) break
                val child = try {
                    node.getChild(i)
                } catch (_: Exception) {
                    null
                }
                if (child != null) {
                    try {
                        collectNodeTree(child, textsOutput, viewIdsOutput, depth + 1)
                    } finally {
                        try {
                            @Suppress("DEPRECATION")
                            if (android.os.Build.VERSION.SDK_INT < 34) {
                                child.recycle()
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
        } catch (_: Exception) {}
    }


    override fun onInterrupt() {
        if (::semaphoreManager.isInitialized) semaphoreManager.hide()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        // Chamado quando o usuário desliga o serviço OU o sistema o encerra: o semáforo de saúde
        // precisa refletir isso imediatamente (antes ficava preso no último estado).
        if (::prefsRepository.isInitialized) prefsRepository.unregisterChangeListener(prefsListener)
        if (::semaphoreManager.isInitialized) semaphoreManager.hide()
        if (instance === this) instance = null
        notifyConnection(false)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        notifyConnection(false)
        super.onDestroy()
    }

    private fun notifyConnection(connected: Boolean) {
        onConnectionChanged?.invoke(connected)
    }

    companion object {
        private const val MAX_TREE_DEPTH = 18
        private const val MAX_TEXT_ITEMS = 250

        /** Teto do escopo: apps de corrida suportados + o simulador interno. Sensíveis nunca entram. */
        val ALLOWED_PACKAGES: Set<String> = TargetApps.SUPPORTED_PACKAGES

        /** Pacote inexistente: mantém o filtro do sistema ativo quando nenhum app está marcado. */
        private const val NO_SCOPE_SENTINEL = "invalid.none.selected"

        @Volatile
        var instance: UberAccessibilityService? = null
            private set

        /** true somente enquanto o serviço está realmente conectado (fonte de verdade do semáforo). */
        val isConnected: Boolean get() = instance != null

        @Volatile
        var lastEvaluation: RideEvaluation? = null

        var onEvaluationListener: ((RideEvaluation) -> Unit)? = null
        var onConnectionChanged: ((Boolean) -> Unit)? = null
    }
}

