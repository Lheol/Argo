package com.appuuber.semaforo.data

/** App de corrida suportado pelo leitor (único tipo que pode ser marcado pelo usuário). */
data class SupportedApp(val packageName: String, val displayName: String)

/**
 * Catálogo fechado de alvos. O usuário só pode MARCAR/DESMARCAR itens deste catálogo:
 * não existe opção de adicionar pacotes arbitrários, pois o parser só conhece estes layouts.
 */
object TargetApps {
    const val UBER = "com.ubercab.driver"
    const val NOVE_NOVE = "com.app99.driver"
    const val INDRIVE = "sinet.startup.inDriver"

    val SUPPORTED: List<SupportedApp> = listOf(
        SupportedApp(UBER, "Uber Driver"),
        SupportedApp(NOVE_NOVE, "99 Motorista"),
        SupportedApp(INDRIVE, "inDrive")
    )

    val SUPPORTED_PACKAGES: Set<String> = SUPPORTED.map { it.packageName }.toSet()

    /** Um pacote só é selecionável se estiver no catálogo E não for sensível/financeiro. */
    fun isSelectable(pkg: String): Boolean =
        pkg in SUPPORTED_PACKAGES && !SensitiveAppPolicy.isSensitive(pkg)
}

/**
 * Política fixa de proteção: apps bancários/financeiros/carteiras NUNCA são lidos,
 * mesmo que por erro de configuração entrem na lista de marcados.
 */
object SensitiveAppPolicy {

    private val SENSITIVE_PREFIXES = listOf(
        "br.com.brb", "br.com.bb.", "com.itau", "br.com.itau", "com.bradesco", "br.com.bradesco",
        "br.com.caixa", "com.caixa", "com.santander", "br.com.santander", "br.com.intermedium",
        "com.c6bank", "com.nu.production", "com.picpay", "com.mercadopago", "br.com.sicredi",
        "br.com.sicoob", "br.com.xp", "com.btgpactual", "br.com.original", "com.neon", "com.pagseguro",
        "com.google.android.apps.walletnfcrel", "com.paypal"
    )

    private val SENSITIVE_KEYWORDS = listOf(
        "bank", "banco", "banking", "wallet", "fintech", "pix", "creditcard", "pagamento", "pay."
    )

    fun isSensitive(pkg: String): Boolean {
        val p = pkg.lowercase()
        return SENSITIVE_PREFIXES.any { p.startsWith(it) } || SENSITIVE_KEYWORDS.any { p.contains(it) }
    }
}
