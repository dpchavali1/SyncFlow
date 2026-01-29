#!/bin/bash
set -e

# Configuration - UPDATE THESE VALUES
VERSION="1.0.0"
APP_NAME="SyncFlow"
BUNDLE_ID="com.syncflow.mac"
DEVELOPER_ID="Developer ID Application: YOUR_NAME (YOUR_TEAM_ID)"
APPLE_ID="your@email.com"
TEAM_ID="YOUR_TEAM_ID"
PROJECT_PATH="./SyncFlowMac/SyncFlowMac.xcodeproj"
SCHEME="SyncFlowMac"

echo "🚀 Building $APP_NAME v$VERSION..."

# Clean previous builds
rm -rf build
rm -rf "$APP_NAME.xcarchive"
rm -f "$APP_NAME-$VERSION.dmg"

# Build and archive the app
echo "📦 Archiving..."
xcodebuild \
  -project "$PROJECT_PATH" \
  -scheme "$SCHEME" \
  -configuration Release \
  -archivePath "$APP_NAME.xcarchive" \
  archive

# Export the app
echo "📤 Exporting..."
xcodebuild \
  -exportArchive \
  -archivePath "$APP_NAME.xcarchive" \
  -exportPath ./build \
  -exportOptionsPlist ./scripts/ExportOptions.plist

# Sign the app
echo "✍️  Signing..."
codesign --deep --force --verify --verbose \
  --sign "$DEVELOPER_ID" \
  --options runtime \
  --timestamp \
  "./build/$APP_NAME.app"

# Verify signature
echo "🔍 Verifying signature..."
codesign --verify --deep --strict --verbose=2 "./build/$APP_NAME.app"
spctl --assess --type execute --verbose ./build/$APP_NAME.app

# Create DMG
echo "💿 Creating DMG..."
hdiutil create \
  -volname "$APP_NAME" \
  -srcfolder "./build/$APP_NAME.app" \
  -ov \
  -format UDZO \
  "$APP_NAME-$VERSION.dmg"

# Sign the DMG
echo "✍️  Signing DMG..."
codesign --sign "$DEVELOPER_ID" "$APP_NAME-$VERSION.dmg"

# Notarize
echo "📮 Submitting for notarization..."
echo "⏳ This may take 5-15 minutes..."

xcrun notarytool submit "$APP_NAME-$VERSION.dmg" \
  --apple-id "$APPLE_ID" \
  --team-id "$TEAM_ID" \
  --password "@keychain:AC_PASSWORD" \
  --wait

# Staple the ticket
echo "📎 Stapling notarization ticket..."
xcrun stapler staple "$APP_NAME-$VERSION.dmg"

# Verify stapling
echo "🔍 Verifying staple..."
xcrun stapler validate "$APP_NAME-$VERSION.dmg"

# Generate checksum
echo "🔐 Generating SHA-256 checksum..."
shasum -a 256 "$APP_NAME-$VERSION.dmg" > "$APP_NAME-$VERSION.dmg.sha256"
cat "$APP_NAME-$VERSION.dmg.sha256"

# File size
FILE_SIZE=$(du -h "$APP_NAME-$VERSION.dmg" | cut -f1)
echo ""
echo "✅ Build complete!"
echo "📦 File: $APP_NAME-$VERSION.dmg"
echo "💾 Size: $FILE_SIZE"
echo "🔐 SHA-256: $(cat "$APP_NAME-$VERSION.dmg.sha256" | cut -d' ' -f1)"
echo ""
echo "🎉 Ready to upload to sfweb.app/downloads/"
