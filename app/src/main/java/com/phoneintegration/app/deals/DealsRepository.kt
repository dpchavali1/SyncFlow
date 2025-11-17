package com.phoneintegration.app.deals

import android.content.Context
import com.phoneintegration.app.deals.cloud.CloudDealsService
import com.phoneintegration.app.deals.model.Deal
import com.phoneintegration.app.deals.local.LocalDealsLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.phoneintegration.app.deals.storage.DealCache
class DealsRepository(private val context: Context) {

    private val cloud = CloudDealsService()
    private val local = LocalDealsLoader(context)
    private val cache = DealCache(context)

    private val githubUrl =
        "https://raw.githubusercontent.com/dpchavali1/syncflow-deals/main/deals.json"

    suspend fun getDeals(): List<Deal> = withContext(Dispatchers.IO) {

        // 1) Try cloud with 3-second timeout
        val cloudDeals = cloud.loadFromUrl(githubUrl)
        if (cloudDeals.isNotEmpty()) {
            cache.saveDeals(cloudDeals)
            return@withContext cloudDeals
        }

        // 2) Load cached deals (fast!)
        val cached = cache.loadDeals()
        if (cached.isNotEmpty()) {
            return@withContext cached
        }

        // 3) Use local assets (always works)
        return@withContext local.loadFromAssets()
    }
}

