'use client'

import React, { useEffect, useState } from 'react'
import { httpsCallable, getFunctions } from 'firebase/functions'
import { useRouter } from 'next/navigation'
import Link from 'next/link'
import { ArrowLeft, RefreshCw } from 'lucide-react'

interface User {
  userId: string
  deviceCount: number
  syncGroupId: string | null
  subscription: string
  deviceLimit: number
  createdAt: number | null
  lastActive: number | null
}

interface AdminUser {
  users: User[]
  totalUsers: number
  totalDevices: number
}

export default function UsersPage() {
  const router = useRouter()
  const [data, setData] = useState<AdminUser | null>(null)
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState('')
  const [filter, setFilter] = useState<'all' | 'free' | 'premium'>('all')

  useEffect(() => {
    loadUsers()
  }, [])

  const loadUsers = async () => {
    try {
      setIsLoading(true)
      const functions = getFunctions()
      const listUsers = httpsCallable(functions, 'listUsers')
      const result = await listUsers({})
      const userData = result.data as AdminUser

      if (userData.users) {
        setData(userData)
      } else {
        setError('Failed to load users')
      }
    } catch (err) {
      console.error('Error loading users:', err)
      setError('An error occurred while loading users')
    } finally {
      setIsLoading(false)
    }
  }

  if (!data && !isLoading) {
    return (
      <div className="space-y-8">
        <div className="flex justify-between items-center">
          <h1 className="text-3xl font-bold">Users</h1>
          <Link
            href="/admin"
            className="flex items-center gap-2 px-4 py-2 text-gray-600 hover:text-gray-900"
          >
            <ArrowLeft className="w-4 h-4" />
            Back to Admin
          </Link>
        </div>
        <div className="p-4 bg-red-50 border border-red-200 rounded text-red-800">
          {error || 'Failed to load data'}
        </div>
      </div>
    )
  }

  const filteredUsers = data
    ? data.users.filter((u) => {
        if (filter === 'free') return u.subscription === 'free'
        if (filter === 'premium') return u.subscription !== 'free'
        return true
      })
    : []

  const freeCount = data ? data.users.filter((u) => u.subscription === 'free').length : 0
  const premiumCount = data ? data.users.filter((u) => u.subscription !== 'free').length : 0

  return (
    <div className="space-y-8 bg-white rounded-lg p-8">
      {/* Header */}
      <div className="flex justify-between items-center">
        <h1 className="text-3xl font-bold">Users</h1>
        <div className="flex gap-3">
          <button
            onClick={loadUsers}
            className="flex items-center gap-2 px-4 py-2 bg-blue-500 text-white rounded hover:bg-blue-600 disabled:bg-blue-300"
            disabled={isLoading}
          >
            <RefreshCw className={`w-4 h-4 ${isLoading ? 'animate-spin' : ''}`} />
            {isLoading ? 'Loading...' : 'Refresh'}
          </button>
          <Link
            href="/admin"
            className="flex items-center gap-2 px-4 py-2 text-gray-600 hover:text-gray-900 border border-gray-300 rounded hover:bg-gray-50"
          >
            <ArrowLeft className="w-4 h-4" />
            Back
          </Link>
        </div>
      </div>

      {/* Stats */}
      {data && (
        <div className="grid grid-cols-4 gap-4">
          <StatCard label="Total Users" value={data.totalUsers.toString()} />
          <StatCard label="Free Tier" value={freeCount.toString()} color="gray" />
          <StatCard label="Premium Tier" value={premiumCount.toString()} color="green" />
          <StatCard label="Total Devices" value={data.totalDevices.toString()} color="blue" />
        </div>
      )}

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
        <div className="p-4 bg-red-50 border border-red-200 rounded text-red-800">{error}</div>
      )}

      {/* Users Table */}
      <div className="overflow-x-auto border rounded-lg">
        <table className="w-full">
          <thead className="bg-gray-100 border-b">
            <tr>
              <th className="px-4 py-3 text-left text-sm font-semibold">User ID</th>
              <th className="px-4 py-3 text-left text-sm font-semibold">Devices</th>
              <th className="px-4 py-3 text-left text-sm font-semibold">Subscription</th>
              <th className="px-4 py-3 text-left text-sm font-semibold">Sync Group</th>
              <th className="px-4 py-3 text-left text-sm font-semibold">Created</th>
              <th className="px-4 py-3 text-left text-sm font-semibold">Last Active</th>
            </tr>
          </thead>
          <tbody>
            {filteredUsers.map((user) => (
              <tr key={user.userId} className="border-b hover:bg-gray-50">
                <td className="px-4 py-3 text-sm font-mono">
                  <span className="bg-gray-100 px-2 py-1 rounded text-xs">{user.userId.slice(0, 12)}...</span>
                </td>
                <td className="px-4 py-3 text-sm">
                  <span
                    className={`px-2 py-1 rounded text-xs font-semibold ${
                      user.deviceCount >= user.deviceLimit
                        ? 'bg-red-100 text-red-800'
                        : 'bg-blue-100 text-blue-800'
                    }`}
                  >
                    {user.deviceCount}/{user.deviceLimit}
                  </span>
                </td>
                <td className="px-4 py-3 text-sm">
                  <span
                    className={`px-2 py-1 rounded text-xs font-semibold ${
                      user.subscription === 'free'
                        ? 'bg-gray-100 text-gray-800'
                        : 'bg-green-100 text-green-800'
                    }`}
                  >
                    {user.subscription}
                  </span>
                </td>
                <td className="px-4 py-3 text-sm font-mono text-xs">
                  {user.syncGroupId ? (
                    <Link
                      href={`/admin/sync-groups/${user.syncGroupId}`}
                      className="text-blue-500 hover:underline"
                    >
                      {user.syncGroupId.slice(0, 16)}...
                    </Link>
                  ) : (
                    <span className="text-gray-400">-</span>
                  )}
                </td>
                <td className="px-4 py-3 text-sm">
                  {user.createdAt ? new Date(user.createdAt).toLocaleDateString() : '-'}
                </td>
                <td className="px-4 py-3 text-sm">
                  {user.lastActive ? new Date(user.lastActive).toLocaleDateString() : '-'}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {filteredUsers.length === 0 && !isLoading && (
        <div className="text-center py-8 text-gray-500">No users found</div>
      )}
    </div>
  )
}

function StatCard(
  {
    label,
    value,
    color = 'blue'
  }: {
    label: string
    value: string
    color?: string
  }
) {
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
