# Admin Dashboard: Sync Groups Management

## Overview

Add admin pages to manage sync groups, view device information, and handle plan upgrades.

## File: web/app/admin/sync-groups/page.tsx

```typescript
'use client'

import React, { useEffect, useState } from 'react'
import { httpsCallable, getFunctions } from 'firebase/functions'
import Link from 'next/link'

interface SyncGroup {
  syncGroupId: string
  plan: string
  deviceCount: number
  deviceLimit: number
  createdAt: number
  masterDevice: string
}

export default function SyncGroupsPage() {
  const [groups, setGroups] = useState<SyncGroup[]>([])
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState('')
  const [filter, setFilter] = useState<'all' | 'free' | 'premium'>('all')

  useEffect(() => {
    loadSyncGroups()
  }, [])

  const loadSyncGroups = async () => {
    try {
      setIsLoading(true)
      const functions = getFunctions()
      const listGroups = httpsCallable(functions, 'listSyncGroups')
      const result = await listGroups({})
      const data = result.data as { success: boolean; groups: SyncGroup[] }

      if (data.success) {
        setGroups(data.groups)
      } else {
        setError('Failed to load sync groups')
      }
    } catch (err) {
      console.error('Error loading sync groups:', err)
      setError('An error occurred while loading sync groups')
    } finally {
      setIsLoading(false)
    }
  }

  const filteredGroups = groups.filter((g) => {
    if (filter === 'free') return g.plan === 'free'
    if (filter === 'premium') return g.plan !== 'free'
    return true
  })

  const premiumCount = groups.filter((g) => g.plan !== 'free').length
  const freeCount = groups.filter((g) => g.plan === 'free').length
  const totalDevices = groups.reduce((sum, g) => sum + g.deviceCount, 0)

  return (
    <div className="space-y-8">
      {/* Header */}
      <div className="flex justify-between items-center">
        <h1 className="text-3xl font-bold">Sync Groups</h1>
        <button
          onClick={loadSyncGroups}
          className="px-4 py-2 bg-blue-500 text-white rounded hover:bg-blue-600"
          disabled={isLoading}
        >
          {isLoading ? 'Loading...' : 'Refresh'}
        </button>
      </div>

      {/* Stats */}
      <div className="grid grid-cols-4 gap-4">
        <StatCard label="Total Groups" value={groups.length.toString()} />
        <StatCard label="Free Tier" value={freeCount.toString()} color="gray" />
        <StatCard label="Premium Tier" value={premiumCount.toString()} color="green" />
        <StatCard label="Total Devices" value={totalDevices.toString()} color="blue" />
      </div>

      {/* Filters */}
      <div className="flex gap-2">
        {(['all', 'free', 'premium'] as const).map((f) => (
          <button
            key={f}
            onClick={() => setFilter(f)}
            className={`px-4 py-2 rounded capitalize ${
              filter === f
                ? 'bg-blue-500 text-white'
                : 'bg-gray-200 text-gray-800 hover:bg-gray-300'
            }`}
          >
            {f}
          </button>
        ))}
      </div>

      {/* Error */}
      {error && (
        <div className="p-4 bg-red-50 border border-red-200 rounded text-red-800">
          {error}
        </div>
      )}

      {/* Groups Table */}
      <div className="overflow-x-auto border rounded-lg">
        <table className="w-full">
          <thead className="bg-gray-100 border-b">
            <tr>
              <th className="px-4 py-3 text-left text-sm font-semibold">Sync Group ID</th>
              <th className="px-4 py-3 text-left text-sm font-semibold">Plan</th>
              <th className="px-4 py-3 text-left text-sm font-semibold">Devices</th>
              <th className="px-4 py-3 text-left text-sm font-semibold">Created</th>
              <th className="px-4 py-3 text-left text-sm font-semibold">Actions</th>
            </tr>
          </thead>
          <tbody>
            {filteredGroups.map((group) => (
              <tr key={group.syncGroupId} className="border-b hover:bg-gray-50">
                <td className="px-4 py-3 text-sm font-mono">{group.syncGroupId.slice(0, 20)}...</td>
                <td className="px-4 py-3 text-sm">
                  <span
                    className={`px-2 py-1 rounded text-xs font-semibold ${
                      group.plan === 'free'
                        ? 'bg-gray-100 text-gray-800'
                        : 'bg-green-100 text-green-800'
                    }`}
                  >
                    {group.plan}
                  </span>
                </td>
                <td className="px-4 py-3 text-sm">
                  {group.deviceCount}/{group.deviceLimit}
                </td>
                <td className="px-4 py-3 text-sm">
                  {new Date(group.createdAt).toLocaleDateString()}
                </td>
                <td className="px-4 py-3 text-sm space-x-2">
                  <Link
                    href={`/admin/sync-groups/${group.syncGroupId}`}
                    className="text-blue-500 hover:underline"
                  >
                    View
                  </Link>
                  {group.plan === 'free' && (
                    <button
                      onClick={() => handleUpgrade(group.syncGroupId)}
                      className="text-green-500 hover:underline"
                    >
                      Upgrade
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {filteredGroups.length === 0 && !isLoading && (
        <div className="text-center py-8 text-gray-500">
          No sync groups found
        </div>
      )}
    </div>
  )

  async function handleUpgrade(syncGroupId: string) {
    try {
      const functions = getFunctions()
      const updatePlan = httpsCallable(functions, 'updateSyncGroupPlan')
      const result = await updatePlan({ syncGroupId, plan: 'monthly' })

      const data = result.data as { success: boolean }
      if (data.success) {
        loadSyncGroups()
        alert('Upgraded to monthly plan')
      } else {
        alert('Failed to upgrade')
      }
    } catch (err) {
      console.error('Upgrade error:', err)
      alert('Error upgrading plan')
    }
  }
}

function StatCard({
  label,
  value,
  color = 'blue'
}: {
  label: string
  value: string
  color?: string
}) {
  const bgColor = {
    blue: 'bg-blue-50',
    green: 'bg-green-50',
    gray: 'bg-gray-50'
  }[color]

  const textColor = {
    blue: 'text-blue-600',
    green: 'text-green-600',
    gray: 'text-gray-600'
  }[color]

  return (
    <div className={`${bgColor} p-4 rounded-lg`}>
      <p className="text-sm text-gray-600">{label}</p>
      <p className={`text-2xl font-bold ${textColor}`}>{value}</p>
    </div>
  )
}
```

## File: web/app/admin/sync-groups/[groupId]/page.tsx

```typescript
'use client'

import React, { useEffect, useState } from 'react'
import { httpsCallable, getFunctions } from 'firebase/functions'
import { useParams, useRouter } from 'next/navigation'

interface DeviceInfo {
  deviceId: string
  deviceType: string
  joinedAt: number
  lastSyncedAt?: number
  status: string
  deviceName?: string
}

interface HistoryEntry {
  timestamp: number
  action: string
  deviceId?: string
  newPlan?: string
  previousPlan?: string
}

interface GroupDetails {
  syncGroupId: string
  plan: string
  deviceLimit: number
  deviceCount: number
  createdAt: number
  masterDevice: string
  wasPremium: boolean
  firstPremiumDate?: number
  devices: DeviceInfo[]
  history: HistoryEntry[]
}

export default function GroupDetailsPage() {
  const params = useParams()
  const router = useRouter()
  const syncGroupId = params.groupId as string

  const [group, setGroup] = useState<GroupDetails | null>(null)
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState('')
  const [selectedPlan, setSelectedPlan] = useState('monthly')

  useEffect(() => {
    loadGroupDetails()
  }, [])

  const loadGroupDetails = async () => {
    try {
      setIsLoading(true)
      const functions = getFunctions()
      const getDetails = httpsCallable(functions, 'getSyncGroupDetails')
      const result = await getDetails({ syncGroupId })
      const data = result.data as { success: boolean; data: GroupDetails }

      if (data.success) {
        setGroup(data.data)
      } else {
        setError('Failed to load group details')
      }
    } catch (err) {
      console.error('Error loading group details:', err)
      setError('An error occurred while loading group details')
    } finally {
      setIsLoading(false)
    }
  }

  const handleUpgradePlan = async () => {
    if (!group) return

    try {
      const functions = getFunctions()
      const updatePlan = httpsCallable(functions, 'updateSyncGroupPlan')
      const result = await updatePlan({ syncGroupId, plan: selectedPlan })
      const data = result.data as { success: boolean }

      if (data.success) {
        loadGroupDetails()
        alert(`Upgraded to ${selectedPlan} plan`)
      } else {
        alert('Failed to upgrade')
      }
    } catch (err) {
      console.error('Upgrade error:', err)
      alert('Error upgrading plan')
    }
  }

  const handleRemoveDevice = async (deviceId: string) => {
    if (!confirm('Remove this device from the group?')) return

    try {
      const functions = getFunctions()
      const removeDevice = httpsCallable(functions, 'removeDeviceFromSyncGroup')
      const result = await removeDevice({ syncGroupId, deviceId })
      const data = result.data as { success: boolean }

      if (data.success) {
        loadGroupDetails()
        alert('Device removed')
      } else {
        alert('Failed to remove device')
      }
    } catch (err) {
      console.error('Remove error:', err)
      alert('Error removing device')
    }
  }

  if (isLoading) {
    return <div>Loading...</div>
  }

  if (!group) {
    return <div>Group not found</div>
  }

  return (
    <div className="space-y-8">
      {/* Header */}
      <div className="flex items-center gap-4">
        <button
          onClick={() => router.back()}
          className="text-blue-500 hover:underline"
        >
          ← Back
        </button>
        <h1 className="text-3xl font-bold">Sync Group Details</h1>
      </div>

      {/* Group Info */}
      <div className="bg-white p-6 rounded-lg border">
        <div className="grid grid-cols-2 gap-4 mb-4">
          <div>
            <p className="text-sm text-gray-600">Sync Group ID</p>
            <p className="font-mono text-sm">{group.syncGroupId}</p>
          </div>
          <div>
            <p className="text-sm text-gray-600">Created</p>
            <p>{new Date(group.createdAt).toLocaleString()}</p>
          </div>
          <div>
            <p className="text-sm text-gray-600">Plan</p>
            <span
              className={`px-2 py-1 rounded text-xs font-semibold ${
                group.plan === 'free'
                  ? 'bg-gray-100 text-gray-800'
                  : 'bg-green-100 text-green-800'
              }`}
            >
              {group.plan}
            </span>
          </div>
          <div>
            <p className="text-sm text-gray-600">Devices</p>
            <p>{group.deviceCount}/{group.deviceLimit}</p>
          </div>
        </div>

        {group.plan === 'free' && (
          <div className="flex gap-2 items-end">
            <div>
              <label className="text-sm text-gray-600">Upgrade to:</label>
              <select
                value={selectedPlan}
                onChange={(e) => setSelectedPlan(e.target.value)}
                className="mt-1 px-2 py-1 border rounded"
              >
                <option value="monthly">Monthly ($3.99/mo)</option>
                <option value="yearly">Yearly ($29.99/yr)</option>
                <option value="lifetime">Lifetime ($99.99)</option>
              </select>
            </div>
            <button
              onClick={handleUpgradePlan}
              className="px-4 py-2 bg-green-500 text-white rounded hover:bg-green-600"
            >
              Upgrade
            </button>
          </div>
        )}
      </div>

      {/* Devices */}
      <div className="bg-white p-6 rounded-lg border">
        <h2 className="text-xl font-bold mb-4">Devices ({group.deviceCount})</h2>
        <div className="overflow-x-auto">
          <table className="w-full">
            <thead className="bg-gray-100">
              <tr>
                <th className="px-4 py-2 text-left text-sm font-semibold">Device ID</th>
                <th className="px-4 py-2 text-left text-sm font-semibold">Type</th>
                <th className="px-4 py-2 text-left text-sm font-semibold">Joined</th>
                <th className="px-4 py-2 text-left text-sm font-semibold">Last Synced</th>
                <th className="px-4 py-2 text-left text-sm font-semibold">Status</th>
                <th className="px-4 py-2 text-left text-sm font-semibold">Action</th>
              </tr>
            </thead>
            <tbody>
              {group.devices.map((device) => (
                <tr key={device.deviceId} className="border-b">
                  <td className="px-4 py-2 text-sm font-mono">{device.deviceId.slice(0, 16)}...</td>
                  <td className="px-4 py-2 text-sm capitalize">{device.deviceType}</td>
                  <td className="px-4 py-2 text-sm">{new Date(device.joinedAt).toLocaleDateString()}</td>
                  <td className="px-4 py-2 text-sm">
                    {device.lastSyncedAt ? new Date(device.lastSyncedAt).toLocaleString() : 'Never'}
                  </td>
                  <td className="px-4 py-2 text-sm">
                    <span
                      className={`px-2 py-1 rounded text-xs font-semibold ${
                        device.status === 'active'
                          ? 'bg-green-100 text-green-800'
                          : 'bg-gray-100 text-gray-800'
                      }`}
                    >
                      {device.status}
                    </span>
                  </td>
                  <td className="px-4 py-2 text-sm">
                    <button
                      onClick={() => handleRemoveDevice(device.deviceId)}
                      className="text-red-500 hover:underline"
                    >
                      Remove
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      {/* History */}
      <div className="bg-white p-6 rounded-lg border">
        <h2 className="text-xl font-bold mb-4">History</h2>
        <div className="space-y-2 max-h-96 overflow-y-auto">
          {group.history.map((entry, idx) => (
            <div key={idx} className="p-2 bg-gray-50 rounded text-sm">
              <p className="font-semibold capitalize">{entry.action.replace(/_/g, ' ')}</p>
              <p className="text-xs text-gray-600">
                {new Date(entry.timestamp).toLocaleString()}
              </p>
              {entry.newPlan && (
                <p className="text-xs">
                  {entry.previousPlan} → {entry.newPlan}
                </p>
              )}
            </div>
          ))}
        </div>
      </div>
    </div>
  )
}
```

## Files to Create

1. `web/app/admin/sync-groups/page.tsx` - Sync groups list
2. `web/app/admin/sync-groups/[groupId]/page.tsx` - Group details

## Features

- ✅ List all sync groups with filtering
- ✅ View group details and device list
- ✅ Remove devices from group
- ✅ Upgrade plan with one click
- ✅ View complete change history
- ✅ Device status tracking
- ✅ Statistics dashboard

## Next Steps

1. Create the two pages above
2. Add navigation link in admin menu
3. Test list page with real data
4. Test upgrade functionality
5. Test device removal
