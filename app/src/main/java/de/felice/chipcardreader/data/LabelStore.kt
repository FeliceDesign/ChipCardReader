package de.felice.chipcardreader.data

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf

/**
 * Names YOU give to VDV organisation and product numbers.
 *
 * Why not a built-in list? The official VDV org-ID list isn't public, and a
 * wrong name ("VRR" on a Hamburg card) is worse than an honest number.
 * So the user labels each number once and the phone remembers it.
 *
 * Backed by SharedPreferences (a tiny key-value store on the phone) and
 * mirrored into a Compose state map so the UI updates instantly.
 */
class LabelStore(context: Context) {
    private val prefs = context.getSharedPreferences("labels", Context.MODE_PRIVATE)
    private val cache = mutableStateMapOf<String, String>().apply {
        prefs.all.forEach { (k, v) -> if (v is String) put(k, v) }
    }

    fun org(id: Int): String? = cache[orgKey(id)]
    fun product(org: Int, product: Int): String? = cache[productKey(org, product)]

    fun setOrg(id: Int, name: String) = set(orgKey(id), name)
    fun setProduct(org: Int, product: Int, name: String) = set(productKey(org, product), name)

    fun library(id: String): String? = cache[libraryKey(id)]
    fun setLibrary(id: String, name: String) = set(libraryKey(id), name)

    private fun set(key: String, name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) {
            cache.remove(key); prefs.edit().remove(key).apply()
        } else {
            cache[key] = clean; prefs.edit().putString(key, clean).apply()
        }
    }

    private fun libraryKey(id: String) = "library.$id"
    private fun orgKey(id: Int) = "org.$id"
    private fun productKey(org: Int, product: Int) = "product.$org.$product"
}
