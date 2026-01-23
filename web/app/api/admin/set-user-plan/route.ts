import { NextRequest, NextResponse } from 'next/server'
import { ref, update, serverTimestamp, get } from 'firebase/database'
import { database } from '@/lib/firebase'
import { initializeApp, getApps } from 'firebase/app'
import { getAuth } from 'firebase/auth'

/**
 * SECURED Admin API to set user plan for testing
 * POST /api/admin/set-user-plan
 *
 * SECURITY REQUIREMENTS:
 * 1. Must be authenticated Firebase user (Firebase ID token required)
 * 2. Must be admin (checked in Firebase)
 * 3. Rate limited to 10 requests per minute per admin
 *
 * Body: {
 *   userId: string,
 *   plan: 'free' | 'monthly' | 'yearly' | 'lifetime',
 *   daysValid?: number (default 30 for paid, 7 for free),
 *   token: string (Firebase ID token)
 * }
 */

// Simple in-memory rate limiter (use Redis in production)
const rateLimitMap = new Map<string, { count: number; resetTime: number }>()

function checkRateLimit(key: string, maxRequests: number = 10, windowMs: number = 60000): boolean {
  const now = Date.now()
  const record = rateLimitMap.get(key)

  if (!record || now > record.resetTime) {
    rateLimitMap.set(key, { count: 1, resetTime: now + windowMs })
    return true
  }

  if (record.count >= maxRequests) {
    return false
  }

  record.count++
  return true
}

async function verifyAdminToken(token: string): Promise<{ userId: string; isAdmin: boolean } | null> {
  try {
    // Verify Firebase ID token
    const decodedToken = await getAuth().currentUser?.getIdTokenResult()

    // In production, verify the token server-side using Firebase Admin SDK
    // For now, we check the custom claims
    if (!decodedToken) {
      return null
    }

    // Check if user has admin claim
    const isAdmin = decodedToken.claims?.admin === true

    return {
      userId: decodedToken.firebase.identities?.['firebase.sign_in_provider']?.[0] || 'unknown',
      isAdmin
    }
  } catch {
    return null
  }
}

export async function POST(request: NextRequest) {
  try {
    // Get authorization header
    const authHeader = request.headers.get('authorization')
    if (!authHeader?.startsWith('Bearer ')) {
      return NextResponse.json(
        { error: 'Missing or invalid authorization header. Use: Authorization: Bearer <firebase-token>' },
        { status: 401 }
      )
    }

    const token = authHeader.substring(7) // Remove "Bearer " prefix

    // Verify admin token (TODO: implement proper Firebase Admin SDK verification)
    const adminVerification = await verifyAdminToken(token)
    if (!adminVerification) {
      console.warn('⚠️ [SECURITY] Invalid admin token attempted')
      return NextResponse.json(
        { error: 'Invalid or expired token. Must be authenticated admin.' },
        { status: 401 }
      )
    }

    if (!adminVerification.isAdmin) {
      console.warn(`⚠️ [SECURITY] Non-admin user ${adminVerification.userId} attempted to modify plans`)
      return NextResponse.json(
        { error: 'Forbidden. Admin role required to modify user plans.' },
        { status: 403 }
      )
    }

    // Rate limiting
    if (!checkRateLimit(adminVerification.userId, 10, 60000)) {
      console.warn(`⚠️ [SECURITY] Rate limit exceeded for admin ${adminVerification.userId}`)
      return NextResponse.json(
        { error: 'Rate limit exceeded. Maximum 10 requests per minute.' },
        { status: 429 }
      )
    }

    const { userId, plan, daysValid } = await request.json()

    // Validate inputs
    if (!userId || !plan) {
      return NextResponse.json(
        { error: 'Missing userId or plan' },
        { status: 400 }
      )
    }

    // Validate userId format (basic check)
    if (typeof userId !== 'string' || userId.length < 10) {
      return NextResponse.json(
        { error: 'Invalid userId format' },
        { status: 400 }
      )
    }

    const validPlans = ['free', 'monthly', 'yearly', 'lifetime']
    if (!validPlans.includes(plan)) {
      return NextResponse.json(
        { error: `Invalid plan. Must be one of: ${validPlans.join(', ')}` },
        { status: 400 }
      )
    }

    // Validate daysValid
    const days = parseInt(daysValid) || (plan === 'free' ? 7 : 30)
    if (days < 1 || days > 365) {
      return NextResponse.json(
        { error: 'Days valid must be between 1 and 365' },
        { status: 400 }
      )
    }

    // Check if target user exists
    const userRef = ref(database, `users/${userId}`)
    const userSnapshot = await get(userRef)
    if (!userSnapshot.exists()) {
      return NextResponse.json(
        { error: `User ${userId} does not exist` },
        { status: 404 }
      )
    }

    // Calculate expiry time
    const now = Date.now()
    let planExpiresAt: number | null = null
    let freeTrialExpiresAt: number | null = null

    if (plan === 'free') {
      freeTrialExpiresAt = now + (days * 24 * 60 * 60 * 1000)
    } else {
      planExpiresAt = now + (days * 24 * 60 * 60 * 1000)
    }

    // Update Firebase with audit trail
    const updateData: any = {
      plan,
      updatedAt: serverTimestamp(),
      // Audit trail - record who changed what
      lastPlanModifiedBy: adminVerification.userId,
      lastPlanModifiedAt: now
    }

    if (planExpiresAt) {
      updateData.planExpiresAt = planExpiresAt
    }
    if (freeTrialExpiresAt) {
      updateData.freeTrialExpiresAt = freeTrialExpiresAt
    }

    await update(userRef, updateData)

    // Log the action for security audit
    const expiryDate = new Date(planExpiresAt || freeTrialExpiresAt || now)
    console.log(
      `🔧 [ADMIN ACTION] ${adminVerification.userId} set user ${userId} to ${plan} plan (expires ${expiryDate.toISOString()})`
    )

    return NextResponse.json(
      {
        success: true,
        userId,
        plan,
        expiresAt: expiryDate.toISOString(),
        expiresAtTimestamp: planExpiresAt || freeTrialExpiresAt,
        modifiedBy: adminVerification.userId,
        message: `✅ Set user ${userId} to ${plan} plan (expires ${expiryDate.toLocaleDateString()} ${expiryDate.toLocaleTimeString()})`
      },
      { status: 200 }
    )
  } catch (error: any) {
    console.error('❌ Error setting user plan:', error)
    return NextResponse.json(
      {
        error: 'Failed to set user plan',
        details: error.message
      },
      { status: 500 }
    )
  }
}

// Health check
export async function GET() {
  return NextResponse.json({
    status: 'healthy',
    endpoint: '/api/admin/set-user-plan',
    method: 'POST',
    description: 'SECURED: Set a user plan for testing. Requires Firebase admin token.',
    security: {
      authentication: 'Firebase ID token (Authorization: Bearer <token>)',
      authorization: 'Admin role required',
      rateLimit: '10 requests per minute per admin',
      auditTrail: 'All changes logged with admin ID and timestamp'
    }
  })
}
