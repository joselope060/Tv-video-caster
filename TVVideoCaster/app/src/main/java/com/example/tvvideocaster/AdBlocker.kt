package com.example.tvvideocaster

import android.content.Context
import android.net.Uri
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bloqueador de publicidad por dominio, estilo AdGuard DNS.
 *
 * - Lee reglas con sintaxis de lista de AdGuard: `||dominio.com^` (bloquear) y `@@||dominio.com^` (excepción).
 * - Una regla de dominio cubre también todos sus subdominios.
 * - Carga una lista básica incluida en la app (assets/adblock_seed.txt) y, si existe, el filtro
 *   oficial de AdGuard DNS descargado en la caché.
 */
class AdBlocker(private val context: Context) {

    @Volatile private var blocked: Set<String> = emptySet()
    @Volatile private var allowed: Set<String> = emptySet()
    @Volatile var enabled: Boolean = true
    val blockedCount = AtomicInteger(0)

    private val prefs = context.getSharedPreferences("adblock", Context.MODE_PRIVATE)
    private val cacheFile get() = File(context.filesDir, "adguard_filter.txt")

    val ruleCount: Int get() = blocked.size

    /** Carga las listas en un hilo aparte y actualiza el filtro si tiene más de 7 días. */
    fun init(onLoaded: (Int) -> Unit) {
        Thread {
            reload()
            post { onLoaded(ruleCount) }
            val age = System.currentTimeMillis() - prefs.getLong(KEY_LAST_UPDATE, 0L)
            if (age > UPDATE_INTERVAL_MS) {
                val ok = download()
                if (ok) reload()
                post { onLoaded(ruleCount) }
            }
        }.start()
    }

    /** Descarga manual del filtro oficial de AdGuard DNS. */
    fun updateNow(onDone: (Boolean, Int) -> Unit) {
        Thread {
            val ok = download()
            if (ok) reload()
            post { onDone(ok, ruleCount) }
        }.start()
    }

    fun isBlocked(url: String): Boolean {
        if (!enabled) return false
        val host = Uri.parse(url).host?.lowercase() ?: return false
        val b = blocked
        val a = allowed
        // Las excepciones tienen prioridad sobre los bloqueos
        var h = host
        var hit = false
        while (true) {
            if (h in a) return false
            if (h in b) hit = true
            val i = h.indexOf('.')
            if (i < 0) break
            h = h.substring(i + 1)
        }
        if (hit) blockedCount.incrementAndGet()
        return hit
    }

    private fun reload() {
        val block = HashSet<String>(60_000)
        val allow = HashSet<String>(2_000)
        try {
            context.assets.open("adblock_seed.txt").bufferedReader().useLines { parse(it, block, allow) }
        } catch (e: Exception) { }
        val f = cacheFile
        if (f.exists()) {
            try {
                f.bufferedReader().useLines { parse(it, block, allow) }
            } catch (e: Exception) { }
        }
        blocked = block
        allowed = allow
    }

    private fun parse(lines: Sequence<String>, block: MutableSet<String>, allow: MutableSet<String>) {
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("!") || line.startsWith("#")) continue
            val isAllow = line.startsWith("@@||")
            if (!isAllow && !line.startsWith("||")) continue
            val body = line.removePrefix("@@").removePrefix("||")
            val caret = body.indexOf('^')
            if (caret <= 0) continue
            val tail = body.substring(caret + 1)
            // Solo reglas de dominio puras (se admite el modificador $important)
            if (tail.isNotEmpty() && tail != "\$important") continue
            val domain = body.substring(0, caret).lowercase()
            if (!DOMAIN_REGEX.matches(domain)) continue
            if (isAllow) allow.add(domain) else block.add(domain)
        }
    }

    private fun download(): Boolean {
        return try {
            val conn = (URL(FILTER_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
            }
            if (conn.responseCode != 200) return false
            val tmp = File(context.filesDir, "adguard_filter.tmp")
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (tmp.length() < 10_000) { tmp.delete(); return false } // descarga sospechosamente pequeña
            if (!tmp.renameTo(cacheFile)) {
                tmp.copyTo(cacheFile, overwrite = true)
                tmp.delete()
            }
            prefs.edit().putLong(KEY_LAST_UPDATE, System.currentTimeMillis()).apply()
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun post(block: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(block)
    }

    companion object {
        const val FILTER_URL = "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt"
        private const val KEY_LAST_UPDATE = "last_update"
        private const val UPDATE_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000
        private val DOMAIN_REGEX = Regex("^[a-z0-9]([a-z0-9.-]*[a-z0-9])?\\.[a-z0-9-]{2,}$")
    }
}
