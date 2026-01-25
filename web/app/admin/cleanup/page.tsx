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
  const [totalDuplicateAccounts, setTotalDuplicateAccounts] = useState(0)
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

      // Calculate total duplicate accounts that can be deleted
      const totalDupes = duplicates.reduce((sum, d) => sum + (d.userIds.length - 1), 0)
      setTotalDuplicateAccounts(totalDupes)

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
        setTotalDuplicateAccounts(0)
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
                        <th className="px-3 py-2 text-left text-xs font-medium text-gray-300">Devices</th>
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
                              <span className="px-2 py-1 rounded text-xs font-semibold bg-blue-900/30 text-blue-300">
                                {user.devicesCount}
                              </span>
                            </td>
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
          <div className="space-y-8">
            {/* Executive Summary Header */}
            <div className="bg-gradient-to-br from-slate-900 via-slate-800 to-slate-900 rounded-2xl p-8 border border-slate-700 shadow-2xl">
              <div className="flex items-center justify-between mb-6">
                <div className="flex items-center gap-4">
                  <div className="p-3 bg-gradient-to-br from-blue-500 to-indigo-600 rounded-xl shadow-lg">
                    <Database className="w-8 h-8 text-white" />
                  </div>
                  <div>
                    <h2 className="text-2xl font-bold text-white mb-1">Data Management Center</h2>
                    <p className="text-slate-300">Professional cleanup and optimization tools</p>
                  </div>
                </div>
                <div className="flex items-center gap-3">
                  <div className="text-right">
                    <div className="text-2xl font-bold text-white">{cleanupLog.length}</div>
                    <div className="text-xs text-slate-400 uppercase tracking-wide">Operations Logged</div>
                  </div>
                  <div className="w-px h-12 bg-slate-600"></div>
                  <div className="text-right">
                    <div className="text-2xl font-bold text-green-400">Active</div>
                    <div className="text-xs text-slate-400 uppercase tracking-wide">System Status</div>
                  </div>
                </div>
              </div>

              {/* Quick Action Bar */}
              <div className="bg-slate-800/50 rounded-xl p-6 border border-slate-600">
                <h3 className="text-lg font-semibold text-white mb-4 flex items-center gap-2">
                  <Zap className="w-5 h-5 text-yellow-400" />
                  Quick Actions
                </h3>
                <div className="flex flex-wrap gap-4">
                  <button
                    onClick={handleAutoCleanup}
                    disabled={isRunningAuto || !userId}
                    className="flex items-center gap-3 px-6 py-4 bg-gradient-to-r from-blue-600 to-blue-700 hover:from-blue-700 hover:to-blue-800 text-white font-semibold rounded-xl shadow-lg transition-all duration-200 disabled:opacity-50 disabled:cursor-not-allowed transform hover:scale-105"
                  >
                    {isRunningAuto ? <Loader2 className="w-5 h-5 animate-spin" /> : <Zap className="w-5 h-5" />}
                    <div className="text-left">
                      <div className="text-sm font-bold">Auto Cleanup</div>
                      <div className="text-xs opacity-90">12 categories • Full report</div>
                    </div>
                  </button>

                  <button
                    onClick={handleSmartGlobalCleanup}
                    disabled={isRunningSmartCleanup}
                    className="flex items-center gap-3 px-6 py-4 bg-gradient-to-r from-emerald-600 to-emerald-700 hover:from-emerald-700 hover:to-emerald-800 text-white font-semibold rounded-xl shadow-lg transition-all duration-200 disabled:opacity-50 disabled:cursor-not-allowed transform hover:scale-105"
                  >
                    {isRunningSmartCleanup ? <Loader2 className="w-5 h-5 animate-spin" /> : <Activity className="w-5 h-5" />}
                    <div className="text-left">
                      <div className="text-sm font-bold">Smart Cleanup</div>
                      <div className="text-xs opacity-90">Plan-based • Intelligent</div>
                    </div>
                  </button>

                  <button
                    onClick={handleDetectOrphans}
                    disabled={isDetectingOrphans}
                    className="flex items-center gap-3 px-6 py-4 bg-gradient-to-r from-purple-600 to-purple-700 hover:from-purple-700 hover:to-purple-800 text-white font-semibold rounded-xl shadow-lg transition-all duration-200 disabled:opacity-50 disabled:cursor-not-allowed transform hover:scale-105"
                  >
                    {isDetectingOrphans ? <Loader2 className="w-5 h-5 animate-spin" /> : <Search className="w-5 h-5" />}
                    <div className="text-left">
                      <div className="text-sm font-bold">Scan Orphans</div>
                      <div className="text-xs opacity-90">Find wasted data</div>
                    </div>
                  </button>
                </div>
              </div>
            </div>

            {/* User Management Section */}
            <div className="bg-white rounded-2xl shadow-lg border border-gray-100 overflow-hidden">
              <div className="bg-gradient-to-r from-indigo-500 to-purple-600 p-6">
                <h3 className="text-xl font-bold text-white flex items-center gap-3">
                  <div className="p-2 bg-white/20 rounded-lg">
                    <Users className="w-6 h-6 text-white" />
                  </div>
                  User Account Management
                </h3>
                <p className="text-indigo-100 mt-2">Manage user accounts, duplicates, and orphaned data</p>
              </div>

              <div className="p-6">
                <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
                  {/* Duplicate Users Card */}
                  <div className="group bg-gradient-to-br from-red-50 to-pink-50 border border-red-200 rounded-xl p-6 hover:shadow-lg transition-all duration-200">
                    <div className="flex items-start justify-between mb-4">
                      <div className="p-3 bg-red-100 rounded-xl group-hover:bg-red-200 transition-colors">
                        <AlertTriangle className="w-6 h-6 text-red-600" />
                      </div>
                      <div className="text-right">
                        <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-red-100 text-red-800">
                          {totalDuplicateAccounts} to Delete
                        </span>
                      </div>
                    </div>

                    <h4 className="font-bold text-gray-900 mb-2">Duplicate Users</h4>
                    <p className="text-sm text-gray-600 mb-4 leading-relaxed">
                      Remove accounts created from device disconnect/reconnect cycles
                      {duplicatesList.length > 0 && (
                        <span className="block mt-1 text-xs text-gray-500">
                          Found on {duplicatesList.length} device{duplicatesList.length !== 1 ? 's' : ''}
                        </span>
                      )}
                    </p>

                    <div className="flex gap-2">
                      <button
                        onClick={handleDetectDuplicates}
                        disabled={isDetectingDuplicates}
                        className="flex-1 px-4 py-2 bg-red-600 text-white text-sm font-medium rounded-lg hover:bg-red-700 disabled:opacity-50 transition-colors"
                      >
                        {isDetectingDuplicates ? 'Scanning...' : 'Detect'}
                      </button>
                      <button
                        onClick={handleDeleteDuplicates}
                        disabled={isDeletingDuplicates || totalDuplicateAccounts === 0}
                        className="px-4 py-2 bg-red-700 text-white text-sm font-medium rounded-lg hover:bg-red-800 disabled:opacity-50 transition-colors"
                      >
                        {isDeletingDuplicates ? '...' : `Delete ${totalDuplicateAccounts}`}
                      </button>
                    </div>
                  </div>

                  {/* Orphaned Accounts Card */}
                  <div className="group bg-gradient-to-br from-orange-50 to-red-50 border border-orange-200 rounded-xl p-6 hover:shadow-lg transition-all duration-200">
                    <div className="flex items-start justify-between mb-4">
                      <div className="p-3 bg-orange-100 rounded-xl group-hover:bg-orange-200 transition-colors">
                        <UserX className="w-6 h-6 text-orange-600" />
                      </div>
                      <div className="text-right">
                        <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-orange-100 text-orange-800">
                          Critical
                        </span>
                      </div>
                    </div>

                    <h4 className="font-bold text-gray-900 mb-2">Orphaned Accounts</h4>
                    <p className="text-sm text-gray-600 mb-4 leading-relaxed">
                      Remove users without active devices who cannot access their messages
                    </p>

                    <div className="mb-3 p-3 bg-orange-100 rounded-lg">
                      <div className="flex items-center gap-2 text-xs text-orange-800 font-medium">
                        <DollarSign className="w-4 h-4" />
                        ~$0.005/month savings per user
                      </div>
                    </div>

                    <button
                      onClick={handleDeleteNoDeviceUsers}
                      disabled={isDeletingNoDeviceUsers}
                      className="w-full px-4 py-2 bg-orange-600 text-white text-sm font-medium rounded-lg hover:bg-orange-700 disabled:opacity-50 transition-colors"
                    >
                      {isDeletingNoDeviceUsers ? 'Deleting...' : 'Delete Orphaned Users'}
                    </button>
                  </div>

                  {/* Data Scanner Card */}
                  <div className="group bg-gradient-to-br from-emerald-50 to-teal-50 border border-emerald-200 rounded-xl p-6 hover:shadow-lg transition-all duration-200 md:col-span-2 lg:col-span-1">
                    <div className="flex items-start justify-between mb-4">
                      <div className="p-3 bg-emerald-100 rounded-xl group-hover:bg-emerald-200 transition-colors">
                        <Search className="w-6 h-6 text-emerald-600" />
                      </div>
                      <div className="text-right">
                        <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-emerald-100 text-emerald-800">
                          Analysis
                        </span>
                      </div>
                    </div>

                    <h4 className="font-bold text-gray-900 mb-2">Data Integrity Scanner</h4>
                    <p className="text-sm text-gray-600 mb-4 leading-relaxed">
                      Comprehensive scan for orphaned nodes, empty accounts, and data inconsistencies
                    </p>

                    <button
                      onClick={handleDetectOrphans}
                      disabled={isDetectingOrphans}
                      className="w-full px-4 py-2 bg-emerald-600 text-white text-sm font-medium rounded-lg hover:bg-emerald-700 disabled:opacity-50 transition-colors"
                    >
                      {isDetectingOrphans ? 'Scanning...' : 'Start Deep Scan'}
                    </button>
                  </div>
                </div>
              </div>
            </div>

            {/* Message Management Section */}
            <div className="bg-white rounded-2xl shadow-lg border border-gray-100 overflow-hidden">
              <div className="bg-gradient-to-r from-cyan-500 to-blue-600 p-6">
                <h3 className="text-xl font-bold text-white flex items-center gap-3">
                  <div className="p-2 bg-white/20 rounded-lg">
                    <MessageSquare className="w-6 h-6 text-white" />
                  </div>
                  Message & Media Management
                </h3>
                <p className="text-cyan-100 mt-2">Clean up old messages, media files, and enforce plan limits</p>
              </div>

              <div className="p-6">
                <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
                  {/* SMS Cleanup Card */}
                  <div className="group bg-gradient-to-br from-blue-50 to-cyan-50 border border-blue-200 rounded-xl p-6 hover:shadow-lg transition-all duration-200">
                    <div className="flex items-start justify-between mb-4">
                      <div className="p-3 bg-blue-100 rounded-xl group-hover:bg-blue-200 transition-colors">
                        <Mail className="w-6 h-6 text-blue-600" />
                      </div>
                      <div className="text-right">
                        <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-blue-100 text-blue-800">
                          SMS Only
                        </span>
                      </div>
                    </div>

                    <h4 className="font-bold text-gray-900 mb-2">SMS Message Cleanup</h4>
                    <p className="text-sm text-gray-600 mb-4 leading-relaxed">
                      Remove SMS messages older than retention periods based on user plans
                    </p>

                    <div className="mb-3 space-y-2">
                      <div className="flex justify-between text-xs">
                        <span className="text-gray-600">Free Tier:</span>
                        <span className="font-medium text-gray-900">30 days</span>
                      </div>
                      <div className="flex justify-between text-xs">
                        <span className="text-gray-600">Paid Plans:</span>
                        <span className="font-medium text-gray-900">90 days</span>
                      </div>
                      <div className="flex justify-between text-xs">
                        <span className="text-gray-600">Storage per message:</span>
                        <span className="font-medium text-gray-900">~500 bytes</span>
                      </div>
                    </div>

                    <button
                      onClick={handleDeleteOldMessages}
                      disabled={isDeletingOldMessages}
                      className="w-full px-4 py-2 bg-blue-600 text-white text-sm font-medium rounded-lg hover:bg-blue-700 disabled:opacity-50 transition-colors"
                    >
                      {isDeletingOldMessages ? 'Cleaning...' : 'Clean Old SMS'}
                    </button>
                  </div>

                  {/* MMS Cleanup Card */}
                  <div className="group bg-gradient-to-br from-orange-50 to-amber-50 border border-orange-200 rounded-xl p-6 hover:shadow-lg transition-all duration-200">
                    <div className="flex items-start justify-between mb-4">
                      <div className="p-3 bg-orange-100 rounded-xl group-hover:bg-orange-200 transition-colors">
                        <HardDrive className="w-6 h-6 text-orange-600" />
                      </div>
                      <div className="text-right">
                        <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-orange-100 text-orange-800">
                          High Impact
                        </span>
                      </div>
                    </div>

                    <h4 className="font-bold text-gray-900 mb-2">MMS & Media Cleanup</h4>
                    <p className="text-sm text-gray-600 mb-4 leading-relaxed">
                      Aggressively remove MMS messages and large media attachments
                    </p>

                    <div className="mb-3 space-y-2">
                      <div className="flex justify-between text-xs">
                        <span className="text-gray-600">Free Tier:</span>
                        <span className="font-medium text-gray-900">7 days</span>
                      </div>
                      <div className="flex justify-between text-xs">
                        <span className="text-gray-600">Paid Plans:</span>
                        <span className="font-medium text-gray-900">90 days</span>
                      </div>
                      <div className="flex justify-between text-xs">
                        <span className="text-gray-600">Avg. size per MMS:</span>
                        <span className="font-medium text-gray-900">~2MB</span>
                      </div>
                    </div>

                    <button
                      onClick={handleDeleteOldMms}
                      disabled={isDeletingOldMms}
                      className="w-full px-4 py-2 bg-orange-600 text-white text-sm font-medium rounded-lg hover:bg-orange-700 disabled:opacity-50 transition-colors"
                    >
                      {isDeletingOldMms ? 'Cleaning...' : 'Clean MMS & Media'}
                    </button>
                  </div>

                  {/* Plan Enforcement Card */}
                  <div className="group bg-gradient-to-br from-green-50 to-emerald-50 border border-green-200 rounded-xl p-6 hover:shadow-lg transition-all duration-200">
                    <div className="flex items-start justify-between mb-4">
                      <div className="p-3 bg-green-100 rounded-xl group-hover:bg-green-200 transition-colors">
                        <Shield className="w-6 h-6 text-green-600" />
                      </div>
                      <div className="text-right">
                        <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-green-100 text-green-800">
                          Policy
                        </span>
                      </div>
                    </div>

                    <h4 className="font-bold text-gray-900 mb-2">Free Tier Enforcement</h4>
                    <p className="text-sm text-gray-600 mb-4 leading-relaxed">
                      Remove calls and media from free users, keeping SMS only for compliance
                    </p>

                    <div className="mb-3 p-3 bg-green-100 rounded-lg">
                      <div className="flex items-center gap-2 text-xs text-green-800 font-medium">
                        <TrendingUp className="w-4 h-4" />
                        95% storage reduction per free user
                      </div>
                    </div>

                    <button
                      onClick={handleEnforceSmsFree}
                      disabled={isEnforcingSms}
                      className="w-full px-4 py-2 bg-green-600 text-white text-sm font-medium rounded-lg hover:bg-green-700 disabled:opacity-50 transition-colors"
                    >
                      {isEnforcingSms ? 'Enforcing...' : 'Enforce SMS Only'}
                    </button>
                  </div>
                </div>
              </div>
            </div>

            {/* Device Management Section */}
            <div className="bg-white rounded-2xl shadow-lg border border-gray-100 overflow-hidden">
              <div className="bg-gradient-to-r from-slate-600 to-slate-700 p-6">
                <h3 className="text-xl font-bold text-white flex items-center gap-3">
                  <div className="p-2 bg-white/20 rounded-lg">
                    <UserX className="w-6 h-6 text-white" />
                  </div>
                  Device & Session Management
                </h3>
                <p className="text-slate-200 mt-2">Clean up device registrations and session data</p>
              </div>

              <div className="p-6">
                <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
                  <div className="group bg-gradient-to-br from-slate-50 to-gray-50 border border-slate-200 rounded-xl p-6 hover:shadow-lg transition-all duration-200">
                    <div className="flex items-start justify-between mb-4">
                      <div className="p-3 bg-slate-100 rounded-xl group-hover:bg-slate-200 transition-colors">
                        <UserX className="w-6 h-6 text-slate-600" />
                      </div>
                      <div className="text-right">
                        <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-slate-100 text-slate-800">
                          Maintenance
                        </span>
                      </div>
                    </div>

                    <h4 className="font-bold text-gray-900 mb-2">Device Registry Cleanup</h4>
                    <p className="text-sm text-gray-600 mb-4 leading-relaxed">
                      Remove duplicate and inactive device entries from the registry
                    </p>

                    <div className="mb-3 p-3 bg-slate-100 rounded-lg">
                      <div className="flex items-center gap-2 text-xs text-slate-800 font-medium">
                        <RefreshCw className="w-4 h-4" />
                        Cleans devices inactive for 7+ days
                      </div>
                    </div>

                    <button
                      onClick={handleDeviceCleanup}
                      disabled={isRunningDeviceCleanup}
                      className="w-full px-4 py-2 bg-slate-600 text-white text-sm font-medium rounded-lg hover:bg-slate-700 disabled:opacity-50 transition-colors"
                    >
                      {isRunningDeviceCleanup ? 'Cleaning...' : 'Clean Device Registry'}
                    </button>
                  </div>
                </div>
              </div>
            </div>

            {/* Activity Monitor */}
            <div className="bg-slate-900 rounded-2xl shadow-2xl border border-slate-700 overflow-hidden">
              <div className="bg-gradient-to-r from-slate-800 to-slate-900 p-6 border-b border-slate-700">
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-3">
                    <div className="p-2 bg-slate-700 rounded-lg">
                      <Activity className="w-5 h-5 text-slate-300" />
                    </div>
                    <div>
                      <h3 className="text-lg font-bold text-white">Cleanup Activity Monitor</h3>
                      <p className="text-slate-400 text-sm">Real-time operation logs and system status</p>
                    </div>
                  </div>
                  <div className="flex items-center gap-3">
                    <div className="flex items-center gap-2">
                      <div className="w-2 h-2 bg-green-400 rounded-full animate-pulse"></div>
                      <span className="text-xs text-slate-400 uppercase tracking-wide">Live</span>
                    </div>
                    <button
                      onClick={() => setCleanupLog([])}
                      className="px-4 py-2 bg-slate-700 hover:bg-slate-600 text-slate-300 text-sm font-medium rounded-lg transition-colors"
                    >
                      Clear Logs
                    </button>
                  </div>
                </div>
              </div>

              <div className="p-6">
                <div ref={logContainerRef} className="bg-slate-800 rounded-xl p-4 font-mono text-sm max-h-80 overflow-y-auto border border-slate-600">
                  {cleanupLog.length === 0 ? (
                    <div className="text-center py-8">
                      <Activity className="w-12 h-12 text-slate-600 mx-auto mb-3" />
                      <p className="text-slate-500">No cleanup activity yet.</p>
                      <p className="text-slate-600 text-sm">Run operations above to see live activity logs.</p>
                    </div>
                  ) : (
                    <div className="space-y-2">
                      {cleanupLog.slice(-30).map((log: string, index: number) => {
                        const isError = log.toLowerCase().includes('error') || log.toLowerCase().includes('failed')
                        const isSuccess = log.toLowerCase().includes('completed') || log.toLowerCase().includes('success')
                        const isWarning = log.toLowerCase().includes('warning') || log.toLowerCase().includes('orphan')

                        return (
                          <div
                            key={index}
                            className={`p-3 rounded-lg border-l-4 ${
                              isError ? 'bg-red-900/20 border-red-500 text-red-300' :
                              isSuccess ? 'bg-green-900/20 border-green-500 text-green-300' :
                              isWarning ? 'bg-yellow-900/20 border-yellow-500 text-yellow-300' :
                              'bg-slate-700/50 border-slate-500 text-slate-300'
                            }`}
                          >
                            <div className="flex items-start gap-3">
                              <div className={`w-2 h-2 rounded-full mt-2 ${
                                isError ? 'bg-red-400' :
                                isSuccess ? 'bg-green-400' :
                                isWarning ? 'bg-yellow-400' :
                                'bg-slate-400'
                              }`}></div>
                              <div className="flex-1 font-medium text-xs leading-relaxed">{log}</div>
                            </div>
                          </div>
                        )
                      })}
                    </div>
                  )}
                </div>
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