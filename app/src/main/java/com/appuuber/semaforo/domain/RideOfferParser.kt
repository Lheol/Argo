package com.appuuber.semaforo.domain

import kotlin.math.roundToInt

/**
 * Representa a cor do semáforo para a corrida analisada.
 */
enum class SemaphoreStatus(val label: String, val hexColor: Int) {
    GREEN("VALE A PENA", 0xFF10B981.toInt()),     // Verde Esmeralda
    YELLOW("TEM POTENCIAL", 0xFFF59E0B.toInt()),  // Amarelo Âmbar
    RED("PAGANDO MAL", 0xFFEF4444.toInt())        // Vermelho Alerta
}

/**
 * Configurações de metas do motorista (similares ao Rota Pro / GiGu / StopClub).
 */
data class DriverThresholds(
    val greenMinPerKm: Double = 2.00,   // R$ 2,00/km para ficar Verde
    val yellowMinPerKm: Double = 1.50,  // R$ 1,50/km para ficar Amarelo
    val greenMinPerHour: Double = 45.0, // R$ 45,00/h para ficar Verde
    val yellowMinPerHour: Double = 32.0,// R$ 32,00/h para ficar Amarelo
    val costPerKm: Double = 0.75        // Custo de combustível + manutenção por km (GiGu)
) {
    /** Garante metas finitas, não negativas e coerentes (verde >= amarelo). */
    fun sanitized(): DriverThresholds {
        val d = DriverThresholds()
        fun fix(v: Double, fallback: Double) = if (v.isFinite() && v >= 0.0) v else fallback
        val gKm = fix(greenMinPerKm, d.greenMinPerKm)
        val yKm = fix(yellowMinPerKm, d.yellowMinPerKm)
        val gH = fix(greenMinPerHour, d.greenMinPerHour)
        val yH = fix(yellowMinPerHour, d.yellowMinPerHour)
        return DriverThresholds(
            greenMinPerKm = maxOf(gKm, yKm),
            yellowMinPerKm = minOf(gKm, yKm),
            greenMinPerHour = maxOf(gH, yH),
            yellowMinPerHour = minOf(gH, yH),
            costPerKm = fix(costPerKm, d.costPerKm)
        )
    }
}

/**
 * Dados extraídos da chamada e métricas calculadas.
 */
data class RideEvaluation(
    val appSource: String = "Uber",         // Plataforma detectada (Uber, 99, inDrive)
    val category: String = "UberX",         // Modalidade (UberX, Comfort, 99Pop, Radar, etc.)
    val grossPrice: Double,                 // Valor bruto ofertado (R$)
    val pickupDistanceKm: Double = 0.0,     // Distância até o passageiro (km)
    val tripDistanceKm: Double = 0.0,       // Distância da viagem (km)
    val totalDistanceKm: Double,            // Distância total: embarque + viagem (km)
    val totalDurationMin: Int,              // Tempo total: embarque + viagem (min)
    val earningsPerKm: Double,              // Ganho bruto por km (R$/km)
    val earningsPerHour: Double,            // Ganho bruto por hora (R$/h)
    val netProfit: Double,                  // Lucro líquido descontando custo/km (R$)
    val passengerRating: Double? = null,    // Nota do passageiro (ex: 4.92)
    val hasMultipleStops: Boolean = false,  // Indica se tem parada intermediária ("Parada(s)")
    val stopCount: Int = 0,                 // Quantidade de paradas detectadas (0 = sem paradas)
    val isRadarOffer: Boolean = false,      // Indica se é chamada do Radar ("Selecionar"/"Corresponder")
    val status: SemaphoreStatus,            // Cor do semáforo (GREEN, YELLOW, RED)
    val rawSignature: String                // Assinatura única para evitar reprocessar o mesmo card
)

/**
 * Motor de engenharia reversa baseado nas regras reais extraídas do `receita_android.json`
 * e `RotaAccessibilityService` do APK do Rota Pro 1.1.64 (`app.rotapro`).
 */
object RideOfferParser {

    // Limpeza de valores que NÃO são o preço principal da corrida (extraído de `receita_android.json`)
    private val NOISE_PRICE_CLEANERS = listOf(
        Regex("""\+?\s*R\$\s*\d{1,6}(?:[.,]\d{1,2})?\s*inclu[íi]dos?""", RegexOption.IGNORE_CASE),
        Regex("""\+\s*R\$\s*\d{1,6}(?:[.,]\d{1,2})?""", RegexOption.IGNORE_CASE),
        // Rota Pro 1.1.66: tarifa "R$ 1,8/km" sem centavos obrigatórios e com espaços (`uber.priceCleanPorKm`)
        Regex("""R\$\s*\d{1,6}(?:[.,]\d{1,2})?[ \t]*/[ \t]*(?:km|hr|h|hora)\b[^\n]*""", RegexOption.IGNORE_CASE),
        Regex("""Taxa\s+de\s+deslocamento\s+R\$\s*\d+(?:[.,]\d+)?""", RegexOption.IGNORE_CASE),
        Regex("""Coletar\s+R\$\s*\d+(?:[.,]\d+)?""", RegexOption.IGNORE_CASE),
        Regex("""R\$\s*\d{1,6}\s*para\s*\d+\s*viagens""", RegexOption.IGNORE_CASE)
    )

    // Captura de Preço Principal (`a11yuber.priceAnyRegex` / `uber.precoCandidatoRegex`)
    private val PRICE_REGEX = Regex("""(?<!\+)R\$\s*(\d{1,3}(?:\.\d{3})+,\d{2}|\d{1,4}(?:[.,]\d{2}))""")

    // Aparelhos/contas com moeda em dólar exibem "US$22" (inteiro, sem centavos obrigatórios).
    private val USD_PRICE_REGEX = Regex("""(?<!\+)US\$\s*(\d{1,4}(?:[.,]\d{2})?)""")

    // Uber Embarque: "3 min (1,2 km) de distância" ou "4 min (1.5 km) até o passageiro" (`a11yuber.pickupRegex`)
    private val UBER_PICKUP_REGEX = Regex(
        """(\d{1,3})\s*min\w*\s*\((\d{1,4}(?:[.,]\d{1,2})?)\s*(km|m)\)\s*(?:de\s*dist|at[ée])""",
        RegexOption.IGNORE_CASE
    )

    // Uber Viagem com Horas: "Viagem de 1 h 15 min (42,0 km)" (`a11yuber.tripHourRegex`)
    private val UBER_TRIP_HOUR_REGEX = Regex(
        """viagem\s+de\s+(\d{1,2})\s*h\s*e?\s*(\d{1,3})\s*min\w*\s*\(?(\d{1,4}(?:[.,]\d{1,2})?)\s*(km|m)\)?""",
        RegexOption.IGNORE_CASE
    )

    // Uber Viagem em Minutos: "Viagem de 16 min (7,4 km)" (`a11yuber.tripMinRegex`)
    private val UBER_TRIP_MIN_REGEX = Regex(
        """viagem\s+de\s+(\d{1,3})\s*min\w*\s*\(?(\d{1,4}(?:[.,]\d{1,2})?)\s*(km|m)\)?""",
        RegexOption.IGNORE_CASE
    )

    // Uber Pacote / Total: "Total: 22 min (9,5 km)" (`a11yuber.pacoteMinRegex`)
    private val UBER_PACOTE_TOTAL_REGEX = Regex(
        """total:\s*(?:(\d{1,2})\s*h\s*e?\s*)?(\d{1,3})\s*min\w*\s*\((\d{1,4}(?:[.,]\d{1,2})?)\s*(km|m)\)""",
        RegexOption.IGNORE_CASE
    )

    // Padrão Genérico de Pernas (Uber / 99 Normal e Radar): "4 min (1,5 km)", "(15 min 6,2 km)" e,
    // desde o Rota Pro 1.1.66, também "4 min 1.5 km" (separador apenas por espaço / bullet).
    private val GENERIC_LEG_REGEX = Regex(
        """(?:(\d{1,2})\s*h\s*e?\s*)?(\d{1,3})\s*min\w*[\s•·\-–]*(?:\(\s*)?(\d{1,4}(?:[.,]\d{1,2})?)\s*(km|m)\b""",
        RegexOption.IGNORE_CASE
    )

    // Fallback de KM e Minutos separados
    private val KM_FALLBACK_REGEX = Regex("""(\d{1,3}(?:[.,]\d{1,2})?)\s*km\b""", RegexOption.IGNORE_CASE)
    private val MIN_FALLBACK_REGEX = Regex("""\b(\d{1,3})\s*min(?:utos?|s)?\b""", RegexOption.IGNORE_CASE)

    // Nota do Passageiro (`a11yuber.ratingRegex` / `nove.ratingRegex`): "4,92 (140)" ou "★ 4.89"
    private val RATING_REGEX = Regex("""(?:★\s*|(?<![\d$.,]))([345][.,]\d{2})(?:\s*\(\d+\)|\s*[-–—·•.*]?\s*\+?\d+\s*corridas?|\b)""", RegexOption.IGNORE_CASE)

    // Múltiplas Paradas (`uber.paradasRegex` / `nove.paradasRegex`)
    // Exige contagem ("2 paradas", "1 parada extra"), "várias paradas" ou "parada(s)" explícito; evita "ponto de parada".
    private val STOPS_REGEX = Regex(
        """v[áa]rias\s+paradas|\b\d+\s*paradas?\b|\bparadas?\s+extras?\b|\bparada\(s\)""",
        RegexOption.IGNORE_CASE
    )

    // Modalidade (`a11yuber.typeRegex` / `a11ynove.typeRegex`)
    private val CATEGORY_REGEX = Regex(
        """(UberX\s*Poupa|UberX|Comfort|Black|Flash\s*Moto|Flash|Prioridade|Priority|Envios|Pacote|99Pop|Pop\s*Expresso|99Moto|99Negocia|Negocia|inDrive)""",
        RegexOption.IGNORE_CASE
    )

    // Filtro Anti-Falso-Positivo (`gate.naoEhOferta` do Rota Pro)
    private val NON_OFFER_BLACKLIST = listOf(
        "iniciar navegação", "iniciar navegacao",
        "última viagem", "ultima viagem",
        "ver histórico", "ver historico",
        "progresso na missão", "progresso na missao",
        "solicitação de reserva", "como foi a viagem",
        "avaliar usuário", "configurações da conta", "saldo disponível"
    )

    // Palavras-chave de validação de oferta (`gate.palavrasOferta` + `a11yuber.canParse`)
    private val OFFER_KEYWORDS = listOf(
        "aceitar", "selecionar", "corresponder", "escolher", "viagem de",
        "de dist", "total:", "distância total", "uberx", "comfort", "black",
        "flash", "exclusivo", "prioridade", "99pop", "99negocia", "poupa",
        // Card real da 99 ("Corrida longa" / Dinheiro / Perfil Premium) não tem botão "Aceitar" visível
        "corrida longa", "perfil premium", "taxa de deslocamento", "parada(s)"
    )

    // Marcadores textuais que só aparecem em cards da 99 (nunca o número "99" solto, que pode ser centavos).
    private val NOVE_NOVE_MARKERS = listOf(
        "99pop", "99 pop", "99negocia", "99 negocia", "99moto", "99 moto", "corrida longa",
        "perfil premium", "taxa de deslocamento", "dinheiro"
    )

    /**
     * Analisa os nós da tela (`AccessibilityNodeInfo` + View IDs) seguindo o pipeline do Rota Pro.
     * Telas do "Radar de Viagens" listam vários cards: nesse caso só o PRIMEIRO card completo é avaliado,
     * para nunca misturar o preço de um card com as distâncias de outro.
     */
    fun parseScreenNodes(
        nodeTexts: List<String>,
        thresholds: DriverThresholds = DriverThresholds(),
        viewIds: List<String> = emptyList()
    ): RideEvaluation? {
        val cards = splitRadarCards(nodeTexts)
        if (cards.size >= 2) {
            return cards.firstNotNullOfOrNull { parseSingleOffer(it, thresholds, viewIds) }
        }
        return parseSingleOffer(nodeTexts, thresholds, viewIds)
    }

    /** Uma avaliação por card COMPLETO da lista do Radar (cards cortados na tela são ignorados). */
    fun parseRadarCards(
        nodeTexts: List<String>,
        thresholds: DriverThresholds = DriverThresholds(),
        viewIds: List<String> = emptyList()
    ): List<RideEvaluation> {
        val cards = splitRadarCards(nodeTexts).ifEmpty { listOf(nodeTexts) }
        return cards.mapNotNull { parseSingleOffer(it, thresholds, viewIds) }
    }

    /**
     * Divide a tela do Radar em cards, usando o selo de categoria (UberX, Comfort...) como início de card.
     * Só divide quando a tela é do Radar e tem 2+ cards; caso contrário devolve lista vazia.
     */
    private fun splitRadarCards(nodeTexts: List<String>): List<List<String>> {
        if (nodeTexts.none { it.contains("radar", ignoreCase = true) }) return emptyList()
        val starts = nodeTexts.indices.filter { i ->
            val m = CATEGORY_REGEX.matchEntire(nodeTexts[i].trim())
            m != null
        }
        if (starts.size < 2) return emptyList()
        return starts.mapIndexed { n, s ->
            nodeTexts.subList(s, if (n + 1 < starts.size) starts[n + 1] else nodeTexts.size)
        }
    }

    private fun parseSingleOffer(
        nodeTexts: List<String>,
        thresholds: DriverThresholds,
        viewIds: List<String>
    ): RideEvaluation? {
        if (nodeTexts.isEmpty()) return null

        // Normaliza espaços especiais (NBSP / narrow NBSP / thin) que o `\s` do regex não cobre.
        val fullText = nodeTexts.joinToString(separator = " | ")
            .replace('\u00A0', ' ').replace('\u202F', ' ').replace('\u2009', ' ')
        val lowerText = fullText.lowercase()

        // 1. Verifica Blacklist de telas que têm R$ mas NÃO são ofertas (`gate.naoEhOferta`)
        if (NON_OFFER_BLACKLIST.any { lowerText.contains(it) }) {
            return null
        }

        // 2. Verifica presença de View IDs nativos da Uber/99 ou palavras-chave de chamada
        val hasKnownOfferViewId = viewIds.any { id ->
            id.contains("primary_touch_area") ||
                id.contains("driver_offers_job_board") ||
                id.contains("broad_order_container") ||
                id.contains("flutter_deal_gesture_container") ||
                id.contains("order_button_accept_price")
        }
        val hasOfferKeyword = OFFER_KEYWORDS.any { lowerText.contains(it) }
        if (!hasKnownOfferViewId && !hasOfferKeyword) return null

        // 3. Limpa preços de bônus/dinâmico ("+R$ 3,50 incluído") e tarifas ("R$ 1,80/km") para isolar o valor real
        var cleanedForPrice = fullText
        NOISE_PRICE_CLEANERS.forEach { regex ->
            cleanedForPrice = regex.replace(cleanedForPrice, " ")
        }

        val prices = (PRICE_REGEX.findAll(cleanedForPrice) + USD_PRICE_REGEX.findAll(cleanedForPrice))
            .mapNotNull { match ->
                parseMoney(match.groupValues[1])
            }
            .filter { it in 4.00..1500.0 }
            .toList()

        val grossPrice = prices.maxOrNull() ?: return null

        // 4. Extração de Embarque (Pickup) e Viagem (Trip)
        var pickupKm = 0.0
        var pickupMin = 0
        var tripKm = 0.0
        var tripMin = 0

        val pacoteMatch = UBER_PACOTE_TOTAL_REGEX.find(fullText)
        if (pacoteMatch != null) {
            val h = pacoteMatch.groupValues[1].toIntOrNull() ?: 0
            val m = pacoteMatch.groupValues[2].toIntOrNull() ?: 0
            val dist = parseDistanceKm(pacoteMatch.groupValues[3], pacoteMatch.groupValues[4])
            tripMin = h * 60 + m
            tripKm = dist
        } else {
            val pickupMatch = UBER_PICKUP_REGEX.find(fullText)
            if (pickupMatch != null) {
                pickupMin = pickupMatch.groupValues[1].toIntOrNull() ?: 0
                pickupKm = parseDistanceKm(pickupMatch.groupValues[2], pickupMatch.groupValues[3])
            }

            val tripHourMatch = UBER_TRIP_HOUR_REGEX.find(fullText)
            val tripMinMatch = UBER_TRIP_MIN_REGEX.find(fullText)
            if (tripHourMatch != null) {
                val h = tripHourMatch.groupValues[1].toIntOrNull() ?: 0
                val m = tripHourMatch.groupValues[2].toIntOrNull() ?: 0
                tripMin = h * 60 + m
                tripKm = parseDistanceKm(tripHourMatch.groupValues[3], tripHourMatch.groupValues[4])
            } else if (tripMinMatch != null) {
                tripMin = tripMinMatch.groupValues[1].toIntOrNull() ?: 0
                tripKm = parseDistanceKm(tripMinMatch.groupValues[2], tripMinMatch.groupValues[3])
            }
        }

        // Embarque sem o sufixo "de distância" (variações de layout): pega a primeira perna genérica
        // que NÃO esteja dentro do trecho "Viagem de ...", evitando contar a viagem duas vezes.
        if (pickupKm <= 0.0 && tripKm > 0.0) {
            val tripRange = (UBER_TRIP_HOUR_REGEX.find(fullText) ?: UBER_TRIP_MIN_REGEX.find(fullText))?.range
            val leg = GENERIC_LEG_REGEX.findAll(fullText).firstOrNull { m ->
                tripRange == null || m.range.last < tripRange.first || m.range.first > tripRange.last
            }
            if (leg != null) {
                pickupMin = (leg.groupValues[1].toIntOrNull() ?: 0) * 60 + (leg.groupValues[2].toIntOrNull() ?: 0)
                pickupKm = parseDistanceKm(leg.groupValues[3], leg.groupValues[4])
            }
        }

        // Viagem sem o prefixo "Viagem de" (ex: 99 ou cards curtos da Uber como "14 min (5,1 km)"):
        // pega a perna genérica que não sobreponha a perna de embarque já identificada.
        if (pickupKm > 0.0 && tripKm <= 0.0) {
            val pickupRange = UBER_PICKUP_REGEX.find(fullText)?.range
            val leg = GENERIC_LEG_REGEX.findAll(fullText).firstOrNull { m ->
                pickupRange == null || m.range.last < pickupRange.first || m.range.first > pickupRange.last
            }
            if (leg != null) {
                tripMin = (leg.groupValues[1].toIntOrNull() ?: 0) * 60 + (leg.groupValues[2].toIntOrNull() ?: 0)
                tripKm = parseDistanceKm(leg.groupValues[3], leg.groupValues[4])
            }
        }

        // Se o formato for 99 Pop ou card simplificado ("3 min (1,1 km) ... 16 min (7,4 km)"), usa GENERIC_LEG_REGEX
        if (pickupKm + tripKm <= 0.0) {
            val genericLegs = GENERIC_LEG_REGEX.findAll(fullText).toList()
            if (genericLegs.isNotEmpty()) {
                val first = genericLegs[0]
                pickupMin = (first.groupValues[1].toIntOrNull() ?: 0) * 60 + (first.groupValues[2].toIntOrNull() ?: 0)
                pickupKm = parseDistanceKm(first.groupValues[3], first.groupValues[4])

                if (genericLegs.size >= 2) {
                    val second = genericLegs[1]
                    tripMin = (second.groupValues[1].toIntOrNull() ?: 0) * 60 + (second.groupValues[2].toIntOrNull() ?: 0)
                    tripKm = parseDistanceKm(second.groupValues[3], second.groupValues[4])
                }
            }
        }

        // Fallback caso km e min estejam em nós separados
        var totalDistanceKm = pickupKm + tripKm
        var totalDurationMin = pickupMin + tripMin

        if (totalDistanceKm <= 0.0) {
            val kms = KM_FALLBACK_REGEX.findAll(fullText)
                .mapNotNull { it.groupValues[1].replace(",", ".").toDoubleOrNull() }
                .filter { it in 0.1..500.0 }
                .toList()
            if (kms.isEmpty()) return null
            pickupKm = if (kms.size >= 2) kms[0] else 0.0
            tripKm = if (kms.size >= 2) kms[1] else kms[0]
            totalDistanceKm = kms.take(2).sum()
        }

        if (totalDurationMin <= 0) {
            val mins = MIN_FALLBACK_REGEX.findAll(fullText)
                .mapNotNull { it.groupValues[1].toIntOrNull() }
                .filter { it in 1..300 }
                .toList()
            totalDurationMin = mins.take(2).sum()
            if (totalDurationMin <= 0) {
                totalDurationMin = (totalDistanceKm * 2.4).roundToInt().coerceAtLeast(3)
            }
        }

        // 5. Guarda de Plausibilidade Anti-Corrupção (veto de leitura de saldo espúrio).
        // Teto recalibrado para R$ 45,00/km e R$ 800,00/h para acomodar picos reais de tarifa dinâmica (surge 3.5x+)
        // e categorias Black/Flash de alta remuneração sem descartar ofertas lucrativas legítimas.
        val rawPerKm = grossPrice / totalDistanceKm.coerceAtLeast(0.1)
        val rawPerHour = (grossPrice / totalDurationMin.coerceAtLeast(1).toDouble()) * 60.0
        if (rawPerKm > 45.0 || rawPerHour > 800.0) {
            // Provável leitura de saldo da carteira ou corrupção de nó
            return null
        }

        // 6. Metadados extras da corrida (Nota, Paradas, Radar, Categoria)
        val rating = RATING_REGEX.find(fullText)?.groupValues?.get(1)?.replace(",", ".")?.toDoubleOrNull()
        val stopCount = countStops(fullText)
        val hasStops = stopCount > 0
        val isRadar = lowerText.contains("selecionar") || lowerText.contains("corresponder")
        val category = CATEGORY_REGEX.find(fullText)?.value ?: "UberX"
        val appSource = when {
            viewIds.any { it.contains("app99") || it.contains("taxis99") || it.contains("didiglobal") } ||
                NOVE_NOVE_MARKERS.any { lowerText.contains(it) } -> "99"
            lowerText.contains("indrive") -> "inDrive"
            else -> "Uber"
        }

        return evaluateOffer(
            grossPrice = grossPrice,
            totalDistanceKm = totalDistanceKm,
            totalDurationMin = totalDurationMin,
            thresholds = thresholds,
            appSource = appSource,
            category = category,
            pickupDistanceKm = pickupKm,
            tripDistanceKm = tripKm,
            passengerRating = rating,
            hasMultipleStops = hasStops,
            stopCount = stopCount,
            isRadarOffer = isRadar
        )
    }

    /**
     * Quantidade de paradas: "2 paradas" -> 2; "várias paradas" -> 2; "parada extra"/"parada(s)" -> 1;
     * sem menção -> 0. Limitado a 9 para evitar leitura espúria.
     */
    internal fun countStops(text: String): Int {
        val match = STOPS_REGEX.find(text) ?: return 0
        val m = match.value.lowercase()
        val digits = Regex("""\d+""").find(m)?.value?.toIntOrNull()
        return when {
            digits != null -> digits.coerceIn(1, 9)
            m.startsWith("v") -> 2
            else -> 1
        }
    }

    /** "1.250,00" -> 1250.0 | "12,50" -> 12.5 | "12.50" -> 12.5 */
    internal fun parseMoney(raw: String): Double? {
        val normalized = if (raw.contains(',') && raw.contains('.')) {
            raw.replace(".", "").replace(",", ".")
        } else {
            raw.replace(",", ".")
        }
        return normalized.toDoubleOrNull()
    }

    private fun parseDistanceKm(rawNumber: String, unit: String): Double {
        val value = rawNumber.replace(",", ".").toDoubleOrNull() ?: return 0.0
        return if (unit.equals("m", ignoreCase = true)) {
            (value / 1000.0).coerceAtLeast(0.1)
        } else {
            value
        }
    }

    /**
     * Calcula R$/km, R$/hora, lucro líquido e define a cor do semáforo (Verde, Amarelo ou Vermelho).
     */
    fun evaluateOffer(
        grossPrice: Double,
        totalDistanceKm: Double,
        totalDurationMin: Int,
        thresholds: DriverThresholds = DriverThresholds(),
        appSource: String = "Uber",
        category: String = "UberX",
        pickupDistanceKm: Double = 0.0,
        tripDistanceKm: Double = totalDistanceKm,
        passengerRating: Double? = null,
        hasMultipleStops: Boolean = false,
        isRadarOffer: Boolean = false,
        stopCount: Int = 0
    ): RideEvaluation {
        val validGross = if (grossPrice.isFinite() && grossPrice > 0.0) grossPrice else 0.0
        val safeKm = if (totalDistanceKm.isFinite() && totalDistanceKm >= 0.1) totalDistanceKm else 0.1
        val safeMin = if (totalDurationMin in 1..100_000) totalDurationMin else 1

        val rawPerKm = validGross / safeKm
        val rawPerHour = (validGross / safeMin.toDouble()) * 60.0
        val operationalCost = safeKm * (thresholds.costPerKm.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0)
        val rawNet = validGross - operationalCost

        val earningsPerKm = if (rawPerKm.isFinite()) rawPerKm else 0.0
        val earningsPerHour = if (rawPerHour.isFinite()) rawPerHour else 0.0
        val netProfit = if (rawNet.isFinite()) rawNet else 0.0

        // Lógica de Decisão do Semáforo:
        // - VERDE: Quando bate a meta boa de R$/km E R$/h (ou supera em 25% o R$/km mesmo com trânsito)
        // - AMARELO: Quando atinge pelo menos a faixa intermediária (tem potencial dependendo da região/retorno)
        // - VERMELHO: Pagando mal (abaixo do mínimo tanto em km quanto em hora, ou lucro líquido quase nulo)
        val status = when {
            (earningsPerKm >= thresholds.greenMinPerKm && earningsPerHour >= thresholds.greenMinPerHour) ||
                (earningsPerKm >= thresholds.greenMinPerKm * 1.25 && earningsPerHour >= thresholds.yellowMinPerHour) -> {
                SemaphoreStatus.GREEN
            }
            earningsPerKm >= thresholds.yellowMinPerKm && earningsPerHour >= thresholds.yellowMinPerHour * 0.85 -> {
                SemaphoreStatus.YELLOW
            }
            earningsPerHour >= thresholds.greenMinPerHour && earningsPerKm >= thresholds.yellowMinPerKm * 0.90 -> {
                SemaphoreStatus.YELLOW
            }
            else -> {
                SemaphoreStatus.RED
            }
        }

        val safePickup = if (pickupDistanceKm.isFinite() && pickupDistanceKm >= 0.0) pickupDistanceKm else 0.0
        val safeTrip = if (tripDistanceKm.isFinite() && tripDistanceKm >= 0.0) tripDistanceKm else safeKm
        val safeRating = passengerRating?.takeIf { it.isFinite() && it in 1.0..5.0 }

        val safeStops = if (stopCount > 0) stopCount.coerceAtMost(9) else if (hasMultipleStops) 1 else 0
        val signature = String.format(java.util.Locale.US, "%.2f_%.1f_%d", validGross, safeKm, safeMin)

        return RideEvaluation(
            appSource = appSource,
            category = category,
            grossPrice = validGross,
            pickupDistanceKm = safePickup,
            tripDistanceKm = safeTrip,
            totalDistanceKm = safeKm,
            totalDurationMin = safeMin,
            earningsPerKm = earningsPerKm,
            earningsPerHour = earningsPerHour,
            netProfit = netProfit,
            passengerRating = safeRating,
            hasMultipleStops = hasStopsFlag(hasMultipleStops) || safeStops > 0,
            stopCount = safeStops,
            isRadarOffer = isRadarOffer,
            status = status,
            rawSignature = signature
        )
    }

    private fun hasStopsFlag(flag: Boolean): Boolean = flag
}
