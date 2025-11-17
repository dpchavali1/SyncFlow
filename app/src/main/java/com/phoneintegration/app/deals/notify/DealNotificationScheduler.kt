package com.phoneintegration.app.deals.notify

import android.content.Context
import androidx.work.*
import java.time.*
import java.util.concurrent.TimeUnit
import com.phoneintegration.app.deals.notify.HolidayCalendar

object DealNotificationScheduler {

    private val zoneId = ZoneId.of("America/Detroit")

    fun scheduleDailyWork(context: Context) {
        scheduleMidnightRescheduler(context)
        scheduleTodayWindows(context)
    }

    private fun scheduleMidnightRescheduler(context: Context) {
        val now = ZonedDateTime.now(zoneId)
        val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(zoneId)

        val delay = Duration.between(now, nextMidnight).toMillis()

        val work = OneTimeWorkRequestBuilder<RescheduleWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "midnight_rescheduler",
            ExistingWorkPolicy.REPLACE,
            work
        )
    }

    private fun scheduleTodayWindows(context: Context) {
        val now = ZonedDateTime.now(zoneId)
        val date = now.toLocalDate()

        val isWeekend = now.dayOfWeek == DayOfWeek.SATURDAY || now.dayOfWeek == DayOfWeek.SUNDAY
        val isHoliday = HolidayCalendar.isHoliday(date)

        if (isWeekend) {
            scheduleOneFixed(context, date.atTime(11, 0))
            scheduleOneFixed(context, date.atTime(17, 0))
            return
        }

        if (isHoliday) {
            scheduleOneFixed(context, date.atTime(10, 30))
            scheduleOneFixed(context, date.atTime(18, 0))
            return
        }

        // Weekday → two windows
        scheduleRandomInsideWindow(
            context,
            start = date.atTime(12, 0),
            end = date.atTime(14, 0)
        )

        scheduleRandomInsideWindow(
            context,
            start = date.atTime(16, 0),
            end = date.atTime(19, 0)
        )
    }

    private fun scheduleOneFixed(context: Context, time: LocalDateTime) {
        val now = ZonedDateTime.now(zoneId)
        val triggerTime = time.atZone(zoneId)

        if (triggerTime.isBefore(now)) return

        val delay = Duration.between(now, triggerTime).toMillis()

        val work = OneTimeWorkRequestBuilder<SendDealNotificationWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(context).enqueue(work)
    }

    private fun scheduleRandomInsideWindow(
        context: Context,
        start: LocalDateTime,
        end: LocalDateTime
    ) {
        val now = ZonedDateTime.now(zoneId)
        val zoneStart = start.atZone(zoneId)
        val zoneEnd = end.atZone(zoneId)

        if (zoneEnd.isBefore(now)) return

        val minMs = Duration.between(now, zoneStart).toMillis().coerceAtLeast(0)
        val maxMs = Duration.between(now, zoneEnd).toMillis()

        val randomDelay = (minMs..maxMs).random()

        val work = OneTimeWorkRequestBuilder<SendDealNotificationWorker>()
            .setInitialDelay(randomDelay, TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(context).enqueue(work)
    }
}

class RescheduleWorker(
    ctx: Context,
    params: WorkerParameters
) : Worker(ctx, params) {

    override fun doWork(): Result {
        DealNotificationScheduler.scheduleDailyWork(applicationContext)
        return Result.success()
    }
}
