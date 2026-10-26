package com.appuuber.semaforo

import com.appuuber.semaforo.data.SensitiveAppPolicy
import com.appuuber.semaforo.data.TargetApps
import com.appuuber.semaforo.domain.HealthInputs
import com.appuuber.semaforo.domain.HealthLevel
import com.appuuber.semaforo.domain.SystemHealthEvaluator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScopeAndHealthTest {

    private val ok = HealthInputs(
        overlayGranted = true, accessibilityEnabled = true, serviceConnected = true,
        selectedInstalledTargets = 1, selectedTargets = 1, batteryOptimizationIgnored = true
    )

    @Test
    fun `apps financeiros nunca sao selecionaveis`() {
        listOf("br.com.brb.digital", "com.nu.production", "com.itau", "br.com.bb.android", "com.mercadopago.wallet")
            .forEach {
                assertTrue(it, SensitiveAppPolicy.isSensitive(it))
                assertFalse(it, TargetApps.isSelectable(it))
            }
    }

    @Test
    fun `somente apps do catalogo sao selecionaveis`() {
        assertTrue(TargetApps.isSelectable("com.ubercab.driver"))
        assertTrue(TargetApps.isSelectable("com.app99.driver"))
        assertFalse(TargetApps.isSelectable("com.whatsapp"))
        assertFalse(TargetApps.isSelectable("com.android.chrome"))
    }

    @Test
    fun `semaforo VERDE quando tudo esta ok`() {
        assertEquals(HealthLevel.GREEN, SystemHealthEvaluator.evaluate(ok).level)
    }

    @Test
    fun `semaforo VERMELHO quando servico desconectado mesmo com acessibilidade ligada`() {
        val h = SystemHealthEvaluator.evaluate(ok.copy(serviceConnected = false))
        assertEquals(HealthLevel.RED, h.level)
    }

    @Test
    fun `semaforo VERMELHO sem sobreposicao`() {
        assertEquals(HealthLevel.RED, SystemHealthEvaluator.evaluate(ok.copy(overlayGranted = false)).level)
    }

    @Test
    fun `semaforo AMARELO sem app marcado ou com bateria restrita`() {
        assertEquals(HealthLevel.YELLOW, SystemHealthEvaluator.evaluate(ok.copy(selectedTargets = 0, selectedInstalledTargets = 0)).level)
        assertEquals(HealthLevel.YELLOW, SystemHealthEvaluator.evaluate(ok.copy(batteryOptimizationIgnored = false)).level)
    }
}
