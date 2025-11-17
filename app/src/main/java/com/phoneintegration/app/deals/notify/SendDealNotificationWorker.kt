package com.phoneintegration.app.deals.notify

import android.content.Context
import androidx.work.*
import com.phoneintegration.app.deals.DealsRepository
import com.phoneintegration.app.deals.notify.DealNotificationManager
import com.phoneintegration.app.deals.notify.PriceDropEngine

class SendDealNotificationWorker(
    ctx: Context,
    params: WorkerParameters
) : CoroutineWorker(ctx, params) {

    private val repo = DealsRepository(ctx)
    private val dropEngine = PriceDropEngine(ctx)

    override suspend fun doWork(): Result {
        val deals = repo.getDeals()

        if (deals.isEmpty()) return Result.success()

        // price drop check
        val drop = dropEngine.checkForPriceDrop(deals)
        if (drop != null) {
            DealNotificationManager.showPriceDropNotification(
                context = applicationContext,
                deal = drop
            )
            return Result.success()
        }

        // pick a random top deal for regular notification
        val pick = deals.random()

        DealNotificationManager.showDealNotification(
            context = applicationContext,
            deal = pick
        )

        return Result.success()
    }
}
