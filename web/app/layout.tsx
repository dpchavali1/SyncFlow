import type { Metadata, Viewport } from 'next'
import { Inter } from 'next/font/google'
import Script from 'next/script'
import { ServiceWorkerRegistration } from '../components/ServiceWorkerRegistration'
import { PerformanceProvider, PerformanceMonitor } from '../components/PerformanceComponents'
import SupportChat from '../components/SupportChat'
import './globals.css'

const inter = Inter({ subsets: ['latin'] })

export const metadata: Metadata = {
  title: 'SyncFlow - Desktop SMS Integration',
  description: 'Access your phone messages from your desktop',
  manifest: '/manifest.json',
  appleWebApp: {
    capable: true,
    statusBarStyle: 'default',
    title: 'SyncFlow',
  },
}

export const viewport: Viewport = {
  themeColor: '#0ea5e9',
  width: 'device-width',
  initialScale: 1,
  maximumScale: 1,
}

export default function RootLayout({
  children,
}: {
  children: React.ReactNode
}) {
  return (
    <html lang="en">
      <head>
        <link rel="icon" href="/favicon.ico" />
        <link rel="apple-touch-icon" href="/icon-192.png" />
      </head>
      <body className={inter.className}>
        <PerformanceProvider>
          <ServiceWorkerRegistration />
          {children}
          <PerformanceMonitor />
        </PerformanceProvider>
        <SupportChat />
        <Script
          async
          src="https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js?client=ca-pub-4962910048695842"
          crossOrigin="anonymous"
          strategy="afterInteractive"
        />
      </body>
    </html>
  )
}
