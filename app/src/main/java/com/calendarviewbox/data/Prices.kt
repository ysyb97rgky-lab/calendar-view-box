package com.calendarviewbox.data

import org.json.JSONObject
import java.util.Locale

/** A product found at a store, or a price someone typed in (store "Other"). */
data class StoreProduct(
    val store: String,
    val productId: String?,
    val name: String,
    val size: String?,
    val price: Double,
    val wasPrice: Double? = null,
    val unitPrice: String? = null,
    val special: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val shortStore: String
        get() = when (store) {
            STORE_WOOLWORTHS -> "Woolies"
            else -> store
        }
}

const val STORE_WOOLWORTHS = "Woolworths"
const val STORE_COLES = "Coles"
const val STORE_ALDI = "Aldi"
const val STORE_OTHER = "Other"

/** "2x Milk" or "Milk x2" means two of them. */
data class Quantity(val count: Int, val name: String)

object Prices {

    private val leading = Regex("""^\s*(\d{1,2})\s*[x×]\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val trailing = Regex("""^(.+?)\s+[x×]\s*(\d{1,2})\s*$""", RegexOption.IGNORE_CASE)

    fun quantity(text: String): Quantity {
        leading.matchEntire(text)?.let { return Quantity(it.groupValues[1].toInt().coerceAtLeast(1), it.groupValues[2].trim()) }
        trailing.matchEntire(text)?.let { return Quantity(it.groupValues[2].toInt().coerceAtLeast(1), it.groupValues[1].trim()) }
        return Quantity(1, text.trim())
    }

    /** Key for remembering a price by name: lower case, single spaces, no quantity. */
    fun key(text: String): String =
        quantity(text).name.lowercase(Locale.ROOT).replace(Regex("""\s+"""), " ").trim()

    fun money(amount: Double): String = String.format(Locale.US, "$%.2f", amount)

    /** Reads "4.95" or "$4.95". */
    fun parseMoney(text: String): Double? =
        text.trim().removePrefix("$").replace(",", "").toDoubleOrNull()?.takeIf { it >= 0 && it < 10_000 }

    fun toJson(p: StoreProduct): JSONObject = JSONObject()
        .put("store", p.store)
        .put("id", p.productId ?: JSONObject.NULL)
        .put("name", p.name)
        .put("size", p.size ?: JSONObject.NULL)
        .put("price", p.price)
        .put("was", p.wasPrice ?: JSONObject.NULL)
        .put("unit", p.unitPrice ?: JSONObject.NULL)
        .put("special", p.special)
        .put("at", p.updatedAt)

    fun fromJson(o: JSONObject): StoreProduct? = runCatching {
        StoreProduct(
            store = o.getString("store"),
            productId = if (o.isNull("id")) null else o.getString("id"),
            name = o.getString("name"),
            size = if (!o.has("size") || o.isNull("size")) null else o.getString("size"),
            price = o.getDouble("price"),
            wasPrice = if (!o.has("was") || o.isNull("was")) null else o.getDouble("was"),
            unitPrice = if (!o.has("unit") || o.isNull("unit")) null else o.getString("unit"),
            special = o.optBoolean("special"),
            updatedAt = o.optLong("at"),
        )
    }.getOrNull()

    fun mapToJson(map: Map<String, StoreProduct>): String =
        JSONObject().apply { map.forEach { (k, v) -> put(k, toJson(v)) } }.toString()

    fun mapFromJson(s: String?): Map<String, StoreProduct> = runCatching {
        val o = JSONObject(s ?: return emptyMap())
        o.keys().asSequence().mapNotNull { k -> o.optJSONObject(k)?.let { fromJson(it) }?.let { k to it } }.toMap()
    }.getOrDefault(emptyMap())
}
