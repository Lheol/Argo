package com.appuuber.semaforo.ui

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.appuuber.semaforo.R
import com.appuuber.semaforo.data.PreferencesRepository
import com.appuuber.semaforo.data.SupportedApp
import com.appuuber.semaforo.data.TargetApps
import com.appuuber.semaforo.data.VehicleExpenseRepository
import com.appuuber.semaforo.domain.DriverThresholds
import com.appuuber.semaforo.domain.FuelRecord
import com.appuuber.semaforo.domain.FuelType
import com.appuuber.semaforo.domain.HealthInputs
import com.appuuber.semaforo.domain.HealthLevel
import com.appuuber.semaforo.domain.MaintenanceRecord
import com.appuuber.semaforo.domain.PaymentMethod
import com.appuuber.semaforo.domain.RideEvaluation
import com.appuuber.semaforo.domain.RideOfferParser
import com.appuuber.semaforo.domain.SystemHealthEvaluator
import com.appuuber.semaforo.overlay.FloatingSemaphoreManager
import com.appuuber.semaforo.service.UberAccessibilityService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Painel de Controle Principal (Lightweight Premium Dark UI) +
 * Módulo REA de Controle de Gastos (Combustível & Manutenção/Peças) do Fiat Argo 2024/25 Vermelho.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: PreferencesRepository
    private lateinit var vehicleRepo: VehicleExpenseRepository
    private lateinit var previewOverlay: FloatingSemaphoreManager
    private val ui = Handler(Looper.getMainLooper())
    private val ptBr = Locale("pt", "BR")

    // Navegação entre abas (Semáforo vs. Meu Argo 2024/25)
    private lateinit var tabBtnSemaforo: Button
    private lateinit var tabBtnArgo: Button
    private lateinit var sectionSemaforo: LinearLayout
    private lateinit var sectionArgo: LinearLayout

    // Componentes da aba Semáforo
    private lateinit var statusLight: StatusLightView
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var checksText: TextView
    private val appSwitches = mutableMapOf<String, Switch>()
    private val appStatusViews = mutableMapOf<String, TextView>()

    private lateinit var inGreenKm: EditText
    private lateinit var inYellowKm: EditText
    private lateinit var inGreenHour: EditText
    private lateinit var inYellowHour: EditText
    private lateinit var inCostKm: EditText

    private lateinit var simMock: View
    private lateinit var simCategory: TextView
    private lateinit var simPrice: TextView
    private lateinit var simPickup: TextView
    private lateinit var simTrip: TextView
    private lateinit var simResult: TextView

    // Componentes da aba Meu Veículo (Fiat Argo 2024/25 Vermelho)
    private lateinit var argoSummaryBanner: TextView
    private lateinit var argoMetricsText: TextView
    private lateinit var argoPaymentSplitText: TextView
    private lateinit var argoRealCostBadge: TextView

    // Formulário de Abastecimento
    private lateinit var inFuelDate: EditText
    private lateinit var inFuelOdometer: EditText
    private lateinit var inFuelTotalAmount: EditText
    private lateinit var inFuelPricePerLiter: EditText
    private lateinit var inFuelStation: EditText
    private lateinit var tvFuelCalculatedLiters: TextView
    private var selectedFuelType: FuelType = FuelType.GASOLINA_COMUM
    private var selectedFuelPayment: PaymentMethod = PaymentMethod.CREDITO
    private val fuelTypeButtons = mutableMapOf<FuelType, Button>()
    private val fuelPaymentButtons = mutableMapOf<PaymentMethod, Button>()
    private lateinit var fuelHistoryContainer: LinearLayout

    // Formulário de Manutenção / Peças Trocadas
    private lateinit var inMaintPartName: EditText
    private lateinit var inMaintDate: EditText
    private lateinit var inMaintMechanic: EditText
    private lateinit var inMaintPartCost: EditText
    private lateinit var inMaintLaborCost: EditText
    private lateinit var inMaintWhereBought: EditText
    private lateinit var inMaintOdometer: EditText
    private lateinit var inMaintNotes: EditText
    private lateinit var tvMaintCalculatedTotal: TextView
    private var selectedMaintPayment: PaymentMethod = PaymentMethod.CREDITO
    private val maintPaymentButtons = mutableMapOf<PaymentMethod, Button>()
    private lateinit var maintHistoryContainer: LinearLayout

    private val healthTicker = object : Runnable {
        override fun run() {
            refreshHealth()
            ui.postDelayed(this, 1500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_AppUuber)
        super.onCreate(savedInstanceState)
        prefs = PreferencesRepository(this)
        vehicleRepo = VehicleExpenseRepository(this)
        previewOverlay = FloatingSemaphoreManager(this)
        setContentView(buildContent())
        loadThresholds()
        syncSwitchesFromPrefs()
        refreshArgoDashboard()
    }

    override fun onResume() {
        super.onResume()
        refreshHealth()
        refreshArgoDashboard()
        ui.removeCallbacks(healthTicker)
        ui.postDelayed(healthTicker, 1500L)
        UberAccessibilityService.onConnectionChanged = { ui.post { refreshHealth() } }
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(healthTicker)
        UberAccessibilityService.onConnectionChanged = null
    }

    override fun onDestroy() {
        previewOverlay.hide()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ Saúde do Sistema

    private fun isAccessibilityEnabled(): Boolean {
        val me = ComponentName(this, UberAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return enabled.split(":").mapNotNull { ComponentName.unflattenFromString(it) }.any { it == me }
    }

    private fun isBatteryUnrestricted(): Boolean =
        (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    private fun isInstalled(pkg: String): Boolean = try {
        packageManager.getPackageInfo(pkg, 0); true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun refreshHealth() {
        val selected = prefs.getEnabledTargets()
        val health = SystemHealthEvaluator.evaluate(
            HealthInputs(
                overlayGranted = previewOverlay.canDrawOverlays(),
                accessibilityEnabled = isAccessibilityEnabled(),
                serviceConnected = UberAccessibilityService.isConnected,
                selectedInstalledTargets = selected.count { isInstalled(it) },
                selectedTargets = selected.size,
                batteryOptimizationIgnored = isBatteryUnrestricted()
            )
        )
        statusLight.setLevel(health.level, animate = true)
        statusTitle.text = health.title
        statusTitle.setTextColor(
            when (health.level) {
                HealthLevel.GREEN -> Color.parseColor("#10B981")
                HealthLevel.YELLOW -> Color.parseColor("#F59E0B")
                HealthLevel.RED -> Color.parseColor("#F43F5E")
            }
        )
        statusDetail.text = health.detail
        checksText.text = health.checks.joinToString("\n") { (if (it.ok) "✔ " else "⚠ ") + it.label }

        appStatusViews.forEach { (pkg, tv) ->
            val inst = isInstalled(pkg)
            tv.text = if (inst) "Instalado no aparelho" else "Não instalado"
            tv.setTextColor(Color.parseColor(if (inst) "#10B981" else "#64748B"))
        }
    }

    // --------------------------------------------------------------- Layout Otimizado

    private fun buildContent(): View {
        val d = resources.displayMetrics.density
        val dp: (Int) -> Int = { v -> (v * d).toInt() }

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#060911"))
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(40))
        }

        // Header com branding + Identificação do Argo 2024/25 Vermelho
        root.addView(text("Lucronline • Semáforo & Frota REA", 21f, "#F8FAFC", bold = true))
        root.addView(text("Cálculo de corridas em tempo real + Gestão do Fiat Argo 2024/25 Vermelho.", 12.5f, "#94A3B8").apply {
            setPadding(0, dp(3), 0, dp(14))
        })

        // Seletor de Abas no Topo (Semáforo vs. Gastos do Argo 2024/25)
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#0F172A"))
                setStroke(dp(1), Color.parseColor("#1E293B"))
            }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) }
        }

        tabBtnSemaforo = smallBtn("🚦 Semáforo", "#2563EB", dp) { switchTab(showArgo = false, dp = dp) }
        tabBtnArgo = smallBtn("🚗 Argo 24/25 (Gastos)", "#DC2626", dp) { switchTab(showArgo = true, dp = dp) }
        tabBar.addView(tabBtnSemaforo, weight(dp, 6))
        tabBar.addView(tabBtnArgo, weight(dp, 0))
        root.addView(tabBar)

        // =========================================================================
        // SEÇÃO 1: PAINEL DO SEMÁFORO (Corridas Uber / 99 / inDrive)
        // =========================================================================
        sectionSemaforo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Atalho rápido no topo do Semáforo para abrir o Controle de Gastos do Argo
        val argoQuickCard = card(dp).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#1A0B12")) // Tom rubro escuro elegante para o Argo Vermelho
                setStroke(dp(1), Color.parseColor("#EF4444"))
            }
        }
        argoQuickCard.addView(text("🚗 Veículo Ativo: Fiat Argo 2024/25 • Vermelho", 14.5f, "#FCA5A5", bold = true))
        argoSummaryBanner = text("Toque abaixo para registrar abastecimento ou troca de peças.", 12f, "#E2E8F0").apply {
            setPadding(0, dp(4), 0, dp(8))
        }
        argoQuickCard.addView(argoSummaryBanner)
        argoQuickCard.addView(button("⛽ / 🔧 Abrir Controle de Gasolina e Manutenção", "#DC2626", dp) {
            switchTab(showArgo = true, dp = dp)
        })
        sectionSemaforo.addView(argoQuickCard)

        // --- Card 1: Saúde Operacional com Semáforo Vetorial
        val health = card(dp)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusLight = StatusLightView(this)
        row.addView(statusLight)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), 0, 0, 0)
        }
        statusTitle = text("Conectando...", 18.5f, "#FFFFFF", bold = true)
        statusDetail = text("", 12.5f, "#CBD5E1").apply { setPadding(0, dp(2), 0, dp(8)) }
        checksText = text("", 11.5f, "#94A3B8")
        col.addView(statusTitle)
        col.addView(statusDetail)
        col.addView(checksText)
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        health.addView(row)
        sectionSemaforo.addView(health)

        // --- Card 2: Seleção Manual de Alvos (Anti-Block Bancário)
        val targets = card(dp)
        targets.addView(title("1. Apps Monitorados"))
        targets.addView(text("O leitor atua única e exclusivamente nos apps marcados abaixo. Instituições bancárias e financeiras são sempre bloqueadas.", 12f, "#94A3B8").apply {
            setPadding(0, 0, 0, dp(12))
        })
        TargetApps.SUPPORTED.forEach { targets.addView(appRow(it, dp)) }
        sectionSemaforo.addView(targets)

        // --- Card 3: Permissões e Resiliência
        val perms = card(dp)
        perms.addView(title("2. Permissões de Sistema"))
        perms.addView(button("Sobreposição de Tela (Overlay)", "#2563EB", dp) { openOverlaySettings() })
        perms.addView(button("Ativar Serviço de Acessibilidade", "#059669", dp) { openAccessibility() })
        perms.addView(button("Sem Restrição de Bateria (Estabilidade)", "#7C3AED", dp) { requestBatteryExemption() })
        sectionSemaforo.addView(perms)

        // --- Card 4: Simulador de Chamada
        val sim = card(dp)
        sim.addView(title("3. Teste em Tempo Real"))
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btns.addView(smallBtn("🟢 Boa", "#10B981", dp) { simulate("UberX • Exclusivo", 24.80, 1.1, 3, 7.4, 16) }, weight(dp, 6))
        btns.addView(smallBtn("🟡 Média", "#F59E0B", dp) { simulate("UberX", 15.90, 1.8, 5, 7.7, 21) }, weight(dp, 6))
        btns.addView(smallBtn("🔴 Ruim", "#F43F5E", dp) { simulate("UberX Poupa", 9.40, 2.9, 8, 6.8, 22) }, weight(dp, 0))
        sim.addView(btns)

        val mock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#090D16"))
                setStroke(dp(1), Color.parseColor("#1E293B"))
            }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        simMock = mock
        simCategory = text("", 12f, "#38BDF8", bold = true)
        simPrice = text("", 24f, "#FFFFFF", bold = true)
        simPickup = text("", 13f, "#CBD5E1")
        simTrip = text("", 13f, "#94A3B8")
        listOf(simCategory, simPrice, simPickup, simTrip).forEach { mock.addView(it) }
        sim.addView(mock)

        simResult = text("Toque em um cenário acima para disparar o semáforo.", 12.5f, "#94A3B8", bold = false).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(Color.parseColor("#090D16")) }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
        }
        sim.addView(simResult)
        sectionSemaforo.addView(sim)

        // --- Card 5: Configuração de Metas Financeiras
        val cfg = card(dp)
        cfg.addView(title("4. Metas do Semáforo"))
        inGreenKm = input(cfg, "🟢 Verde: Mínimo R$/km", dp)
        inGreenHour = input(cfg, "🟢 Verde: Mínimo R$/hora", dp)
        inYellowKm = input(cfg, "🟡 Amarelo: Mínimo R$/km", dp)
        inYellowHour = input(cfg, "🟡 Amarelo: Mínimo R$/hora", dp)
        inCostKm = input(cfg, "⛽ Custo do Veículo por km (Argo 24/25)", dp)
        cfg.addView(button("Salvar Preferências", "#3B82F6", dp) { saveThresholds() })
        sectionSemaforo.addView(cfg)

        root.addView(sectionSemaforo)

        // =========================================================================
        // SEÇÃO 2: CONTROLE DE GASTOS — FIAT ARGO 2024/25 VERMELHO (REA)
        // =========================================================================
        sectionArgo = buildArgoExpenseSection(dp)
        root.addView(sectionArgo)

        // Inicia exibindo a aba do Argo ou Semáforo (deixamos ambas acessíveis com 1 toque)
        switchTab(showArgo = false, dp = dp)

        scroll.addView(root)
        return scroll
    }

    private fun buildArgoExpenseSection(dp: (Int) -> Int): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        val todayStr = SimpleDateFormat("dd/MM/yyyy", ptBr).format(Date())

        // --- Card A: Painel Consolidado do Fiat Argo 2024/25 Vermelho
        val summaryCard = card(dp).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#180A11"))
                setStroke(dp(1), Color.parseColor("#EF4444"))
            }
        }
        summaryCard.addView(text("🚗 Fiat Argo 2024/25 • Vermelho", 18f, "#FFFFFF", bold = true))
        summaryCard.addView(text("Painel REA de Custos Reais (Abastecimentos por Odômetro + Peças e Mão de Obra)", 12f, "#FCA5A5").apply {
            setPadding(0, dp(2), 0, dp(12))
        })

        argoMetricsText = text("", 13f, "#F8FAFC").apply {
            setLineSpacing(0f, 1.25f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#090D16"))
            }
        }
        summaryCard.addView(argoMetricsText)

        argoPaymentSplitText = text("", 12f, "#CBD5E1").apply {
            setPadding(0, dp(10), 0, dp(6))
        }
        summaryCard.addView(argoPaymentSplitText)

        argoRealCostBadge = text("", 14f, "#10B981", bold = true).apply {
            setPadding(0, dp(4), 0, dp(8))
        }
        summaryCard.addView(argoRealCostBadge)

        summaryCard.addView(button("⚡ Aplicar Custo/km do Argo no Semáforo", "#059669", dp) {
            applyArgoCostToSemaphore()
        })
        container.addView(summaryCard)

        // --- Card B: Controle de Abastecimento (Todas as vezes que for abastecer)
        val fuelCard = card(dp)
        fuelCard.addView(title("⛽ 1. Novo Abastecimento (Argo 24/25)"))
        fuelCard.addView(text("Preencha os dados sempre que encostar no posto para calcular litros, média km/L e gasto no crédito ou débito.", 12f, "#94A3B8").apply {
            setPadding(0, 0, 0, dp(8))
        })

        inFuelOdometer = input(fuelCard, "📍 Quilometragem atual no painel (km)", dp, decimal = true, hint = "Ex: 28450")
        inFuelTotalAmount = input(fuelCard, "💵 Quanto vai abastecer (Valor Total R$)", dp, decimal = true, hint = "Ex: 150,00")

        // Seletor de Tipo de Combustível
        fuelCard.addView(text("⛽ Qual tipo de combustível:", 12.5f, "#CBD5E1").apply { setPadding(0, dp(10), 0, dp(6)) })
        val fuelRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val fuelRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }
        }
        val btnGasComum = smallBtn(FuelType.GASOLINA_COMUM.label, "#2563EB", dp) { selectFuelType(FuelType.GASOLINA_COMUM) }
        val btnGasAdit = smallBtn(FuelType.GASOLINA_ADITIVADA.label, "#1E293B", dp) { selectFuelType(FuelType.GASOLINA_ADITIVADA) }
        val btnEtanol = smallBtn(FuelType.ETANOL.label, "#1E293B", dp) { selectFuelType(FuelType.ETANOL) }
        val btnGnv = smallBtn(FuelType.GNV.label, "#1E293B", dp) { selectFuelType(FuelType.GNV) }

        fuelTypeButtons[FuelType.GASOLINA_COMUM] = btnGasComum
        fuelTypeButtons[FuelType.GASOLINA_ADITIVADA] = btnGasAdit
        fuelTypeButtons[FuelType.ETANOL] = btnEtanol
        fuelTypeButtons[FuelType.GNV] = btnGnv

        fuelRow1.addView(btnGasComum, weight(dp, 6))
        fuelRow1.addView(btnGasAdit, weight(dp, 0))
        fuelRow2.addView(btnEtanol, weight(dp, 6))
        fuelRow2.addView(btnGnv, weight(dp, 0))
        fuelCard.addView(fuelRow1)
        fuelCard.addView(fuelRow2)

        inFuelPricePerLiter = input(fuelCard, "🏷️ Valor do combustível por litro (R$/L)", dp, decimal = true, hint = "Ex: 5,89")

        tvFuelCalculatedLiters = text("💧 Litros calculados: preencha o valor e o preço/L", 12.5f, "#38BDF8", bold = true).apply {
            setPadding(0, dp(8), 0, dp(4))
        }
        fuelCard.addView(tvFuelCalculatedLiters)

        // Watchers para cálculo instantâneo de litros abastecidos
        val fuelWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { updateFuelLitersPreview() }
        }
        inFuelTotalAmount.addTextChangedListener(fuelWatcher)
        inFuelPricePerLiter.addTextChangedListener(fuelWatcher)

        // Seletor de Forma de Pagamento (Crédito vs Débito vs PIX)
        fuelCard.addView(text("💳 Pagou no Crédito ou Débito?", 12.5f, "#CBD5E1").apply { setPadding(0, dp(10), 0, dp(6)) })
        val payRowFuel = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnFuelCred = smallBtn("💳 Crédito", "#2563EB", dp) { selectFuelPayment(PaymentMethod.CREDITO) }
        val btnFuelDeb = smallBtn("💳 Débito", "#1E293B", dp) { selectFuelPayment(PaymentMethod.DEBITO) }
        val btnFuelPix = smallBtn("💠 PIX/Din.", "#1E293B", dp) { selectFuelPayment(PaymentMethod.PIX_DINHEIRO) }
        fuelPaymentButtons[PaymentMethod.CREDITO] = btnFuelCred
        fuelPaymentButtons[PaymentMethod.DEBITO] = btnFuelDeb
        fuelPaymentButtons[PaymentMethod.PIX_DINHEIRO] = btnFuelPix
        payRowFuel.addView(btnFuelCred, weight(dp, 6))
        payRowFuel.addView(btnFuelDeb, weight(dp, 6))
        payRowFuel.addView(btnFuelPix, weight(dp, 0))
        fuelCard.addView(payRowFuel)

        inFuelDate = inputText(fuelCard, "📅 Data do abastecimento", dp, defaultText = todayStr, hint = "dd/mm/aaaa")
        inFuelStation = inputText(fuelCard, "🏪 Posto / Observação (opcional)", dp, defaultText = "", hint = "Ex: Posto Shell Ipiranga")

        fuelCard.addView(button("⛽ Registrar Abastecimento no Argo", "#DC2626", dp) { saveFuelRecord() })

        fuelCard.addView(text("Histórico de Abastecimentos Registrados:", 13.5f, "#FFFFFF", bold = true).apply {
            setPadding(0, dp(16), 0, dp(6))
        })
        fuelHistoryContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fuelCard.addView(fuelHistoryContainer)
        container.addView(fuelCard)

        // --- Card C: Controle de Peças Trocadas e Manutenção (Argo 2024/25)
        val maintCard = card(dp)
        maintCard.addView(title("🔧 2. Peças Trocadas & Manutenção (Argo 24/25)"))
        maintCard.addView(text("Registre as peças que já foram trocadas, data da troca, quem fez o serviço, valor da mão de obra, valor da peça e onde comprou.", 12f, "#94A3B8").apply {
            setPadding(0, 0, 0, dp(8))
        })

        // Atalhos rápidos de peças comuns do Fiat Argo
        maintCard.addView(text("Atalhos rápidos de peças do Argo:", 12f, "#38BDF8").apply { setPadding(0, 0, 0, dp(6)) })
        val presetRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val presetRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }
        }
        presetRow1.addView(smallBtn("Óleo + Filtro", "#1E293B", dp) { inMaintPartName.setText("Troca de Óleo 0W20 + Filtro") }, weight(dp, 6))
        presetRow1.addView(smallBtn("Pastilha Freio", "#1E293B", dp) { inMaintPartName.setText("Pastilha de Freio Dianteira") }, weight(dp, 6))
        presetRow1.addView(smallBtn("Pneus", "#1E293B", dp) { inMaintPartName.setText("Jogo de Pneus Aro 14/15") }, weight(dp, 0))

        presetRow2.addView(smallBtn("Velas / Cabo", "#1E293B", dp) { inMaintPartName.setText("Jogo de Velas de Ignição") }, weight(dp, 6))
        presetRow2.addView(smallBtn("Bateria", "#1E293B", dp) { inMaintPartName.setText("Bateria 60Ah EFB/SLI") }, weight(dp, 6))
        presetRow2.addView(smallBtn("Filtros Ar", "#1E293B", dp) { inMaintPartName.setText("Filtro de Ar do Motor + Cabine") }, weight(dp, 0))
        maintCard.addView(presetRow1)
        maintCard.addView(presetRow2)

        inMaintPartName = inputText(maintCard, "🔩 Qual peça do veículo foi trocada?", dp, defaultText = "", hint = "Ex: Pastilha de freio, Amortecedor, Correia...")
        inMaintDate = inputText(maintCard, "📅 Data em que foi feita a troca", dp, defaultText = todayStr, hint = "Ex: 26/10/2026")
        inMaintMechanic = inputText(maintCard, "👨‍🔧 Feita por quem? (Mecânico / Oficina / Concessionária)", dp, defaultText = "", hint = "Ex: Mecânico João / Concessionária Fiat")
        inMaintPartCost = input(maintCard, "🏷️ Quanto foi da Peça? (R$)", dp, decimal = true, hint = "Ex: 240,00")
        inMaintLaborCost = input(maintCard, "🛠️ Quanto foi de Mão de Obra? (R$)", dp, decimal = true, hint = "Ex: 120,00")

        tvMaintCalculatedTotal = text("💰 Total (Peça + Mão de Obra): R$ 0,00", 12.5f, "#10B981", bold = true).apply {
            setPadding(0, dp(8), 0, dp(4))
        }
        maintCard.addView(tvMaintCalculatedTotal)

        val maintWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { updateMaintTotalPreview() }
        }
        inMaintPartCost.addTextChangedListener(maintWatcher)
        inMaintLaborCost.addTextChangedListener(maintWatcher)

        inMaintWhereBought = inputText(maintCard, "🏪 Onde comprou a peça? (Loja / Site / Auto Peças)", dp, defaultText = "", hint = "Ex: Mercado Livre, AutoZone, Concessionária Fiat...")
        inMaintOdometer = input(maintCard, "📍 Quilometragem do Argo na troca (km - opcional)", dp, decimal = true, hint = "Ex: 28000")

        maintCard.addView(text("💳 Forma de pagamento:", 12.5f, "#CBD5E1").apply { setPadding(0, dp(10), 0, dp(6)) })
        val payRowMaint = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnMaintCred = smallBtn("💳 Crédito", "#2563EB", dp) { selectMaintPayment(PaymentMethod.CREDITO) }
        val btnMaintDeb = smallBtn("💳 Débito", "#1E293B", dp) { selectMaintPayment(PaymentMethod.DEBITO) }
        val btnMaintPix = smallBtn("💠 PIX/Din.", "#1E293B", dp) { selectMaintPayment(PaymentMethod.PIX_DINHEIRO) }
        maintPaymentButtons[PaymentMethod.CREDITO] = btnMaintCred
        maintPaymentButtons[PaymentMethod.DEBITO] = btnMaintDeb
        maintPaymentButtons[PaymentMethod.PIX_DINHEIRO] = btnMaintPix
        payRowMaint.addView(btnMaintCred, weight(dp, 6))
        payRowMaint.addView(btnMaintDeb, weight(dp, 6))
        payRowMaint.addView(btnMaintPix, weight(dp, 0))
        maintCard.addView(payRowMaint)

        inMaintNotes = inputText(maintCard, "📝 Outros detalhes (Marca da peça, garantia, observações)", dp, defaultText = "", hint = "Ex: Marca Cobreq, 6 meses de garantia")

        maintCard.addView(button("🔧 Salvar Peça Trocada / Manutenção", "#2563EB", dp) { saveMaintenanceRecord() })

        maintCard.addView(text("Histórico de Peças Já Trocadas no Argo:", 13.5f, "#FFFFFF", bold = true).apply {
            setPadding(0, dp(16), 0, dp(6))
        })
        maintHistoryContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        maintCard.addView(maintHistoryContainer)
        container.addView(maintCard)

        return container
    }

    // ------------------------------------------------------------------ Lógica das Abas & Argo 24/25

    private fun switchTab(showArgo: Boolean, dp: (Int) -> Int) {
        sectionSemaforo.visibility = if (showArgo) View.GONE else View.VISIBLE
        sectionArgo.visibility = if (showArgo) View.VISIBLE else View.GONE

        styleSelectableButton(tabBtnSemaforo, active = !showArgo, activeHex = "#2563EB", dp = dp)
        styleSelectableButton(tabBtnArgo, active = showArgo, activeHex = "#DC2626", dp = dp)
    }

    private fun selectFuelType(type: FuelType) {
        selectedFuelType = type
        val dp: (Int) -> Int = { v -> (v * resources.displayMetrics.density).toInt() }
        fuelTypeButtons.forEach { (t, btn) ->
            styleSelectableButton(btn, active = (t == type), activeHex = "#2563EB", dp = dp)
        }
    }

    private fun selectFuelPayment(method: PaymentMethod) {
        selectedFuelPayment = method
        val dp: (Int) -> Int = { v -> (v * resources.displayMetrics.density).toInt() }
        fuelPaymentButtons.forEach { (m, btn) ->
            styleSelectableButton(btn, active = (m == method), activeHex = "#059669", dp = dp)
        }
    }

    private fun selectMaintPayment(method: PaymentMethod) {
        selectedMaintPayment = method
        val dp: (Int) -> Int = { v -> (v * resources.displayMetrics.density).toInt() }
        maintPaymentButtons.forEach { (m, btn) ->
            styleSelectableButton(btn, active = (m == method), activeHex = "#059669", dp = dp)
        }
    }

    private fun styleSelectableButton(btn: Button, active: Boolean, activeHex: String, dp: (Int) -> Int) {
        val bg = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(Color.parseColor(if (active) activeHex else "#1E293B"))
            if (active) setStroke(dp(1), Color.parseColor("#60A5FA"))
        }
        btn.background = RippleDrawable(ColorStateList.valueOf(Color.parseColor("#33FFFFFF")), bg, null)
        btn.setTextColor(Color.parseColor(if (active) "#FFFFFF" else "#94A3B8"))
    }

    private fun parseDoubleInput(e: EditText): Double? =
        e.text.toString().trim().replace(",", ".").toDoubleOrNull()

    private fun updateFuelLitersPreview() {
        val total = parseDoubleInput(inFuelTotalAmount) ?: 0.0
        val priceL = parseDoubleInput(inFuelPricePerLiter) ?: 0.0
        if (total > 0.0 && priceL > 0.0) {
            val liters = total / priceL
            tvFuelCalculatedLiters.text = String.format(ptBr, "💧 Volume calculado: %.2f Litros (%s)", liters, selectedFuelType.label)
        } else {
            tvFuelCalculatedLiters.text = "💧 Litros calculados: preencha quanto vai abastecer e o valor do litro"
        }
    }

    private fun updateMaintTotalPreview() {
        val part = parseDoubleInput(inMaintPartCost) ?: 0.0
        val labor = parseDoubleInput(inMaintLaborCost) ?: 0.0
        val total = part + labor
        tvMaintCalculatedTotal.text = String.format(
            ptBr,
            "💰 Total do Serviço: R$ %.2f (Peça: R$ %.2f + Mão de Obra: R$ %.2f)",
            total, part, labor
        )
    }

    private fun saveFuelRecord() {
        val odometer = parseDoubleInput(inFuelOdometer)
        val totalPaid = parseDoubleInput(inFuelTotalAmount)
        val priceLiter = parseDoubleInput(inFuelPricePerLiter)
        val dateStr = inFuelDate.text.toString().trim().ifBlank {
            SimpleDateFormat("dd/MM/yyyy", ptBr).format(Date())
        }

        if (odometer == null || odometer <= 0.0) {
            Toast.makeText(this, "Informe a quilometragem atual do Argo no painel.", Toast.LENGTH_SHORT).show()
            return
        }
        if (totalPaid == null || totalPaid <= 0.0) {
            Toast.makeText(this, "Informe quanto vai abastecer (R$).", Toast.LENGTH_SHORT).show()
            return
        }
        if (priceLiter == null || priceLiter <= 0.0) {
            Toast.makeText(this, "Informe o valor do litro do combustível (R$/L).", Toast.LENGTH_SHORT).show()
            return
        }

        val record = FuelRecord(
            dateBr = dateStr,
            odometerKm = odometer,
            totalAmountPaid = totalPaid,
            fuelType = selectedFuelType,
            pricePerLiter = priceLiter,
            paymentMethod = selectedFuelPayment,
            stationName = inFuelStation.text.toString().trim()
        )

        if (vehicleRepo.addFuelRecord(record)) {
            inFuelTotalAmount.setText("")
            inFuelStation.setText("")
            refreshArgoDashboard()
            Toast.makeText(
                this,
                String.format(ptBr, "Abastecimento salvo! %.2f L de %s no %s.", record.liters, record.fuelType.label, record.paymentMethod.label),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun saveMaintenanceRecord() {
        val partName = inMaintPartName.text.toString().trim()
        val dateStr = inMaintDate.text.toString().trim().ifBlank {
            SimpleDateFormat("dd/MM/yyyy", ptBr).format(Date())
        }
        val mechanic = inMaintMechanic.text.toString().trim().ifBlank { "Não informado" }
        val partCost = parseDoubleInput(inMaintPartCost) ?: 0.0
        val laborCost = parseDoubleInput(inMaintLaborCost) ?: 0.0
        val whereBought = inMaintWhereBought.text.toString().trim().ifBlank { "Não informado" }
        val odometer = parseDoubleInput(inMaintOdometer) ?: 0.0
        val notes = inMaintNotes.text.toString().trim()

        if (partName.isBlank()) {
            Toast.makeText(this, "Informe qual peça do Argo foi trocada.", Toast.LENGTH_SHORT).show()
            return
        }
        if (partCost <= 0.0 && laborCost <= 0.0) {
            Toast.makeText(this, "Informe o valor da peça e/ou o valor da mão de obra.", Toast.LENGTH_SHORT).show()
            return
        }

        val record = MaintenanceRecord(
            partName = partName,
            dateBr = dateStr,
            mechanicOrWorkshop = mechanic,
            laborCost = laborCost,
            partCost = partCost,
            whereBought = whereBought,
            odometerKm = odometer,
            paymentMethod = selectedMaintPayment,
            notes = notes
        )

        if (vehicleRepo.addMaintenanceRecord(record)) {
            inMaintPartName.setText("")
            inMaintPartCost.setText("")
            inMaintLaborCost.setText("")
            inMaintWhereBought.setText("")
            inMaintMechanic.setText("")
            inMaintNotes.setText("")
            refreshArgoDashboard()
            Toast.makeText(
                this,
                String.format(ptBr, "Peça '%s' registrada! Total R$ %.2f.", record.partName, record.totalCost),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun applyArgoCostToSemaphore() {
        val summary = vehicleRepo.getFinancialSummary()
        if (summary.realCostPerKm <= 0.0) {
            Toast.makeText(this, "Registre ao menos um abastecimento ou manutenção para calcular o custo real por km.", Toast.LENGTH_LONG).show()
            return
        }
        val current = prefs.getThresholds()
        val updated = current.copy(costPerKm = summary.realCostPerKm).sanitized()
        prefs.saveThresholds(updated)
        inCostKm.setText(String.format(Locale.US, "%.2f", updated.costPerKm))
        Toast.makeText(
            this,
            String.format(ptBr, "Custo de R$ %.2f/km do Argo 24/25 aplicado ao Semáforo!", updated.costPerKm),
            Toast.LENGTH_LONG
        ).show()
    }

    private fun refreshArgoDashboard() {
        val d = resources.displayMetrics.density
        val dp: (Int) -> Int = { v -> (v * d).toInt() }

        val summary = vehicleRepo.getFinancialSummary()
        val fuels = vehicleRepo.getFuelRecords()
        val maints = vehicleRepo.getMaintenanceRecords()

        // Atualiza banner rápido na aba do Semáforo
        argoSummaryBanner.text = String.format(
            ptBr,
            "Odômetro: %.0f km • Combustível: R$ %.2f (%d abast.) • Manutenção: R$ %.2f (%d peças) • Custo Real: R$ %.2f/km",
            summary.currentOdometerKm,
            summary.totalFuelSpent,
            summary.fuelCount,
            summary.totalMaintenanceSpent,
            summary.maintenanceCount,
            if (summary.realCostPerKm > 0.0) summary.realCostPerKm else prefs.getThresholds().costPerKm
        )

        // Atualiza métricas detalhadas na aba do Argo 2024/25
        argoMetricsText.text = String.format(
            ptBr,
            "📍 Odômetro Atual: %.0f km (Trecho monitorado: %.0f km)\n" +
                "⛽ Gasto com Combustível: R$ %.2f (%.1f L • Média: %.1f km/L)\n" +
                "🔧 Gasto com Manutenção: R$ %.2f\n" +
                "   ├─ 🔩 Só Peças: R$ %.2f\n" +
                "   └─ 🛠️ Só Mão de Obra: R$ %.2f\n" +
                "💰 Total Geral Investido no Argo: R$ %.2f",
            summary.currentOdometerKm,
            summary.trackedDistanceKm,
            summary.totalFuelSpent,
            summary.totalLiters,
            summary.averageConsumptionKmPerLiter,
            summary.totalMaintenanceSpent,
            summary.totalPartsSpent,
            summary.totalLaborSpent,
            summary.grandTotalSpent
        )

        argoPaymentSplitText.text = String.format(
            ptBr,
            "💳 No Crédito: R$ %.2f (Comb: R$ %.2f | Peças/M.O: R$ %.2f)\n" +
                "💳 No Débito: R$ %.2f (Comb: R$ %.2f | Peças/M.O: R$ %.2f)",
            summary.totalCredito, summary.fuelSpentCredito, summary.maintenanceSpentCredito,
            summary.totalDebito, summary.fuelSpentDebito, summary.maintenanceSpentDebito
        )

        argoRealCostBadge.text = if (summary.realCostPerKm > 0.0) {
            String.format(
                ptBr,
                "⚡ Custo Real Calculado: R$ %.2f/km (Combustível: R$ %.2f/km + Manutenção: R$ %.2f/km)",
                summary.realCostPerKm, summary.fuelCostPerKm, summary.maintenanceCostPerKm
            )
        } else {
            "⚡ Cadastre seus abastecimentos para calcular o R$/km exato do Argo 24/25."
        }

        // Renderiza lista de abastecimentos
        fuelHistoryContainer.removeAllViews()
        if (fuels.isEmpty()) {
            fuelHistoryContainer.addView(text("Nenhum abastecimento registrado ainda.", 12f, "#64748B"))
        } else {
            fuels.forEach { r ->
                fuelHistoryContainer.addView(buildFuelHistoryItem(r, dp))
            }
        }

        // Renderiza lista de peças trocadas
        maintHistoryContainer.removeAllViews()
        if (maints.isEmpty()) {
            maintHistoryContainer.addView(text("Nenhuma peça ou manutenção registrada ainda.", 12f, "#64748B"))
        } else {
            maints.forEach { m ->
                maintHistoryContainer.addView(buildMaintenanceHistoryItem(m, dp))
            }
        }
    }

    private fun buildFuelHistoryItem(r: FuelRecord, dp: (Int) -> Int): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#090D16"))
                setStroke(dp(1), Color.parseColor("#1E293B"))
            }
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleTxt = String.format(ptBr, "⛽ %s • R$ %.2f (%s)", r.fuelType.label, r.totalAmountPaid, r.paymentMethod.label)
        topRow.addView(text(titleTxt, 13.5f, "#FFFFFF", bold = true), LinearLayout.LayoutParams(0, -2, 1f))

        val delBtn = text("Excluir ✕", 11.5f, "#F43F5E", bold = true).apply {
            setPadding(dp(8), dp(4), dp(4), dp(4))
            setOnClickListener {
                vehicleRepo.deleteFuelRecord(r.id)
                refreshArgoDashboard()
            }
        }
        topRow.addView(delBtn)
        box.addView(topRow)

        val stationInfo = if (r.stationName.isNotBlank()) " • Posto: ${r.stationName}" else ""
        val detailTxt = String.format(
            ptBr,
            "📅 %s • 📍 %.0f km • 💧 %.2f L a R$ %.2f/L%s",
            r.dateBr, r.odometerKm, r.liters, r.pricePerLiter, stationInfo
        )
        box.addView(text(detailTxt, 12f, "#94A3B8").apply { setPadding(0, dp(4), 0, 0) })
        return box
    }

    private fun buildMaintenanceHistoryItem(m: MaintenanceRecord, dp: (Int) -> Int): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#090D16"))
                setStroke(dp(1), Color.parseColor("#1E293B"))
            }
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleTxt = String.format(ptBr, "🔩 %s • Total: R$ %.2f", m.partName, m.totalCost)
        topRow.addView(text(titleTxt, 13.5f, "#38BDF8", bold = true), LinearLayout.LayoutParams(0, -2, 1f))

        val delBtn = text("Excluir ✕", 11.5f, "#F43F5E", bold = true).apply {
            setPadding(dp(8), dp(4), dp(4), dp(4))
            setOnClickListener {
                vehicleRepo.deleteMaintenanceRecord(m.id)
                refreshArgoDashboard()
            }
        }
        topRow.addView(delBtn)
        box.addView(topRow)

        val odoTxt = if (m.odometerKm > 0.0) String.format(ptBr, " • 📍 %.0f km", m.odometerKm) else ""
        val line1 = String.format(
            ptBr,
            "📅 Troca em: %s%s • 💳 %s",
            m.dateBr, odoTxt, m.paymentMethod.label
        )
        val line2 = String.format(
            ptBr,
            "🏷️ Peça: R$ %.2f + 🛠️ Mão de Obra: R$ %.2f",
            m.partCost, m.laborCost
        )
        val line3 = "👨‍🔧 Trocado por: ${m.mechanicOrWorkshop} • 🏪 Comprado em: ${m.whereBought}"

        box.addView(text(line1, 12f, "#E2E8F0").apply { setPadding(0, dp(4), 0, 0) })
        box.addView(text(line2, 12f, "#10B981").apply { setPadding(0, dp(2), 0, 0) })
        box.addView(text(line3, 12f, "#94A3B8").apply { setPadding(0, dp(2), 0, 0) })
        if (m.notes.isNotBlank()) {
            box.addView(text("📝 Obs: ${m.notes}", 11.5f, "#CBD5E1").apply { setPadding(0, dp(2), 0, 0) })
        }
        return box
    }

    private fun appRow(app: SupportedApp, dp: (Int) -> Int): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        names.addView(text(app.displayName, 15f, "#FFFFFF", bold = true))
        val st = text("", 12f, "#64748B")
        appStatusViews[app.packageName] = st
        names.addView(st)
        r.addView(names, LinearLayout.LayoutParams(0, -2, 1f))

        val sw = Switch(this).apply {
            setOnCheckedChangeListener { v, checked ->
                v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                prefs.setTargetEnabled(app.packageName, checked)
                refreshHealth()
            }
        }
        appSwitches[app.packageName] = sw
        r.addView(sw)
        return r
    }

    private fun syncSwitchesFromPrefs() {
        val enabled = prefs.getEnabledTargets()
        appSwitches.forEach { (pkg, sw) -> sw.isChecked = pkg in enabled }
    }

    // ------------------------------------------------------------------ Ações e Interações

    private fun simulate(cat: String, price: Double, pKm: Double, pMin: Int, tKm: Double, tMin: Int) {
        simMock.visibility = View.VISIBLE
        simCategory.text = cat
        simPrice.text = String.format(ptBr, "R$ %.2f", price)
        simPickup.text = String.format(ptBr, "%d min (%.1f km) de distância", pMin, pKm)
        simTrip.text = String.format(ptBr, "Viagem de %d min (%.1f km) • Aceitar", tMin, tKm)

        val ev = RideOfferParser.parseScreenNodes(
            listOf(simCategory.text.toString(), simPrice.text.toString(), simPickup.text.toString(), simTrip.text.toString()),
            prefs.getThresholds()
        ) ?: return

        showSimResult(ev)
        if (previewOverlay.canDrawOverlays()) {
            previewOverlay.showEvaluation(ev)
        } else {
            Toast.makeText(this, "Ative a Sobreposição de Tela para visualizar o Semáforo Flutuante.", Toast.LENGTH_LONG).show()
        }
    }

    private fun showSimResult(ev: RideEvaluation) {
        simResult.text = String.format(
            ptBr, "%s • R$ %.2f/km • R$ %.0f/h\nLíquido: R$ %.2f (%.1f km / %d min)",
            ev.status.label, ev.earningsPerKm, ev.earningsPerHour, ev.netProfit, ev.totalDistanceKm, ev.totalDurationMin
        )
        simResult.setTextColor(Color.WHITE)
        simResult.background = GradientDrawable().apply {
            cornerRadius = 24f
            setColor(ev.status.hexColor)
        }
    }

    private fun loadThresholds() {
        val t = prefs.getThresholds()
        inGreenKm.setText(String.format(Locale.US, "%.2f", t.greenMinPerKm))
        inYellowKm.setText(String.format(Locale.US, "%.2f", t.yellowMinPerKm))
        inGreenHour.setText(String.format(Locale.US, "%.0f", t.greenMinPerHour))
        inYellowHour.setText(String.format(Locale.US, "%.0f", t.yellowMinPerHour))
        inCostKm.setText(String.format(Locale.US, "%.2f", t.costPerKm))
    }

    private fun saveThresholds() {
        fun v(e: EditText, f: Double) = e.text.toString().replace(",", ".").toDoubleOrNull() ?: f
        val c = prefs.getThresholds()
        prefs.saveThresholds(
            DriverThresholds(
                greenMinPerKm = v(inGreenKm, c.greenMinPerKm), yellowMinPerKm = v(inYellowKm, c.yellowMinPerKm),
                greenMinPerHour = v(inGreenHour, c.greenMinPerHour), yellowMinPerHour = v(inYellowHour, c.yellowMinPerHour),
                costPerKm = v(inCostKm, c.costPerKm)
            )
        )
        Toast.makeText(this, "Metas financeiras salvas com sucesso.", Toast.LENGTH_SHORT).show()
    }

    private fun openOverlaySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } else {
            Toast.makeText(this, "Permissão de sobreposição já concedida.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAccessibility() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        Toast.makeText(this, "Localize e ative 'Lucronline' em Serviços Instalados.", Toast.LENGTH_LONG).show()
    }

    private fun requestBatteryExemption() {
        if (isBatteryUnrestricted()) {
            Toast.makeText(this, "Isenção de otimização de bateria já ativa.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    // ------------------------------------------------------------------ Helpers Visuais

    private fun text(s: String, sp: Float, color: String, bold: Boolean = false) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(Color.parseColor(color))
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun title(s: String) = text(s, 15.5f, "#FFFFFF", bold = true).apply {
        setPadding(0, 0, 0, (8 * resources.displayMetrics.density).toInt())
    }

    private fun card(dp: (Int) -> Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) }
        background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(Color.parseColor("#0F172A"))
            setStroke(dp(1), Color.parseColor("#1E293B"))
        }
    }

    private fun button(label: String, colorHex: String, dp: (Int) -> Int, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)

        val bg = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(Color.parseColor(colorHex))
        }
        val rippleColor = ColorStateList.valueOf(Color.parseColor("#33FFFFFF"))
        background = RippleDrawable(rippleColor, bg, null)

        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
        setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onClick()
        }
    }

    private fun smallBtn(label: String, colorHex: String, dp: (Int) -> Int, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)

        val bg = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(Color.parseColor(colorHex))
        }
        val rippleColor = ColorStateList.valueOf(Color.parseColor("#33FFFFFF"))
        background = RippleDrawable(rippleColor, bg, null)

        setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onClick()
        }
    }

    private fun weight(dp: (Int) -> Int, endMargin: Int) =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(endMargin) }

    private fun input(
        parent: LinearLayout,
        label: String,
        dp: (Int) -> Int,
        decimal: Boolean = true,
        hint: String = ""
    ): EditText {
        parent.addView(text(label, 12.5f, "#CBD5E1").apply { setPadding(0, dp(8), 0, dp(4)) })
        val e = EditText(this).apply {
            inputType = if (decimal) {
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            } else {
                InputType.TYPE_CLASS_NUMBER
            }
            if (hint.isNotEmpty()) {
                this.hint = hint
                setHintTextColor(Color.parseColor("#475569"))
            }
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#060911"))
                setStroke(dp(1), Color.parseColor("#1E293B"))
            }
        }
        parent.addView(e)
        return e
    }

    private fun inputText(
        parent: LinearLayout,
        label: String,
        dp: (Int) -> Int,
        defaultText: String = "",
        hint: String = ""
    ): EditText {
        parent.addView(text(label, 12.5f, "#CBD5E1").apply { setPadding(0, dp(8), 0, dp(4)) })
        val e = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            if (defaultText.isNotEmpty()) setText(defaultText)
            if (hint.isNotEmpty()) {
                this.hint = hint
                setHintTextColor(Color.parseColor("#475569"))
            }
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#060911"))
                setStroke(dp(1), Color.parseColor("#1E293B"))
            }
        }
        parent.addView(e)
        return e
    }
}
