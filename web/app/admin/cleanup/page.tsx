'use client'

import { useState, useEffect, useRef } from 'react'
import { useRouter } from 'next/navigation'
import {
  Trash2,
  RefreshCw,
  Database,
  AlertTriangle,
  Loader2,
  Shield,
  ShieldX,
  HardDrive,
  Mail,
  MessageSquare,
  Users,
  Zap,
  LogOut,
  BarChart3,
  TrendingUp,
  DollarSign,
  Activity,
  Search,
  UserX,
} from 'lucide-react'
import {
  getOrphanCounts,
  runAutoCleanup,
  cleanupStaleOutgoingMessages,
  cleanupExpiredPairings,
  cleanupOldCallRequests,
  cleanupOldSpamMessages,
  cleanupOldReadReceipts,
  cleanupInactiveDevices,
  cleanupOldNotifications,
  cleanupTypingIndicators,
  cleanupExpiredSessions,
  cleanupOldDevices,
  cleanupOldFileTransfers,
  cleanupAbandonedPairings,
  cleanupOrphanedMedia,
  cleanupEmptyUserNodes,
  cleanupOrphanedUsers,
  detectDuplicateUsersByDevice,
  deleteDetectedDuplicates,
  cleanupUserDataByPlan,
  runSmartGlobalCleanup,
  deleteUsersWithoutDevices,
  deleteOldMessages,
  deleteOldMmsMessages,
  enforceSmsFreeMessages,
  getSystemCleanupOverview,
  getAllUserIds,
  getUserDataSummary,
  runAutoCleanupWithReport,
  getSystemOverview,
  getDetailedUserList,
  getSystemAnalytics,
  getAdminAuditLog,
  bulkDeleteInactiveUsers,
  deleteUserAccount,
  getCostOptimizationRecommendations,
  OrphanCounts,
  CleanupStats,
  database,
} from '@/lib/firebase'
import { ref, update, serverTimestamp, get } from 'firebase/database'

interface AdminSession {
  authenticated: boolean
  timestamp: number
  expiresAt: number
}

interface SystemOverview {
  totalUsers: number
  activeUsers: number
  totalMessages: number
  totalStorageMB: number
  databaseSize: number
  firebaseCosts: {
    estimatedMonthly: number
    breakdown: {
      database: number
      storage: number
      functions: number
    }
  }
  systemHealth: {
    status: 'healthy' | 'warning' | 'critical'
    issues: string[]
  }
}

interface DetailedUser {
  userId: string
  messagesCount: number
  devicesCount: number
  storageUsedMB: number
  lastActivity: number | null
  plan: string
  planExpiresAt: number | null
  planAssignedAt: number | null
  planAssignedBy: string
  wasPremium: boolean
  isActive: boolean
}

interface CostRecommendation {
  type: 'storage' | 'database' | 'cleanup' | 'scaling'
  priority: 'high' | 'medium' | 'low'
  title: string
  description: string
  potentialSavings: number
  action: string
}

// Check if admin session is valid
const isValidAdminSession = (): boolean => {
  try {
    const sessionStr = localStorage.getItem('syncflow_admin_session')
    if (!sessionStr) return false

    const session: AdminSession = JSON.parse(sessionStr)
    if (!session.authenticated) return false
    if (Date.now() > session.expiresAt) {
      localStorage.removeItem('syncflow_admin_session')
      return false
    }
    return true
  } catch {
    return false
  }
}

export default function AdminCleanupPage() {
  const router = useRouter()
  const [userId, setUserId] = useState<string | null>(null)
  const [isLoading, setIsLoading] = useState(true)
  const [isAuthorized, setIsAuthorized] = useState(false)
  const [isRunningAuto, setIsRunningAuto] = useState(false)
  const [cleanupLog, setCleanupLog] = useState<string[]>([])

  // Comprehensive admin state
  const [activeTab, setActiveTab] = useState<'overview' | 'users' | 'data' | 'costs' | 'testing'>('overview')
  const [systemOverview, setSystemOverview] = useState<SystemOverview | null>(null)
  const [detailedUsers, setDetailedUsers] = useState<DetailedUser[]>([])
  const [costRecommendations, setCostRecommendations] = useState<CostRecommendation[]>([])
  const [userSearchTerm, setUserSearchTerm] = useState('')
  const [userFilter, setUserFilter] = useState<'all' | 'active' | 'inactive'>('all')

  const onFilterChange = (filter: 'all' | 'active' | 'inactive') => {
    setUserFilter(filter)
  }
  const [isLoadingOverview, setIsLoadingOverview] = useState(false)
  const [deletingUser, setDeletingUser] = useState<string | null>(null)
  const [isRunningBulkDelete, setIsRunningBulkDelete] = useState(false)
  const [isRunningDeviceCleanup, setIsRunningDeviceCleanup] = useState(false)

  // Cost optimization - NEW state
  const [isDetectingOrphans, setIsDetectingOrphans] = useState(false)
  const [isRunningSmartCleanup, setIsRunningSmartCleanup] = useState(false)
  const [isDetectingDuplicates, setIsDetectingDuplicates] = useState(false)
  const [isDeletingDuplicates, setIsDeletingDuplicates] = useState(false)
  const [orphanCounts, setOrphanCounts] = useState<OrphanCounts | null>(null)
  const [duplicatesList, setDuplicatesList] = useState<any[]>([])
  const [isDeletingNoDeviceUsers, setIsDeletingNoDeviceUsers] = useState(false)
  const [isDeletingOldMessages, setIsDeletingOldMessages] = useState(false)
  const [isDeletingOldMms, setIsDeletingOldMms] = useState(false)
  const [isEnforcingSms, setIsEnforcingSms] = useState(false)

  // Testing state
  const [testUserId, setTestUserId] = useState('')
  const [testPlan, setTestPlan] = useState<'free' | 'monthly' | 'yearly' | 'lifetime'>('free')
  const [testDaysValid, setTestDaysValid] = useState('7')
  const [isSettingPlan, setIsSettingPlan] = useState(false)

  const logContainerRef = useRef<HTMLDivElement>(null)

  const addLog = (message: string) => {
    setCleanupLog(prev => [...prev, `${new Date().toLocaleTimeString()}: ${message}`])
  }

  // Comprehensive admin functions
  const loadSystemOverview = async () => {
    setIsLoadingOverview(true)
    try {
      const overview = await getSystemOverview()
      setSystemOverview(overview)
      addLog('System overview loaded successfully')
    } catch (error) {
      addLog('Error loading system overview')
      console.error(error)
    } finally {
      setIsLoadingOverview(false)
    }
  }

  const loadDetailedUsers = async () => {
    try {
      const users = await getDetailedUserList()
      setDetailedUsers(users)
      addLog(`Loaded ${users.length} user details`)
    } catch (error) {
      addLog('Error loading user details')
      console.error(error)
    }
  }

  const loadCostRecommendations = async () => {
    try {
      const recommendations = await getCostOptimizationRecommendations()
      setCostRecommendations(recommendations)
      addLog(`Loaded ${recommendations.length} cost optimization recommendations`)
    } catch (error) {
      addLog('Error loading cost recommendations')
      console.error(error)
    }
  }

  const handleDeleteUser = async (userId: string) => {
    if (!confirm(`Are you sure you want to delete user "${userId}"? This will permanently remove all their data including messages, contacts, and settings. This action cannot be undone.`)) {
      return
    }

    setDeletingUser(userId)
    try {
      const result = await deleteUserAccount(userId)
      if (result.success) {
        addLog(`User ${userId} deleted successfully: ${result.deletedData.messages} messages, ${result.deletedData.devices} devices, ${result.deletedData.storageMB}MB storage freed`)
        await loadSystemOverview()
        await loadDetailedUsers()
      } else {
        addLog(`Failed to delete user ${userId}: ${result.errors.join(', ')}`)
      }
    } catch (error) {
      addLog(`Error deleting user ${userId}`)
      console.error(error)
    } finally {
      setDeletingUser(null)
    }
  }

  const handleBulkDeleteInactive = async () => {
    if (!confirm('Are you sure you want to delete all users inactive for 90+ days? This action cannot be undone.')) {
      return
    }

    setIsRunningBulkDelete(true)
    try {
      const result = await bulkDeleteInactiveUsers(90)
      addLog(`Bulk delete completed: ${result.deletedUsers} users deleted, ${result.freedStorageMB}MB storage freed`)
      await loadSystemOverview()
      await loadDetailedUsers()
    } catch (error) {
      addLog('Error during bulk delete')
      console.error(error)
    } finally {
      setIsRunningBulkDelete(false)
    }
  }

  const implementRecommendation = async (recommendation: CostRecommendation) => {
    const confirmMessage = `Are you sure you want to implement: "${recommendation.title}"?\n\n${recommendation.description}\n\nThis action may affect system performance and user data.`

    if (!confirm(confirmMessage)) {
      return
    }

    try {
      addLog(`Implementing: ${recommendation.title}`)

      switch (recommendation.type) {
        case 'cleanup':
          if (recommendation.title.includes('Inactive User')) {
            const result = await bulkDeleteInactiveUsers(90)
            addLog(`Cleanup completed: ${result.deletedUsers} users deleted, ${result.freedStorageMB}MB storage freed`)
          } else {
            const cleanupResult = await runAutoCleanupWithReport(userId || 'admin')
            const totalRecords = Object.values(cleanupResult.cleanupStats).reduce((a, b) => a + b, 0)
            addLog(`Cleanup completed: ${totalRecords} records removed`)

            if (cleanupResult.reportSent) {
              addLog(`✅ Cleanup report generated and sent successfully`)
              addLog(`📧 Email report sent to admin email`)
            } else {
              addLog(`📝 Cleanup report generated and logged to console`)
              if (cleanupResult.reportError?.includes('RESEND_API_KEY not configured')) {
                addLog(`📧 To enable email reports, configure RESEND_API_KEY in Vercel environment variables`)
              } else {
                addLog(`❌ Report sending failed: ${cleanupResult.reportError}`)
              }
            }
          }
          break
        default:
          addLog(`Implementation not yet available for: ${recommendation.type}`)
      }

      await loadSystemOverview()
      await loadCostRecommendations()
    } catch (error) {
      addLog(`Error implementing recommendation: ${error}`)
      console.error('Implementation error:', error)
    }
  }

  const handleAutoCleanup = async () => {
    if (!userId) {
      addLog('Cannot run cleanup: Admin authentication required')
      return
    }

    setIsRunningAuto(true)
    addLog('Starting auto cleanup with detailed email reporting...')

    try {
      const result = await runAutoCleanupWithReport(userId)
      const stats = result.cleanupStats
      const total = Object.values(stats).reduce((sum, count) => sum + count, 0)

      addLog(`Admin cleanup complete! Deleted ${total} records`)
      addLog(`  📧 Outgoing messages: ${stats.outgoingMessages}`)
      addLog(`  🔗 Expired pairings: ${stats.pendingPairings}`)
      addLog(`  📞 Old call requests: ${stats.callRequests}`)
      addLog(`  🗑️ Old spam messages: ${stats.spamMessages}`)
      addLog(`  ✅ Old read receipts: ${stats.readReceipts}`)

      if (result.reportSent) {
        addLog(`✅ Cleanup report generated and sent successfully`)
        addLog(`📧 Email report sent to admin email`)
      } else {
        addLog(`📝 Cleanup report generated and logged to console`)
        if (result.reportError?.includes('RESEND_API_KEY not configured')) {
          addLog(`📧 To enable email reports, configure RESEND_API_KEY in Vercel environment variables`)
        } else {
          addLog(`❌ Report sending failed: ${result.reportError}`)
        }
      }
    } catch (error) {
      addLog('Error during admin cleanup')
      console.error(error)
    } finally {
      setIsRunningAuto(false)
    }
  }

  const handleDeviceCleanup = async () => {
    if (!userId) {
      addLog('Cannot run device cleanup: Authentication required')
      return
    }

    setIsRunningDeviceCleanup(true)
    addLog('Starting device cleanup...')

    try {
      const result = await cleanupOldDevices()

      if (result.success) {
        addLog(`✅ Device cleanup complete! Cleaned ${result.cleaned} old device entries`)
        addLog(`📊 Found ${result.deviceInfo?.length || 0} total devices across all users`)

        if (result.deviceInfo && result.deviceInfo.length > 0) {
          addLog('📋 Device details:')
          result.deviceInfo.forEach((device: any) => {
            const status = result.devices?.some((d: any) => d.deviceId === device.deviceId) ? '🗑️ REMOVED' : '✅ KEPT'
            const lastSeen = new Date(device.lastSeen).toLocaleString()
            addLog(`  ${status} ${device.deviceId} (${device.platform}) - Last seen: ${lastSeen} (${device.lastSeenHoursAgo}h ago)`)
          })
        }

        if (result.debug) {
          addLog(`🔍 Debug: ${result.debug.totalDevicesFound} devices found, ${result.debug.cleanedCount} cleaned, threshold: ${result.debug.thresholdHours}h`)
        }
      } else {
        addLog(`❌ Device cleanup failed: ${result.message}`)
        if (result.error) {
          addLog(`   Error: ${result.error}`)
        }
      }
    } catch (error) {
      addLog('Error during device cleanup')
      console.error(error)
    } finally {
      setIsRunningDeviceCleanup(false)
    }
  }

  // NEW: Detect orphaned nodes and users - COST OPTIMIZATION
  const handleDetectOrphans = async () => {
    setIsDetectingOrphans(true)
    addLog('🔍 Scanning for orphaned nodes and users...')

    try {
      const counts = await getOrphanCounts()
      setOrphanCounts(counts)

      addLog('✅ Orphan detection complete!')
      addLog(`📊 Orphan Summary:`)
      addLog(`  🗑️ Empty user nodes (no data, 7+ days): ${counts.emptyUserNodes}`)
      addLog(`  👻 Orphaned users (no messages, 30+ days): ${counts.orphanedUsers}`)
      addLog(`  📧 Stale outgoing messages: ${counts.staleOutgoingMessages}`)
      addLog(`  🔗 Expired pairings: ${counts.expiredPairings}`)
      addLog(`  📞 Old call requests: ${counts.oldCallRequests}`)
      addLog(`  🚫 Old spam messages: ${counts.oldSpamMessages}`)
      addLog(`  ✅ Old read receipts: ${counts.oldReadReceipts}`)
      addLog(`  📱 Inactive devices: ${counts.inactiveDevices}`)
      addLog(`  🔔 Old notifications: ${counts.oldNotifications}`)

      const totalOrphaned = counts.emptyUserNodes + counts.orphanedUsers
      const potentialSavings = (totalOrphaned * 0.002).toFixed(2) // Rough estimate: $0.002 per orphaned user/node
      addLog(`\n💰 Potential monthly savings if cleaned: $${potentialSavings}`)
    } catch (error) {
      addLog(`❌ Error detecting orphans: ${error}`)
      console.error(error)
    } finally {
      setIsDetectingOrphans(false)
    }
  }

  // NEW: Run smart global cleanup - COST OPTIMIZATION
  const handleSmartGlobalCleanup = async () => {
    if (!confirm('⚠️ This will delete all orphaned users and empty nodes.\n\nFree tier users inactive 14+ days will be removed.\nPaid users inactive 60+ days will be removed.\n\nAre you sure?')) {
      return
    }

    setIsRunningSmartCleanup(true)
    addLog('🚀 Starting smart global cleanup...')

    try {
      const results = await runSmartGlobalCleanup()

      addLog('✅ Smart global cleanup complete!')
      addLog(`📊 Cleanup Results:`)
      addLog(`  👥 Users processed: ${results.usersProcessed}`)
      addLog(`  🗑️ Items cleaned per user: ${results.totalItemsCleaned}`)
      addLog(`  👻 Empty nodes deleted: ${results.emptyNodesDeleted}`)
      addLog(`  💀 Orphaned users deleted: ${results.orphanedUsersDeleted}`)
      addLog(`  🔄 Potential duplicate accounts detected: ${results.duplicatesDetected}`)

      const totalDeleted = results.emptyNodesDeleted + results.orphanedUsersDeleted
      const estimatedSavings = (totalDeleted * 0.005).toFixed(2) // $0.005 per deleted node
      addLog(`\n💰 Estimated monthly savings: $${estimatedSavings}`)

      await loadSystemOverview()
      await loadDetailedUsers()
    } catch (error) {
      addLog(`❌ Error during smart cleanup: ${error}`)
      console.error(error)
    } finally {
      setIsRunningSmartCleanup(false)
    }
  }

  // NEW: Detect duplicate users from same device - COST OPTIMIZATION
  const handleDetectDuplicates = async () => {
    setIsDetectingDuplicates(true)
    addLog('🔍 Scanning for duplicate users on same device...')

    try {
      const duplicates = await detectDuplicateUsersByDevice()
      setDuplicatesList(duplicates)

      if (duplicates.length === 0) {
        addLog('✅ No duplicate users found!')
      } else {
        addLog(`⚠️ Found ${duplicates.length} devices with multiple users:`)

        duplicates.forEach((dup, idx) => {
          const mergeCandidate = dup.potentialMergeCandidates ? '🔴 LIKELY DUPLICATE' : '🟡 POSSIBLE DUPLICATE'
          addLog(`\n  ${idx + 1}. Device: ${dup.deviceId.substring(0, 20)}...`)
          addLog(`     ${mergeCandidate}`)
          addLog(`     Users: ${dup.userIds.length}`)
          dup.userIds.forEach((uid: string) => {
            addLog(`       - ${uid.substring(0, 20)}...`)
          })
        })

        const totalDupes = duplicates.reduce((sum, d) => sum + (d.userIds.length - 1), 0)
        addLog(`\n💡 Total duplicate accounts to delete: ${totalDupes}`)
        addLog(`💰 Cleanup could save: $${(totalDupes * 0.005).toFixed(2)}/month`)
      }
    } catch (error) {
      addLog(`❌ Error detecting duplicates: ${error}`)
      console.error(error)
    } finally {
      setIsDetectingDuplicates(false)
    }
  }

  // NEW: Delete detected duplicate users - COST OPTIMIZATION
  const handleDeleteDuplicates = async () => {
    if (duplicatesList.length === 0) {
      addLog('⚠️ Please run "Detect Duplicates" first to identify accounts to delete')
      return
    }

    const totalDupes = duplicatesList.reduce((sum, d) => sum + (d.userIds.length - 1), 0)
    if (!confirm(`⚠️ This will DELETE ${totalDupes} duplicate accounts.\n\nWill keep the newest account per device and delete older ones.\n\nThis action CANNOT be undone. Are you sure?`)) {
      return
    }

    setIsDeletingDuplicates(true)
    addLog('🗑️ Starting deletion of duplicate users...')

    try {
      const result = await deleteDetectedDuplicates()

      if (result.success) {
        addLog('✅ Duplicate deletion complete!')
        addLog(`📊 Deletion Results:`)
        addLog(`  🗑️ Users deleted: ${result.deletedCount}`)
        addLog(`  📱 Devices cleaned: ${result.devicesProcessed}`)

        result.details.forEach(detail => {
          addLog(`  ${detail}`)
        })

        const estimatedSavings = (result.deletedCount * 0.005).toFixed(2)
        addLog(`\n💰 Estimated monthly savings: $${estimatedSavings}`)

        // Clear duplicates list and reload overview
        setDuplicatesList([])
        await loadSystemOverview()
        await loadDetailedUsers()
      } else {
        addLog(`❌ Deletion failed: ${result.details.join(', ')}`)
      }
    } catch (error) {
      addLog(`❌ Error during deletion: ${error}`)
      console.error(error)
    } finally {
      setIsDeletingDuplicates(false)
    }
  }

  const handleDeleteNoDeviceUsers = async () => {
    if (!confirm('⚠️ This will DELETE all users with no devices.\n\nThese orphaned accounts cannot access messages and are taking up storage.\n\nThis action CANNOT be undone. Are you sure?')) {
      return
    }

    setIsDeletingNoDeviceUsers(true)
    addLog('🗑️ Starting deletion of users without devices...')

    try {
      const result = await deleteUsersWithoutDevices()

      if (result.success) {
        addLog('✅ User deletion complete!')
        addLog(`📊 Deletion Results:`)
        addLog(`  🗑️ Users deleted: ${result.deletedCount}`)

        result.details.forEach(detail => {
          addLog(`  ${detail}`)
        })

        const estimatedSavings = (result.deletedCount * 0.005).toFixed(2)
        addLog(`\n💰 Estimated monthly savings: $${estimatedSavings}`)

        await loadSystemOverview()
        await loadDetailedUsers()
      } else {
        addLog(`❌ Deletion failed: ${result.details.join(', ')}`)
      }
    } catch (error) {
      addLog(`❌ Error during deletion: ${error}`)
      console.error(error)
    } finally {
      setIsDeletingNoDeviceUsers(false)
    }
  }

  const handleDeleteOldMessages = async () => {
    if (!confirm('⚠️ This will DELETE all messages older than the retention period.\n\nFree tier: 30 days\nPaid tier: 90 days\n\nThis action CANNOT be undone. Are you sure?')) {
      return
    }

    setIsDeletingOldMessages(true)
    addLog('🗑️ Starting deletion of old messages...')

    try {
      const result = await deleteOldMessages()

      if (result.success) {
        addLog('✅ Message cleanup complete!')
        addLog(`📊 Deletion Results:`)
        addLog(`  📨 Total messages deleted: ${result.messagesDeleted}`)

        Object.entries(result.detailsByUser).forEach(([userId, count]) => {
          addLog(`  ${userId.substring(0, 20)}...: ${count} messages`)
        })

        // Estimate: ~500 bytes per message
        const savingsMB = (result.messagesDeleted * 0.0005).toFixed(2)
        const savingsMonthlyCost = (parseFloat(savingsMB) * 5 / 1024).toFixed(4) // $5 per GB per month
        addLog(`\n💾 Storage freed: ~${savingsMB} MB`)
        addLog(`💰 Estimated monthly savings: $${savingsMonthlyCost}`)

        await loadSystemOverview()
      } else {
        addLog(`❌ Message cleanup failed`)
      }
    } catch (error) {
      addLog(`❌ Error during cleanup: ${error}`)
      console.error(error)
    } finally {
      setIsDeletingOldMessages(false)
    }
  }

  const handleDeleteOldMms = async () => {
    if (!confirm('⚠️ This will DELETE all MMS messages older than the retention period.\n\nFree tier: 7 days (aggressive cleanup to save storage)\nPaid tier: 90 days\n\nAttachments (images, videos, etc.) will be permanently deleted.\n\nThis action CANNOT be undone. Are you sure?')) {
      return
    }

    setIsDeletingOldMms(true)
    addLog('🗑️ Starting deletion of old MMS messages...')

    try {
      const result = await deleteOldMmsMessages()

      if (result.success) {
        addLog('✅ MMS cleanup complete!')
        addLog(`📊 Deletion Results:`)
        addLog(`  📱 Total MMS deleted: ${result.mmsDeleted}`)
        addLog(`  📎 Total attachments removed: ${result.attachmentsRemoved}`)

        Object.entries(result.detailsByUser).forEach(([userId, count]) => {
          addLog(`  ${userId.substring(0, 20)}...: ${count} MMS`)
        })

        // Estimate: ~2MB per MMS with attachments
        const savingsMB = (result.mmsDeleted * 2).toFixed(2)
        const savingsMonthlyCost = (parseFloat(savingsMB) * 5 / 1024).toFixed(4) // $5 per GB per month
        addLog(`\n💾 Storage freed: ~${savingsMB} MB`)
        addLog(`💰 Estimated monthly savings: $${savingsMonthlyCost}`)

        await loadSystemOverview()
      } else {
        addLog(`❌ MMS cleanup failed`)
      }
    } catch (error) {
      addLog(`❌ Error during MMS cleanup: ${error}`)
      console.error(error)
    } finally {
      setIsDeletingOldMms(false)
    }
  }

  const handleSetUserPlan = async () => {
    if (!testUserId.trim()) {
      addLog('⚠️ Please enter a User ID')
      return
    }

    setIsSettingPlan(true)
    addLog(`🔧 Assigning ${testPlan} plan to user ${testUserId}...`)

    try {
      // Check user exists in usage path
      const usageRef = ref(database, `users/${testUserId}/usage`)
      const usageSnapshot = await get(usageRef)

      if (!usageSnapshot.exists()) {
        addLog(`❌ User ${testUserId} not found`)
        setIsSettingPlan(false)
        return
      }

      const now = Date.now()
      const days = parseInt(testDaysValid) || 7
      const updateData: any = {
        plan: testPlan,
        planUpdatedAt: now,
      }

      if (testPlan === 'free') {
        // For free trial: set freeTrialExpiresAt
        updateData.freeTrialExpiresAt = now + (days * 24 * 60 * 60 * 1000)
        // Clear subscription expiry
        updateData.planExpiresAt = null
      } else if (testPlan === 'lifetime') {
        // For lifetime: no expiry date needed
        updateData.planExpiresAt = null
        // Clear trial expiry
        updateData.freeTrialExpiresAt = null
      } else {
        // For monthly/yearly: set planExpiresAt
        updateData.planExpiresAt = now + (days * 24 * 60 * 60 * 1000)
        // Clear trial expiry
        updateData.freeTrialExpiresAt = null
      }

      // Update at the correct path: users/{uid}/usage/
      await update(usageRef, updateData)
      addLog(`📝 Updated users/${testUserId}/usage/`)

      // ALSO UPDATE SUBSCRIPTION RECORDS (Persistent across user deletion)
      const subscriptionRecordRef = ref(database, `subscription_records/${testUserId}`)
      const subscriptionRecordSnapshot = await get(subscriptionRecordRef)
      const previousPlan = subscriptionRecordSnapshot.exists() ? subscriptionRecordSnapshot.child('active/plan').val() : null

      // Update active subscription record
      const subscriptionUpdateData: any = {
        'active/plan': testPlan,
        'active/planAssignedAt': now,
        'active/planAssignedBy': 'testing_tab',
      }

      if (testPlan === 'free') {
        subscriptionUpdateData['active/freeTrialExpiresAt'] = now + (days * 24 * 60 * 60 * 1000)
        subscriptionUpdateData['active/planExpiresAt'] = null
      } else if (testPlan === 'lifetime') {
        subscriptionUpdateData['active/planExpiresAt'] = null
        subscriptionUpdateData['active/freeTrialExpiresAt'] = null
      } else {
        subscriptionUpdateData['active/planExpiresAt'] = now + (days * 24 * 60 * 60 * 1000)
        subscriptionUpdateData['active/freeTrialExpiresAt'] = null
      }

      // Track if this is a premium plan
      const isPremium = ['monthly', 'yearly', 'lifetime'].includes(testPlan)
      if (isPremium && !subscriptionRecordSnapshot.exists()) {
        subscriptionUpdateData['wasPremium'] = true
        subscriptionUpdateData['firstPremiumDate'] = now
      } else if (isPremium && subscriptionRecordSnapshot.child('wasPremium').val() !== true) {
        subscriptionUpdateData['wasPremium'] = true
        if (!subscriptionRecordSnapshot.child('firstPremiumDate').exists()) {
          subscriptionUpdateData['firstPremiumDate'] = now
        }
      }

      // Add history entry
      const historyEntry: any = {
        timestamp: now,
        newPlan: testPlan,
        source: 'testing_tab',
      }
      if (previousPlan) {
        historyEntry.previousPlan = previousPlan
      }
      if (updateData.planExpiresAt) {
        historyEntry.expiresAt = updateData.planExpiresAt
      } else if (updateData.freeTrialExpiresAt) {
        historyEntry.expiresAt = updateData.freeTrialExpiresAt
      }

      subscriptionUpdateData[`history/${now}`] = historyEntry

      // Update subscription records
      await update(subscriptionRecordRef, subscriptionUpdateData)
      addLog(`🔒 Updated subscription_records/${testUserId}/ (persists after user deletion)`)

      const expiryDate = new Date(updateData.planExpiresAt || updateData.freeTrialExpiresAt || now)
      addLog(`✅ User plan updated to ${testPlan}`)
      addLog(`⏰ Expires at: ${expiryDate.toISOString()}`)
      addLog(`📍 User must sign out and back in to see changes`)

      setTestUserId('')
      setTestPlan('free')
      setTestDaysValid('7')
    } catch (error: any) {
      addLog(`❌ Error: ${error.message}`)
      console.error(error)
    } finally {
      setIsSettingPlan(false)
    }
  }

  const handleEnforceSmsFree = async () => {
    if (!confirm('⚠️ This will REMOVE all non-SMS messages (calls, media, etc.) from FREE tier users.\n\nThis enforces SMS-only messaging for free accounts.\n\nPaid users will not be affected.\n\nThis action CANNOT be undone. Are you sure?')) {
      return
    }

    setIsEnforcingSms(true)
    addLog('📱 Starting SMS-only enforcement for free tier...')

    try {
      const result = await enforceSmsFreeMessages()

      if (result.success) {
        addLog('✅ SMS enforcement complete!')
        addLog(`📊 Results:`)
        addLog(`  👥 Free users processed: ${result.usersProcessed}`)
        addLog(`  🗑️ Non-SMS messages removed: ${result.messagesRemoved}`)

        const savingsMB = (result.messagesRemoved * 0.001).toFixed(2)
        const savingsMonthlyCost = (parseFloat(savingsMB) * 5 / 1024).toFixed(4)
        addLog(`\n💾 Storage freed: ~${savingsMB} MB`)
        addLog(`💰 Estimated monthly savings: $${savingsMonthlyCost}`)

        await loadSystemOverview()
      } else {
        addLog(`❌ SMS enforcement failed`)
      }
    } catch (error) {
      addLog(`❌ Error during enforcement: ${error}`)
      console.error(error)
    } finally {
      setIsEnforcingSms(false)
    }
  }

  const handleLogout = () => {
    localStorage.removeItem('syncflow_admin_session')
    localStorage.removeItem('syncflow_user_id')
    router.push('/admin/login')
  }

  // Initialize admin panel
  useEffect(() => {
    console.log('AdminCleanupPage: Initializing...')
    const init = async () => {
      try {
        console.log('AdminCleanupPage: Checking session validity...')
        if (!isValidAdminSession()) {
          console.log('AdminCleanupPage: No valid session, redirecting to login')
          router.push('/admin/login')
          return
        }

        console.log('AdminCleanupPage: Session valid, setting up admin access')
        setIsAuthorized(true)
        setUserId('admin')
        addLog('Admin panel initialized with system access')

        // Load data but don't block UI loading
        console.log('AdminCleanupPage: Loading system overview...')
        loadSystemOverview().catch(error => {
          console.error('System overview error:', error)
          addLog('Failed to load system overview')
        })

        // Clear loading state immediately after auth check
        setIsLoading(false)
        console.log('AdminCleanupPage: Loading state cleared')

      } catch (error) {
        console.error('AdminCleanupPage: Initialization error:', error)
        setIsLoading(false) // Ensure loading is cleared even on error
        addLog('Admin panel initialization failed')
      }
    }

    init()

    // Safety timeout to clear loading state
    const timeout = setTimeout(() => {
      if (isLoading) {
        console.warn('AdminCleanupPage: Loading timeout reached, clearing loading state')
        setIsLoading(false)
        addLog('Loading timeout - some data may not be loaded. Try refreshing the page.')
      }
    }, 15000) // 15 seconds timeout

    return () => clearTimeout(timeout)
  }, [router])

  // Auto-scroll to top when new log entries are added
  useEffect(() => {
    logContainerRef.current?.scrollTo({ top: 0, behavior: 'smooth' })
  }, [cleanupLog])

  if (isLoading) {
    return (
      <div className="min-h-screen bg-gray-900 flex items-center justify-center">
        <Loader2 className="w-8 h-8 text-blue-500 animate-spin" />
      </div>
    )
  }

  if (!isAuthorized) {
    return (
      <div className="min-h-screen bg-gray-900 flex items-center justify-center">
        <div className="text-center">
          <ShieldX className="w-16 h-16 text-red-500 mx-auto mb-4" />
          <h1 className="text-2xl font-bold text-white mb-2">Access Denied</h1>
          <p className="text-gray-400 mb-6">You do not have permission to access this page.</p>
          <button
            onClick={() => router.push('/messages')}
            className="px-6 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 transition-colors"
          >
            Go to Messages
          </button>
        </div>
      </div>
    )
  }

  return (
    <div className="h-screen bg-gray-900 text-white overflow-hidden flex flex-col">
      {/* Header */}
      <div className="flex items-center justify-between p-6 border-b border-gray-800">
        <div className="flex items-center gap-3">
          <Shield className="w-8 h-8 text-blue-500" />
          <div>
            <h1 className="text-2xl font-bold">SyncFlow Admin Dashboard</h1>
            <p className="text-gray-400 text-sm">Comprehensive system management and cost optimization</p>
          </div>
        </div>
        <div className="flex items-center gap-2">
          <button
            onClick={async () => {
              await loadSystemOverview()
              await loadDetailedUsers()
              await loadCostRecommendations()
              addLog('Dashboard data refreshed')
            }}
            className="flex items-center gap-2 px-4 py-2 bg-gray-800 hover:bg-gray-700 rounded-lg transition-colors"
          >
            <RefreshCw className="w-4 h-4" />
            Refresh All
          </button>
          <button
            onClick={handleLogout}
            className="flex items-center gap-2 px-4 py-2 bg-red-600 hover:bg-red-700 rounded-lg transition-colors"
          >
            <LogOut className="w-4 h-4" />
            Logout
          </button>
        </div>
      </div>

      {/* Navigation Tabs */}
      <div className="flex border-b border-gray-800 px-6">
        {[
          { id: 'overview', label: 'Overview', icon: BarChart3 },
          { id: 'users', label: 'Users', icon: Users },
          { id: 'data', label: 'Data & Cleanup', icon: Database },
          { id: 'costs', label: 'Costs & Analytics', icon: DollarSign },
          { id: 'testing', label: 'Testing', icon: Zap },
        ].map(({ id, label, icon: Icon }) => (
          <button
            key={id}
            onClick={() => {
              setActiveTab(id as any)
              if (id === 'overview' && !systemOverview) loadSystemOverview()
              if (id === 'users' && detailedUsers.length === 0) loadDetailedUsers()
              if (id === 'costs' && costRecommendations.length === 0) loadCostRecommendations()
            }}
            className={`flex items-center gap-2 px-4 py-3 border-b-2 font-medium text-sm transition-colors ${
              activeTab === id
                ? 'border-blue-500 text-blue-400'
                : 'border-transparent text-gray-400 hover:text-gray-300'
            }`}
          >
            <Icon className="w-4 h-4" />
            {label}
          </button>
        ))}
      </div>

      {/* Tab Content */}
      <div className="flex-1 overflow-y-auto p-6">
        {activeTab === 'overview' && (
          <div className="space-y-6">
            <div className="bg-gray-800 rounded-xl p-6">
              <div className="flex items-center justify-between mb-4">
                <h2 className="text-xl font-bold">System Health</h2>
                <button onClick={loadSystemOverview} disabled={isLoadingOverview} className="flex items-center gap-2 px-4 py-2 bg-blue-600 hover:bg-blue-700 disabled:opacity-50 rounded-lg transition-colors">
                  {isLoadingOverview ? <Loader2 className="w-4 h-4 animate-spin" /> : <RefreshCw className="w-4 h-4" />}
                  {isLoadingOverview ? 'Loading...' : 'Refresh'}
                </button>
              </div>

              {systemOverview && (
                <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
                  <div className="bg-gray-700 rounded-lg p-4">
                    <div className="flex items-center gap-2 mb-2"><Users className="w-5 h-5 text-green-500" /><span className="text-sm font-medium">Total Users</span></div>
                    <p className="text-2xl font-bold">{systemOverview.totalUsers}</p>
                  </div>
                  <div className="bg-gray-700 rounded-lg p-4">
                    <div className="flex items-center gap-2 mb-2"><Activity className="w-5 h-5 text-blue-500" /><span className="text-sm font-medium">Active Users</span></div>
                    <p className="text-2xl font-bold">{systemOverview.activeUsers}</p>
                  </div>
                  <div className="bg-gray-700 rounded-lg p-4">
                    <div className="flex items-center gap-2 mb-2"><MessageSquare className="w-5 h-5 text-purple-500" /><span className="text-sm font-medium">Total Messages</span></div>
                    <p className="text-2xl font-bold">{systemOverview.totalMessages.toLocaleString()}</p>
                  </div>
                  <div className="bg-gray-700 rounded-lg p-4">
                    <div className="flex items-center gap-2 mb-2"><HardDrive className="w-5 h-5 text-yellow-500" /><span className="text-sm font-medium">Storage Used</span></div>
                    <p className="text-2xl font-bold">{systemOverview.totalStorageMB}MB</p>
                  </div>
                </div>
              )}
            </div>
          </div>
        )}

        {activeTab === 'users' && (
          <div className="space-y-6">
            <div className="bg-gray-800 rounded-xl p-6">
              <div className="flex items-center justify-between mb-4">
                <h2 className="text-xl font-bold">User Management</h2>
                <div className="flex items-center gap-2">
                  <button onClick={loadDetailedUsers} className="flex items-center gap-2 px-4 py-2 bg-blue-600 hover:bg-blue-700 rounded-lg transition-colors">
                    <RefreshCw className="w-4 h-4" />Load Users
                  </button>
                  <button onClick={handleBulkDeleteInactive} disabled={isRunningBulkDelete} className="flex items-center gap-2 px-4 py-2 bg-red-600 hover:bg-red-700 disabled:opacity-50 rounded-lg transition-colors">
                    {isRunningBulkDelete ? <Loader2 className="w-4 h-4 animate-spin" /> : <UserX className="w-4 h-4" />}
                    {isRunningBulkDelete ? 'Deleting...' : 'Delete Inactive'}
                  </button>
                </div>
              </div>

              <div className="flex items-center gap-4 mb-4">
                <div className="flex-1">
                  <div className="relative">
                    <Search className="absolute left-3 top-1/2 transform -translate-y-1/2 w-4 h-4 text-gray-400" />
                    <input type="text" placeholder="Search users..." value={userSearchTerm} onChange={(e) => setUserSearchTerm(e.target.value)} className="w-full pl-10 pr-4 py-2 bg-gray-700 border border-gray-600 rounded-lg text-white placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-blue-500" />
                  </div>
                </div>
                <select value={userFilter} onChange={(e) => onFilterChange(e.target.value as 'all' | 'active' | 'inactive')} className="px-4 py-2 bg-gray-700 border border-gray-600 rounded-lg text-white focus:outline-none focus:ring-2 focus:ring-blue-500">
                  <option value="all">All Users</option>
                  <option value="active">Active Only</option>
                  <option value="inactive">Inactive Only</option>
                </select>
              </div>

              <div className="bg-gray-800 rounded-xl overflow-hidden">
                <div className="overflow-x-auto">
                  <table className="w-full text-sm">
                    <thead className="bg-gray-700">
                      <tr>
                        <th className="px-3 py-2 text-left text-xs font-medium text-gray-300">User ID</th>
                        <th className="px-3 py-2 text-left text-xs font-medium text-gray-300">Messages</th>
                        <th className="px-3 py-2 text-left text-xs font-medium text-gray-300">Storage</th>
                        <th className="px-3 py-2 text-left text-xs font-medium text-gray-300">Plan</th>
                        <th className="px-3 py-2 text-left text-xs font-medium text-gray-300">Expires</th>
                        <th className="px-3 py-2 text-left text-xs font-medium text-gray-300">Assigned By</th>
                        <th className="px-3 py-2 text-left text-xs font-medium text-gray-300">Last Active</th>
                        <th className="px-3 py-2 text-left text-xs font-medium text-gray-300">Status</th>
                        <th className="px-3 py-2 text-center text-xs font-medium text-gray-300">Actions</th>
                      </tr>
                    </thead>
                    <tbody>
                      {detailedUsers
                        .filter(user => {
                          const matchesSearch = user.userId.toLowerCase().includes(userSearchTerm.toLowerCase())
                          const matchesFilter = userFilter === 'all' || (userFilter === 'active' && user.isActive) || (userFilter === 'inactive' && !user.isActive)
                          return matchesSearch && matchesFilter
                        })
                        .map((user) => (
                          <tr key={user.userId} className="border-t border-gray-700 hover:bg-gray-700/50">
                            <td className="px-3 py-2 text-xs font-mono text-blue-400">{user.userId}</td>
                            <td className="px-3 py-2 text-xs">{user.messagesCount.toLocaleString()}</td>
                            <td className="px-3 py-2 text-xs">{user.storageUsedMB}MB</td>
                            <td className="px-3 py-2 text-xs">
                              <span className={`px-2 py-1 rounded text-xs font-medium ${
                                user.wasPremium && user.plan !== 'free'
                                  ? 'bg-purple-900 text-purple-300'
                                  : 'bg-gray-700 text-gray-300'
                              }`}>
                                {user.plan}
                              </span>
                            </td>
                            <td className="px-3 py-2 text-xs text-gray-300">
                              {user.planExpiresAt ? new Date(user.planExpiresAt).toLocaleDateString() : '-'}
                            </td>
                            <td className="px-3 py-2 text-xs text-gray-300">
                              {user.planAssignedBy || '-'}
                            </td>
                            <td className="px-3 py-2 text-xs text-gray-300">
                              {user.lastActivity ? new Date(user.lastActivity).toLocaleDateString() : 'Never'}
                            </td>
                            <td className="px-3 py-2"><span className={`px-2 py-1 rounded text-xs font-medium ${user.isActive ? 'bg-green-900 text-green-300' : 'bg-red-900 text-red-300'}`}>{user.isActive ? 'Active' : 'Inactive'}</span></td>
                            <td className="px-3 py-2 text-center">
                              <button onClick={() => handleDeleteUser(user.userId)} disabled={deletingUser === user.userId} className="px-2 py-1 bg-red-600 hover:bg-red-700 disabled:opacity-50 disabled:bg-gray-600 text-white text-xs font-medium rounded transition-colors">
                                {deletingUser === user.userId ? <Loader2 className="w-3 h-3 animate-spin inline" /> : <UserX className="w-3 h-3 inline" />}
                              </button>
                            </td>
                          </tr>
                        ))}
                    </tbody>
                  </table>
                </div>
                {detailedUsers.length === 0 && <div className="text-center py-8 text-gray-400">No users loaded. Click "Load Users" to fetch data.</div>}
              </div>
            </div>
          </div>
        )}

        {activeTab === 'data' && (
          <div className="space-y-6">
            {/* COST OPTIMIZATION: New cleanup options */}
            <div className="bg-gradient-to-r from-emerald-600 to-teal-600 rounded-xl p-6">
              <div className="flex items-center justify-between">
                <div>
                  <h3 className="text-xl font-bold mb-1">🆕 Detect Orphaned Data</h3>
                  <p className="text-emerald-100">Scan for orphaned user nodes and inactive accounts</p>
                  <p className="text-emerald-100 text-sm mt-2">💾 Finds empty nodes, abandoned users, and wasted storage</p>
                </div>
                <button onClick={handleDetectOrphans} disabled={isDetectingOrphans} className="flex items-center gap-2 px-6 py-3 bg-white text-emerald-600 font-semibold rounded-lg hover:bg-gray-100 disabled:opacity-50 disabled:bg-gray-400 transition-colors">
                  {isDetectingOrphans ? <Loader2 className="w-5 h-5 animate-spin" /> : <Search className="w-5 h-5" />}
                  {isDetectingOrphans ? 'Scanning...' : 'Detect Orphans'}
                </button>
              </div>
            </div>

            <div className="bg-gradient-to-r from-indigo-600 to-violet-600 rounded-xl p-6">
              <div className="flex items-center justify-between">
                <div>
                  <h3 className="text-xl font-bold mb-1">🆕 Smart Global Cleanup</h3>
                  <p className="text-indigo-100">Intelligent cleanup based on user plan tier</p>
                  <p className="text-indigo-100 text-sm mt-2">⚡ Deletes orphaned accounts & empties nodes (FREE: 14d, PAID: 60d)</p>
                </div>
                <button onClick={handleSmartGlobalCleanup} disabled={isRunningSmartCleanup} className="flex items-center gap-2 px-6 py-3 bg-white text-indigo-600 font-semibold rounded-lg hover:bg-gray-100 disabled:opacity-50 disabled:bg-gray-400 transition-colors">
                  {isRunningSmartCleanup ? <Loader2 className="w-5 h-5 animate-spin" /> : <Zap className="w-5 h-5" />}
                  {isRunningSmartCleanup ? 'Cleaning...' : 'Smart Cleanup'}
                </button>
              </div>
            </div>

            <div className="bg-gradient-to-r from-pink-600 to-rose-600 rounded-xl p-6">
              <div className="flex items-center justify-between">
                <div>
                  <h3 className="text-xl font-bold mb-1">🆕 Detect & Delete Duplicate Users</h3>
                  <p className="text-pink-100">Find accounts from same device (disconnect/reconnect) and remove</p>
                  <p className="text-pink-100 text-sm mt-2">🔄 Keeps newest account per device, deletes older ones</p>
                </div>
                <div className="flex gap-3">
                  <button onClick={handleDetectDuplicates} disabled={isDetectingDuplicates} className="flex items-center gap-2 px-6 py-3 bg-white text-pink-600 font-semibold rounded-lg hover:bg-gray-100 disabled:opacity-50 disabled:bg-gray-400 transition-colors">
                    {isDetectingDuplicates ? <Loader2 className="w-5 h-5 animate-spin" /> : <AlertTriangle className="w-5 h-5" />}
                    {isDetectingDuplicates ? 'Scanning...' : 'Detect'}
                  </button>
                  <button onClick={handleDeleteDuplicates} disabled={isDeletingDuplicates || duplicatesList.length === 0} className="flex items-center gap-2 px-6 py-3 bg-red-600 text-white font-semibold rounded-lg hover:bg-red-700 disabled:opacity-50 disabled:bg-gray-400 transition-colors">
                    {isDeletingDuplicates ? <Loader2 className="w-5 h-5 animate-spin" /> : <Trash2 className="w-5 h-5" />}
                    {isDeletingDuplicates ? 'Deleting...' : 'Delete'}
                  </button>
                </div>
              </div>
            </div>

            <div className="bg-gradient-to-r from-blue-600 to-purple-600 rounded-xl p-6">
              <div className="flex items-center justify-between">
                <div>
                  <h3 className="text-xl font-bold mb-1">Auto Cleanup with Email Reports</h3>
                  <p className="text-blue-100">Clean all orphan data with one click (12 categories)</p>
                  <p className="text-blue-100 text-sm mt-2">📧 Generates detailed cleanup report and logs to console</p>
                </div>
                <button onClick={handleAutoCleanup} disabled={isRunningAuto || !userId} className="flex items-center gap-2 px-6 py-3 bg-white text-blue-600 font-semibold rounded-lg hover:bg-gray-100 disabled:opacity-50 disabled:bg-gray-400 transition-colors">
                  {isRunningAuto ? <Loader2 className="w-5 h-5 animate-spin" /> : <Mail className="w-5 h-5" />}
                  {isRunningAuto ? 'Running...' : 'Run Auto Cleanup'}
                </button>
              </div>
            </div>

            <div className="bg-gradient-to-r from-red-600 to-orange-600 rounded-xl p-6">
              <div className="flex items-center justify-between">
                <div>
                  <h3 className="text-xl font-bold mb-1">Device Cleanup</h3>
                  <p className="text-red-100">Remove duplicate and old device entries</p>
                  <p className="text-red-100 text-sm mt-2">🗑️ Cleans devices not seen in 7+ days, keeps most recent per platform</p>
                </div>
                <button onClick={handleDeviceCleanup} disabled={isRunningDeviceCleanup} className="flex items-center gap-2 px-6 py-3 bg-white text-red-600 font-semibold rounded-lg hover:bg-gray-100 disabled:opacity-50 disabled:bg-gray-400 transition-colors">
                  {isRunningDeviceCleanup ? <Loader2 className="w-5 h-5 animate-spin" /> : <UserX className="w-5 h-5" />}
                  {isRunningDeviceCleanup ? 'Cleaning...' : 'Clean Old Devices'}
                </button>
              </div>
            </div>

            <div className="bg-gradient-to-r from-yellow-600 to-amber-600 rounded-xl p-6">
              <div className="flex items-center justify-between">
                <div>
                  <h3 className="text-xl font-bold mb-1">🗑️ Delete Users Without Devices</h3>
                  <p className="text-yellow-100">Remove orphaned accounts that cannot access any messages</p>
                  <p className="text-yellow-100 text-sm mt-2">💾 Saves ~$0.005/month per user deleted</p>
                </div>
                <button onClick={handleDeleteNoDeviceUsers} disabled={isDeletingNoDeviceUsers} className="flex items-center gap-2 px-6 py-3 bg-red-600 text-white font-semibold rounded-lg hover:bg-red-700 disabled:opacity-50 disabled:bg-gray-400 transition-colors">
                  {isDeletingNoDeviceUsers ? <Loader2 className="w-5 h-5 animate-spin" /> : <UserX className="w-5 h-5" />}
                  {isDeletingNoDeviceUsers ? 'Deleting...' : 'Delete Users'}
                </button>
              </div>
            </div>

            <div className="bg-gradient-to-r from-cyan-600 to-blue-600 rounded-xl p-6">
              <div className="flex items-center justify-between">
                <div>
                  <h3 className="text-xl font-bold mb-1">⏰ Delete Old Messages</h3>
                  <p className="text-cyan-100">Remove messages older than retention period</p>
                  <p className="text-cyan-100 text-sm mt-2">Free: 30 days | Paid: 90 days | 💾 ~500 bytes per message</p>
                </div>
                <button onClick={handleDeleteOldMessages} disabled={isDeletingOldMessages} className="flex items-center gap-2 px-6 py-3 bg-white text-cyan-600 font-semibold rounded-lg hover:bg-gray-100 disabled:opacity-50 disabled:bg-gray-400 transition-colors">
                  {isDeletingOldMessages ? <Loader2 className="w-5 h-5 animate-spin" /> : <Trash2 className="w-5 h-5" />}
                  {isDeletingOldMessages ? 'Deleting...' : 'Clean Old Messages'}
                </button>
              </div>
            </div>

            <div className="bg-gradient-to-r from-orange-600 to-amber-600 rounded-xl p-6">
              <div className="flex items-center justify-between">
                <div>
                  <h3 className="text-xl font-bold mb-1">📎 Delete Old MMS Messages</h3>
                  <p className="text-orange-100">Aggressively remove MMS messages and attachments</p>
                  <p className="text-orange-100 text-sm mt-2">Free: 7 days | Paid: 90 days | 💾 ~2MB per MMS attachment</p>
                </div>
                <button onClick={handleDeleteOldMms} disabled={isDeletingOldMms} className="flex items-center gap-2 px-6 py-3 bg-white text-orange-600 font-semibold rounded-lg hover:bg-gray-100 disabled:opacity-50 disabled:bg-gray-400 transition-colors">
                  {isDeletingOldMms ? <Loader2 className="w-5 h-5 animate-spin" /> : <Trash2 className="w-5 h-5" />}
                  {isDeletingOldMms ? 'Deleting...' : 'Clean Old MMS'}
                </button>
              </div>
            </div>

            <div className="bg-gradient-to-r from-green-600 to-emerald-600 rounded-xl p-6">
              <div className="flex items-center justify-between">
                <div>
                  <h3 className="text-xl font-bold mb-1">📱 Enforce SMS-Only for Free Tier</h3>
                  <p className="text-green-100">Remove non-SMS messages (calls, media) from free users</p>
                  <p className="text-green-100 text-sm mt-2">💾 95% storage reduction per free user | Paid users unaffected</p>
                </div>
                <button onClick={handleEnforceSmsFree} disabled={isEnforcingSms} className="flex items-center gap-2 px-6 py-3 bg-white text-green-600 font-semibold rounded-lg hover:bg-gray-100 disabled:opacity-50 disabled:bg-gray-400 transition-colors">
                  {isEnforcingSms ? <Loader2 className="w-5 h-5 animate-spin" /> : <MessageSquare className="w-5 h-5" />}
                  {isEnforcingSms ? 'Enforcing...' : 'Enforce SMS Only'}
                </button>
              </div>
            </div>

            <div className="bg-gray-800 rounded-xl p-6 flex-1 flex flex-col">
              <h3 className="text-lg font-semibold mb-4">Cleanup Activity Log</h3>
              <div ref={logContainerRef} className="flex-1 overflow-y-auto bg-gray-900 rounded-lg p-4 font-mono text-sm space-y-1">
                {cleanupLog.length === 0 ? (
                  <p className="text-gray-500">No cleanup activity yet. Run auto cleanup to see results.</p>
                ) : (
                  cleanupLog.map((log: string, index: number) => (
                    <div key={index} className="text-gray-300">{log}</div>
                  ))
                )}
              </div>
            </div>
          </div>
        )}

        {activeTab === 'costs' && (
          <div className="space-y-6">
            <div className="bg-gray-800 rounded-xl p-6">
              <div className="flex items-center justify-between mb-4">
                <h2 className="text-xl font-bold">Cost Optimization</h2>
                <button onClick={loadCostRecommendations} className="flex items-center gap-2 px-4 py-2 bg-green-600 hover:bg-green-700 rounded-lg transition-colors">
                  <TrendingUp className="w-4 h-4" />Analyze Costs
                </button>
              </div>

              {costRecommendations.length === 0 ? (
                <div className="text-center py-8 text-gray-400">Click "Analyze Costs" to get optimization recommendations.</div>
              ) : (
                <div className="space-y-4">
                  {costRecommendations.map((rec, index) => (
                    <div key={index} className="bg-gray-700 rounded-lg p-4">
                      <div className="flex items-start justify-between">
                        <div className="flex-1">
                          <div className="flex items-center gap-2 mb-2">
                            <span className={`px-2 py-1 rounded text-xs font-medium ${rec.priority === 'high' ? 'bg-red-900 text-red-300' : rec.priority === 'medium' ? 'bg-yellow-900 text-yellow-300' : 'bg-green-900 text-green-300'}`}>
                              {rec.priority.toUpperCase()}
                            </span>
                            <span className="text-sm text-gray-400 capitalize">{rec.type}</span>
                          </div>
                          <h4 className="font-medium mb-1">{rec.title}</h4>
                          <p className="text-sm text-gray-400 mb-2">{rec.description}</p>
                          <p className="text-sm text-green-400 font-medium">💰 Potential savings: ${rec.potentialSavings}/month</p>
                        </div>
                        <div className="text-right">
                          <button onClick={() => implementRecommendation(rec)} className="px-3 py-1 bg-blue-600 hover:bg-blue-700 rounded text-sm transition-colors">Implement</button>
                        </div>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </div>
        )}

        {activeTab === 'testing' && (
          <div className="space-y-6">
            <div className="bg-gradient-to-r from-purple-600 to-pink-600 rounded-xl p-6">
              <h3 className="text-xl font-bold mb-1">🧪 User Plan Assignment</h3>
              <p className="text-purple-100 mb-6">Assign plans to test free vs. paid functionality</p>

              <div className="bg-gray-800 rounded-lg p-6 space-y-4">
                <div>
                  <label className="block text-sm font-medium text-gray-300 mb-2">User ID</label>
                  <input
                    type="text"
                    value={testUserId}
                    onChange={(e) => setTestUserId(e.target.value)}
                    placeholder="Enter user Firebase ID (e.g., abc123def456)"
                    className="w-full px-4 py-2 bg-gray-700 border border-gray-600 rounded-lg text-white placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-purple-500"
                  />
                </div>

                <div className="grid grid-cols-2 gap-4">
                  <div>
                    <label className="block text-sm font-medium text-gray-300 mb-2">Plan</label>
                    <select
                      value={testPlan}
                      onChange={(e) => setTestPlan(e.target.value as any)}
                      className="w-full px-4 py-2 bg-gray-700 border border-gray-600 rounded-lg text-white focus:outline-none focus:ring-2 focus:ring-purple-500"
                    >
                      <option value="free">Free (7-day trial)</option>
                      <option value="monthly">Monthly Paid</option>
                      <option value="yearly">Yearly Paid</option>
                      <option value="lifetime">Lifetime</option>
                    </select>
                  </div>

                  <div>
                    <label className="block text-sm font-medium text-gray-300 mb-2">Days Valid</label>
                    <input
                      type="number"
                      value={testDaysValid}
                      onChange={(e) => setTestDaysValid(e.target.value)}
                      placeholder="7"
                      min="1"
                      max="365"
                      className="w-full px-4 py-2 bg-gray-700 border border-gray-600 rounded-lg text-white placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-purple-500"
                    />
                  </div>
                </div>

                <button
                  onClick={handleSetUserPlan}
                  disabled={isSettingPlan || !testUserId.trim()}
                  className="w-full flex items-center justify-center gap-2 px-6 py-3 bg-white text-purple-600 font-semibold rounded-lg hover:bg-gray-100 disabled:opacity-50 disabled:bg-gray-400 transition-colors"
                >
                  {isSettingPlan ? <Loader2 className="w-5 h-5 animate-spin" /> : <Zap className="w-5 h-5" />}
                  {isSettingPlan ? 'Assigning Plan...' : 'Assign Plan'}
                </button>

                <div className="bg-gray-700 rounded-lg p-4 mt-6">
                  <p className="text-sm text-gray-300 mb-2">
                    <strong>Quick Test Cases:</strong>
                  </p>
                  <ul className="text-sm text-gray-400 space-y-1 ml-4">
                    <li>✅ Expired Free User: Plan = "free", Days = 1</li>
                    <li>✅ Active Trial User: Plan = "free", Days = 7</li>
                    <li>✅ Monthly Subscriber: Plan = "monthly", Days = 30</li>
                    <li>✅ Lifetime User: Plan = "lifetime", Days = 365</li>
                  </ul>
                </div>
              </div>
            </div>

            <div className="bg-gray-800 rounded-xl p-6 flex-1 flex flex-col">
              <h3 className="text-lg font-semibold mb-4">Assignment Log</h3>
              <div className="flex-1 overflow-y-auto bg-gray-900 rounded-lg p-4 font-mono text-sm space-y-1">
                {cleanupLog.length === 0 ? (
                  <p className="text-gray-500">No plan assignments yet. Assign a plan above to see logs.</p>
                ) : (
                  cleanupLog.map((log: string, index: number) => (
                    <div key={index} className="text-gray-300">{log}</div>
                  ))
                )}
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  )
}