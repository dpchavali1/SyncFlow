/** @type {import('next').NextConfig} */
const nextConfig = {
  output: 'standalone',
  reactStrictMode: true,
  swcMinify: true,
  images: {
    domains: ['firebasestorage.googleapis.com'],
  },
  // PWA configuration
  experimental: {
    appDir: true,
  },
}

module.exports = nextConfig
