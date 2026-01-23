import { NextRequest, NextResponse } from 'next/server'
import {
  runSmartGlobalCleanup,
  sendCleanupReport,
  CleanupStats
} from '@/lib/firebase'

/**
 * Automatic Daily Cleanup API Endpoint
 * Called by Vercel Cron (https://vercel.com/docs/cron-jobs)
 * Or manually via POST request
 *
 * Vercel Cron automatically sends X-Vercel-Cron header
 * No authentication needed - Vercel handles it internally
 *
 * Schedule: 0 2 * * * (2 AM UTC daily)
 */

export async function POST(request: NextRequest) {
  const startTime = Date.now()
  const timestamp = new Date().toISOString()

  console.log(`🚀 [AUTO] Starting automatic cleanup at ${timestamp}`)

  try {
    // Run the actual smart global cleanup
    console.log(`⏳ Running smart global cleanup...`)
    const cleanupResults = await runSmartGlobalCleanup()

    console.log(`✅ Cleanup completed:`, cleanupResults)

    // Create CleanupStats object for email report
    const cleanupStats: CleanupStats = {
      outgoingMessages: cleanupResults.totalItemsCleaned || 0,
      pendingPairings: cleanupResults.emptyNodesDeleted || 0,
      callRequests: 0,
      spamMessages: 0,
      readReceipts: 0,
      oldDevices: 0,
      oldNotifications: 0,
      staleTypingIndicators: 0,
      expiredSessions: 0,
      oldFileTransfers: 0,
      abandonedPairings: 0,
      orphanedMedia: 0,
      orphanedUsers: cleanupResults.orphanedUsersDeleted || 0,
      oldMessages: 0,
      oldMmsMessages: 0
    }

    // Send email report
    try {
      console.log(`📧 Sending cleanup report email...`)
      await sendCleanupReport(cleanupStats, 'system', 'AUTO')
      console.log(`✅ Email report sent successfully`)
    } catch (emailError) {
      console.error('⚠️ Failed to send email report:', emailError)
      // Don't fail the whole cleanup if email fails
    }

    const duration = Date.now() - startTime
    console.log(`🎉 Automatic cleanup completed in ${duration}ms`)

    return NextResponse.json(
      {
        success: true,
        timestamp,
        type: 'AUTO',
        results: cleanupResults,
        durationMs: duration,
        message: `Automatic cleanup completed successfully in ${(duration / 1000).toFixed(1)}s`
      },
      { status: 200 }
    )
  } catch (error: any) {
    const duration = Date.now() - startTime
    console.error(`❌ Error in auto cleanup endpoint (${duration}ms):`, error)

    return NextResponse.json(
      {
        success: false,
        error: 'Cleanup failed',
        details: error.message,
        timestamp,
        durationMs: duration
      },
      { status: 500 }
    )
  }
}

// Health check endpoint
export async function GET() {
  return NextResponse.json(
    {
      status: 'healthy',
      endpoint: '/api/cleanup/auto',
      method: 'POST',
      schedule: '0 2 * * * (2 AM UTC daily)',
      note: 'Runs automatic cleanup via Vercel Cron',
      timestamp: new Date().toISOString()
    },
    { status: 200 }
  )
}
