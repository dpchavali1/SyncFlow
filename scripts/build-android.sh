#!/bin/bash
set -e

VERSION="1.0.0"
VERSION_CODE=1
APP_NAME="SyncFlow"

echo "🚀 Building Android APK v$VERSION..."

cd app

# Clean previous builds
echo "🧹 Cleaning..."
../gradlew clean

# Build release APK
echo "📦 Building release APK..."
../gradlew assembleRelease

# Build release AAB (for Play Store)
echo "📦 Building release AAB..."
../gradlew bundleRelease

cd ..

# Find the built files
APK_PATH="app/build/outputs/apk/release/app-release.apk"
AAB_PATH="app/build/outputs/bundle/release/app-release.aab"

# Check if files exist
if [ ! -f "$APK_PATH" ]; then
    echo "❌ Error: APK not found at $APK_PATH"
    exit 1
fi

if [ ! -f "$AAB_PATH" ]; then
    echo "❌ Error: AAB not found at $AAB_PATH"
    exit 1
fi

# Rename with version
cp "$APK_PATH" "SyncFlow-$VERSION.apk"
cp "$AAB_PATH" "SyncFlow-$VERSION.aab"

# Generate checksums
echo "🔐 Generating checksums..."
shasum -a 256 "SyncFlow-$VERSION.apk" > "SyncFlow-$VERSION.apk.sha256"
shasum -a 256 "SyncFlow-$VERSION.aab" > "SyncFlow-$VERSION.aab.sha256"

# File sizes
APK_SIZE=$(du -h "SyncFlow-$VERSION.apk" | cut -f1)
AAB_SIZE=$(du -h "SyncFlow-$VERSION.aab" | cut -f1)

echo ""
echo "✅ Build complete!"
echo "📦 APK: SyncFlow-$VERSION.apk ($APK_SIZE)"
echo "📦 AAB: SyncFlow-$VERSION.aab ($AAB_SIZE)"
echo "🔐 APK SHA-256: $(cat "SyncFlow-$VERSION.apk.sha256" | cut -d' ' -f1)"
echo "🔐 AAB SHA-256: $(cat "SyncFlow-$VERSION.aab.sha256" | cut -d' ' -f1)"
echo ""
echo "📱 APK ready for sfweb.app/downloads/"
echo "📱 AAB ready for Google Play Console"
