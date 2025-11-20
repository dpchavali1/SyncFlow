import { create } from 'zustand'

interface Message {
  id: string
  address: string
  body: string
  date: number
  type: number
  timestamp?: number
  contactName?: string
}

interface Device {
  id: string
  name: string
  type: string
  pairedAt: number
}

interface AppState {
  // Authentication
  userId: string | null
  isAuthenticated: boolean
  setUserId: (userId: string | null) => void

  // Messages
  messages: Message[]
  setMessages: (messages: Message[]) => void
  selectedConversation: string | null
  setSelectedConversation: (address: string | null) => void

  // Devices
  devices: Device[]
  setDevices: (devices: Device[]) => void

  // UI State
  isSidebarOpen: boolean
  toggleSidebar: () => void
  isPairing: boolean
  setIsPairing: (isPairing: boolean) => void

  // Notifications
  hasNewMessage: boolean
  setHasNewMessage: (hasNew: boolean) => void
}

export const useAppStore = create<AppState>((set) => ({
  // Authentication
  userId: null,
  isAuthenticated: false,
  setUserId: (userId) => set({ userId, isAuthenticated: !!userId }),

  // Messages
  messages: [],
  setMessages: (messages) => set({ messages }),
  selectedConversation: null,
  setSelectedConversation: (address) => set({ selectedConversation: address }),

  // Devices
  devices: [],
  setDevices: (devices) => set({ devices }),

  // UI State
  isSidebarOpen: true,
  toggleSidebar: () => set((state) => ({ isSidebarOpen: !state.isSidebarOpen })),
  isPairing: false,
  setIsPairing: (isPairing) => set({ isPairing }),

  // Notifications
  hasNewMessage: false,
  setHasNewMessage: (hasNew) => set({ hasNewMessage: hasNew }),
}))
