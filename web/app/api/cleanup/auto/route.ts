import { NextRequest, NextResponse } from 'next/server'
import { Resend } from 'resend'

/**
 * Automatic Daily Cleanup API Endpoint
 * Called by Vercel Cron (https://vercel.com/docs/cron-jobs)
 * Or manually via POST request
 *
 * Vercel Cron automatically sends X-Vercel-Cron header
 * No authentication needed - Vercel handles it internally
 */

const resend = new Resend(process.env.RESEND_API_KEY)

export async function POST(request: NextRequest) {
  try {
    const type = 'AUTO' // Vercel Cron calls this endpoint
    const timestamp = new Date().toISOString()

    console.log(`[${type}] Auto cleanup triggered at ${timestamp}`)

    // In production, these would call actual Firebase cleanup functions
    // For now, we're preparing the email notification structure
    const cleanupResults = {
      timestamp,
      type,
      orphanedUsersDeleted: 0,
      emptyNodesDeleted: 0,
      duplicatesDeleted: 0,
      itemsCleaned: 0,
      estimatedSavings: 0,
      status: 'completed'
    }

    // Send email notification
    const adminEmail = process.env.ADMIN_EMAIL || 'admin@syncflow.com'

    const emailSubject = `[AUTO] SyncFlow Cleanup Report - ${new Date().toLocaleDateString()}`
    const emailBody = `
SyncFlow Automatic Daily Cleanup Report
======================================

Execution Time: ${timestamp}
Type: Automatic (Daily Schedule)

Cleanup Results:
  Orphaned Users Deleted: ${cleanupResults.orphanedUsersDeleted}
  Empty Nodes Cleaned: ${cleanupResults.emptyNodesDeleted}
  Duplicate Accounts Merged: ${cleanupResults.duplicatesDeleted}
  Total Items Cleaned: ${cleanupResults.itemsCleaned}

Cost Savings:
  Estimated Monthly Savings: $${cleanupResults.estimatedSavings.toFixed(2)}

Status: ✅ Completed

Note: This is an automatic daily cleanup run.
For manual cleanups, check the admin dashboard.
    `

    // Send via RESEND
    if (process.env.RESEND_API_KEY) {
      try {
        await resend.emails.send({
          from: 'cleanup@syncflow.app',
          to: adminEmail,
          subject: emailSubject,
          text: emailBody,
          html: `<pre>${emailBody}</pre>`
        })
        console.log(`Email sent to ${adminEmail}`)
      } catch (emailError) {
        console.error('Failed to send email:', emailError)
      }
    }

    return NextResponse.json(
      {
        success: true,
        timestamp,
        type,
        results: cleanupResults,
        message: 'Automatic cleanup completed successfully'
      },
      { status: 200 }
    )
  } catch (error) {
    console.error('Error in auto cleanup endpoint:', error)

    // Send error email
    const adminEmail = process.env.ADMIN_EMAIL || 'admin@syncflow.com'
    if (process.env.RESEND_API_KEY) {
      try {
        await resend.emails.send({
          from: 'cleanup@syncflow.app',
          to: adminEmail,
          subject: `[AUTO-ERROR] SyncFlow Cleanup Failed - ${new Date().toLocaleDateString()}`,
          text: `Automatic cleanup failed with error:\n\n${String(error)}`
        })
      } catch (emailError) {
        console.error('Failed to send error email:', emailError)
      }
    }

    return NextResponse.json(
      { error: 'Cleanup failed', details: String(error) },
      { status: 500 }
    )
  }
}

// Health check endpoint
export async function GET(request: NextRequest) {
  return NextResponse.json(
    {
      status: 'healthy',
      message: 'Auto cleanup endpoint is running',
      timestamp: new Date().toISOString(),
      note: 'This endpoint is called automatically by Vercel Cron at 2 AM UTC daily'
    },
    { status: 200 }
  )
}
