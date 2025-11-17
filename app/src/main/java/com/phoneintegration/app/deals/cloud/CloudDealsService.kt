package com.phoneintegration.app.deals.cloud

import com.phoneintegration.app.deals.model.Deal
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Serializable
data class CloudDealEntity(
    val title: String,
    val image: String,
    val price: String,
    val url: String,
    val category: String = "Tech"
)

@Serializable
data class CloudDealsWrapper(
    val deals: List<CloudDealEntity>
)

class CloudDealsService {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun loadFromUrl(url: String): List<Deal> = withContext(Dispatchers.IO) {
        return@withContext try {

            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000

            if (conn.responseCode != 200) return@withContext emptyList()

            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val wrapper = json.decodeFromString<CloudDealsWrapper>(text)

            wrapper.deals.map {
                Deal(
                    id = it.url.hashCode().toString(),
                    title = it.title,
                    price = it.price,
                    image = it.image,
                    url = it.url,
                    category = it.category
                )
            }

        } catch (e: Exception) {
            println("DEBUG: Cloud load failed: $e")
            emptyList()
        }
    }
}
