package com.phoneintegration.app.deals

import android.content.Context
import com.phoneintegration.app.deals.cloud.CloudDealsService
import com.phoneintegration.app.deals.model.Deal
import com.phoneintegration.app.deals.local.LocalDealsLoader
import com.phoneintegration.app.deals.storage.DealCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DealsRepository(private val context: Context) {

    private val cloud = CloudDealsService()
    private val local = LocalDealsLoader(context)
    private val cache = DealCache(context)

    private val githubUrl =
        "https://raw.githubusercontent.com/dpchavali1/syncflow-deals/main/deals.json"

    // ---------------------------------------------------------
    // LOAD DEALS (Cloud ➜ Cache ➜ Local fallback)
    // ---------------------------------------------------------
    suspend fun getDeals(forceRefresh: Boolean = false): List<Deal> = withContext(Dispatchers.IO) {

        if (!forceRefresh) {
            // 1) Use cache first
            val cached = cache.loadDeals()
            if (cached.isNotEmpty()) return@withContext cached
        }

        // 2) Try cloud fetch
        val cloudDeals = cloud.loadFromUrl(githubUrl)
        if (cloudDeals.isNotEmpty()) {
            cache.saveDeals(cloudDeals)
            return@withContext cloudDeals
        }

        // 3) Local fallback
        return@withContext local.loadFromAssets()
    }

    // ---------------------------------------------------------
    // REFRESH FROM CLOUD
    // ---------------------------------------------------------
    suspend fun refreshFromCloud(): Boolean = withContext(Dispatchers.IO) {
        val fresh = cloud.loadFromUrl(githubUrl)
        return@withContext if (fresh.isNotEmpty()) {
            cache.saveDeals(fresh)
            true
        } else {
            false
        }
    }

    // ---------------------------------------------------------
    // Wrapper used by SmsViewModel.refreshDeals()
    // ---------------------------------------------------------
    suspend fun refreshDeals(): Boolean = withContext(Dispatchers.IO) {
        try {
            val newDeals = getDeals(forceRefresh = true)
            newDeals.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }
}
