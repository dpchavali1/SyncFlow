'use client'

import { useState } from 'react'
import Link from 'next/link'

export default function DownloadPage() {
  const [copied, setCopied] = useState(false)

  const version = '1.0.0'
  const fileSize = '45 MB'
  const sha256 = 'Will be generated after build'
  const minMacOS = 'macOS 13.0 or later'

  const copyChecksum = () => {
    navigator.clipboard.writeText(sha256)
    setCopied(true)
    setTimeout(() => setCopied(false), 2000)
  }

  return (
    <div className="min-h-screen bg-gradient-to-br from-slate-50 to-blue-50 dark:from-slate-900 dark:to-slate-800">
      {/* Header */}
      <header className="border-b border-slate-200 dark:border-slate-700 bg-white/80 dark:bg-slate-900/80 backdrop-blur-sm sticky top-0 z-50">
        <div className="max-w-6xl mx-auto px-4 py-4 flex items-center justify-between">
          <Link href="/" className="text-2xl font-bold bg-gradient-to-r from-blue-600 to-purple-600 bg-clip-text text-transparent">
            SyncFlow
          </Link>
          <nav className="flex gap-6">
            <Link href="/privacy" className="text-slate-600 dark:text-slate-300 hover:text-blue-600">Privacy</Link>
            <Link href="/terms" className="text-slate-600 dark:text-slate-300 hover:text-blue-600">Terms</Link>
          </nav>
        </div>
      </header>

      <main className="max-w-5xl mx-auto px-4 py-16">
        {/* Hero Section */}
        <div className="text-center mb-16">
          <h1 className="text-5xl font-bold mb-4 bg-gradient-to-r from-blue-600 to-purple-600 bg-clip-text text-transparent">
            Download SyncFlow for Mac
          </h1>
          <p className="text-xl text-slate-600 dark:text-slate-300">
            Seamlessly sync your Android phone with your Mac
          </p>
        </div>

        {/* Download Card */}
        <div className="bg-gradient-to-br from-blue-500 to-purple-600 rounded-3xl p-1 mb-12 shadow-2xl">
          <div className="bg-white dark:bg-slate-900 rounded-3xl p-8">
            <div className="flex items-start gap-6">
              {/* App Icon Placeholder */}
              <div className="w-24 h-24 bg-gradient-to-br from-blue-500 to-purple-600 rounded-2xl flex items-center justify-center flex-shrink-0">
                <svg className="w-12 h-12 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M8 10h.01M12 10h.01M16 10h.01M9 16H5a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v8a2 2 0 01-2 2h-5l-5 5v-5z" />
                </svg>
              </div>

              <div className="flex-1">
                <h2 className="text-3xl font-bold mb-2 dark:text-white">SyncFlow {version}</h2>
                <p className="text-slate-600 dark:text-slate-300 mb-6">
                  {minMacOS} • Apple Silicon & Intel • {fileSize}
                </p>

                {/* Download Button */}
                <a
                  href={`/downloads/SyncFlow-${version}.dmg`}
                  className="inline-flex items-center gap-2 px-8 py-4 bg-gradient-to-r from-blue-600 to-purple-600 text-white rounded-xl font-semibold text-lg hover:shadow-lg hover:scale-105 transition-all duration-200"
                >
                  <svg className="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M9 19l3 3m0 0l3-3m-3 3V10" />
                  </svg>
                  Download for Mac
                </a>

                {/* Checksum */}
                <div className="mt-6 p-4 bg-slate-100 dark:bg-slate-800 rounded-lg">
                  <p className="text-xs text-slate-500 dark:text-slate-400 mb-1">SHA-256 Checksum:</p>
                  <div className="flex items-center gap-2">
                    <code className="text-xs font-mono text-slate-700 dark:text-slate-300 flex-1 truncate">
                      {sha256}
                    </code>
                    <button
                      onClick={copyChecksum}
                      className="text-xs px-3 py-1 bg-white dark:bg-slate-700 rounded hover:bg-slate-200 dark:hover:bg-slate-600 transition-colors"
                    >
                      {copied ? '✓ Copied' : 'Copy'}
                    </button>
                  </div>
                </div>
              </div>
            </div>

            {/* Trust Badges */}
            <div className="flex flex-wrap gap-4 mt-8 pt-6 border-t border-slate-200 dark:border-slate-700">
              <div className="flex items-center gap-2 text-sm text-slate-600 dark:text-slate-400">
                <svg className="w-5 h-5 text-green-500" fill="currentColor" viewBox="0 0 20 20">
                  <path fillRule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clipRule="evenodd" />
                </svg>
                Notarized by Apple
              </div>
              <div className="flex items-center gap-2 text-sm text-slate-600 dark:text-slate-400">
                <svg className="w-5 h-5 text-green-500" fill="currentColor" viewBox="0 0 20 20">
                  <path fillRule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clipRule="evenodd" />
                </svg>
                Code Signed
              </div>
              <div className="flex items-center gap-2 text-sm text-slate-600 dark:text-slate-400">
                <svg className="w-5 h-5 text-green-500" fill="currentColor" viewBox="0 0 20 20">
                  <path fillRule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clipRule="evenodd" />
                </svg>
                Privacy Focused
              </div>
              <div className="flex items-center gap-2 text-sm text-slate-600 dark:text-slate-400">
                <svg className="w-5 h-5 text-green-500" fill="currentColor" viewBox="0 0 20 20">
                  <path fillRule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clipRule="evenodd" />
                </svg>
                No Malware
              </div>
            </div>
          </div>
        </div>

        {/* Installation Guide */}
        <div className="bg-white dark:bg-slate-900 rounded-2xl p-8 mb-12 shadow-lg">
          <h2 className="text-2xl font-bold mb-6 dark:text-white">Installation Guide</h2>

          <div className="space-y-6">
            <div className="flex gap-4">
              <div className="w-10 h-10 bg-blue-100 dark:bg-blue-900 rounded-full flex items-center justify-center flex-shrink-0">
                <span className="text-blue-600 dark:text-blue-300 font-bold">1</span>
              </div>
              <div>
                <h3 className="font-semibold mb-2 dark:text-white">Download the DMG file</h3>
                <p className="text-slate-600 dark:text-slate-300">Click the download button above to get the SyncFlow installer.</p>
              </div>
            </div>

            <div className="flex gap-4">
              <div className="w-10 h-10 bg-blue-100 dark:bg-blue-900 rounded-full flex items-center justify-center flex-shrink-0">
                <span className="text-blue-600 dark:text-blue-300 font-bold">2</span>
              </div>
              <div>
                <h3 className="font-semibold mb-2 dark:text-white">Open the DMG file</h3>
                <p className="text-slate-600 dark:text-slate-300">Double-click the downloaded DMG file. A window will open showing the SyncFlow app icon.</p>
              </div>
            </div>

            <div className="flex gap-4">
              <div className="w-10 h-10 bg-blue-100 dark:bg-blue-900 rounded-full flex items-center justify-center flex-shrink-0">
                <span className="text-blue-600 dark:text-blue-300 font-bold">3</span>
              </div>
              <div>
                <h3 className="font-semibold mb-2 dark:text-white">Drag to Applications folder</h3>
                <p className="text-slate-600 dark:text-slate-300">Drag the SyncFlow icon to your Applications folder to install it.</p>
              </div>
            </div>

            <div className="flex gap-4">
              <div className="w-10 h-10 bg-blue-100 dark:bg-blue-900 rounded-full flex items-center justify-center flex-shrink-0">
                <span className="text-blue-600 dark:text-blue-300 font-bold">4</span>
              </div>
              <div>
                <h3 className="font-semibold mb-2 dark:text-white">Launch SyncFlow</h3>
                <p className="text-slate-600 dark:text-slate-300">Open SyncFlow from your Applications folder and follow the pairing instructions.</p>
              </div>
            </div>
          </div>

          <div className="mt-8 p-4 bg-yellow-50 dark:bg-yellow-900/20 border border-yellow-200 dark:border-yellow-800 rounded-lg">
            <div className="flex gap-3">
              <svg className="w-6 h-6 text-yellow-600 dark:text-yellow-500 flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
              </svg>
              <div>
                <h4 className="font-semibold text-yellow-900 dark:text-yellow-200 mb-1">First Launch Security</h4>
                <p className="text-sm text-yellow-800 dark:text-yellow-300">
                  On first launch, macOS may show a security warning. Right-click the app and select "Open" to bypass this one-time warning.
                </p>
              </div>
            </div>
          </div>
        </div>

        {/* System Requirements */}
        <div className="bg-white dark:bg-slate-900 rounded-2xl p-8 mb-12 shadow-lg">
          <h2 className="text-2xl font-bold mb-6 dark:text-white">System Requirements</h2>

          <div className="grid md:grid-cols-2 gap-6">
            <div>
              <h3 className="font-semibold mb-3 text-blue-600 dark:text-blue-400">Mac</h3>
              <ul className="space-y-2 text-slate-600 dark:text-slate-300">
                <li className="flex items-start gap-2">
                  <svg className="w-5 h-5 text-green-500 mt-0.5" fill="currentColor" viewBox="0 0 20 20">
                    <path fillRule="evenodd" d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z" clipRule="evenodd" />
                  </svg>
                  <span>macOS 13.0 (Ventura) or later</span>
                </li>
                <li className="flex items-start gap-2">
                  <svg className="w-5 h-5 text-green-500 mt-0.5" fill="currentColor" viewBox="0 0 20 20">
                    <path fillRule="evenodd" d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z" clipRule="evenodd" />
                  </svg>
                  <span>Apple Silicon (M1/M2/M3) or Intel processor</span>
                </li>
                <li className="flex items-start gap-2">
                  <svg className="w-5 h-5 text-green-500 mt-0.5" fill="currentColor" viewBox="0 0 20 20">
                    <path fillRule="evenodd" d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z" clipRule="evenodd" />
                  </svg>
                  <span>200 MB free disk space</span>
                </li>
                <li className="flex items-start gap-2">
                  <svg className="w-5 h-5 text-green-500 mt-0.5" fill="currentColor" viewBox="0 0 20 20">
                    <path fillRule="evenodd" d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z" clipRule="evenodd" />
                  </svg>
                  <span>Internet connection</span>
                </li>
              </ul>
            </div>

            <div>
              <h3 className="font-semibold mb-3 text-purple-600 dark:text-purple-400">Android Phone</h3>
              <ul className="space-y-2 text-slate-600 dark:text-slate-300">
                <li className="flex items-start gap-2">
                  <svg className="w-5 h-5 text-green-500 mt-0.5" fill="currentColor" viewBox="0 0 20 20">
                    <path fillRule="evenodd" d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z" clipRule="evenodd" />
                  </svg>
                  <span>Android 8.0 or later</span>
                </li>
                <li className="flex items-start gap-2">
                  <svg className="w-5 h-5 text-green-500 mt-0.5" fill="currentColor" viewBox="0 0 20 20">
                    <path fillRule="evenodd" d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z" clipRule="evenodd" />
                  </svg>
                  <span>SyncFlow Android app installed</span>
                </li>
                <li className="flex items-start gap-2">
                  <svg className="w-5 h-5 text-green-500 mt-0.5" fill="currentColor" viewBox="0 0 20 20">
                    <path fillRule="evenodd" d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z" clipRule="evenodd" />
                  </svg>
                  <span>Same Wi-Fi network for pairing</span>
                </li>
              </ul>
            </div>
          </div>
        </div>

        {/* FAQs */}
        <div className="bg-white dark:bg-slate-900 rounded-2xl p-8 shadow-lg">
          <h2 className="text-2xl font-bold mb-6 dark:text-white">Frequently Asked Questions</h2>

          <div className="space-y-6">
            <div>
              <h3 className="font-semibold mb-2 dark:text-white">Is SyncFlow safe to use?</h3>
              <p className="text-slate-600 dark:text-slate-300">
                Yes! SyncFlow is code-signed and notarized by Apple, which means it has been scanned for malware and verified by Apple. All data is encrypted end-to-end.
              </p>
            </div>

            <div>
              <h3 className="font-semibold mb-2 dark:text-white">Do I need a subscription?</h3>
              <p className="text-slate-600 dark:text-slate-300">
                SyncFlow offers a free tier with basic features. Premium features like photo sync and increased storage require a subscription ($4.99/month).
              </p>
            </div>

            <div>
              <h3 className="font-semibold mb-2 dark:text-white">How do I uninstall SyncFlow?</h3>
              <p className="text-slate-600 dark:text-slate-300">
                Simply drag the SyncFlow app from your Applications folder to the Trash, then empty the Trash.
              </p>
            </div>

            <div>
              <h3 className="font-semibold mb-2 dark:text-white">Where can I get support?</h3>
              <p className="text-slate-600 dark:text-slate-300">
                Visit our <Link href="/support" className="text-blue-600 hover:underline">support page</Link> or email us at support@syncflow.app for assistance.
              </p>
            </div>
          </div>
        </div>

        {/* Links */}
        <div className="text-center mt-12 text-sm text-slate-500 dark:text-slate-400">
          <Link href="/privacy" className="hover:text-blue-600 mx-3">Privacy Policy</Link>
          <span>•</span>
          <Link href="/terms" className="hover:text-blue-600 mx-3">Terms of Service</Link>
          <span>•</span>
          <a href="mailto:support@syncflow.app" className="hover:text-blue-600 mx-3">Support</a>
        </div>
      </main>

      {/* Footer */}
      <footer className="border-t border-slate-200 dark:border-slate-700 mt-16">
        <div className="max-w-6xl mx-auto px-4 py-8 text-center text-slate-600 dark:text-slate-400">
          <p>&copy; 2026 SyncFlow. All rights reserved.</p>
        </div>
      </footer>
    </div>
  )
}
