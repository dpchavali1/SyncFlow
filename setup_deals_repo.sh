#!/bin/bash

# Setup SyncFlow Deals Repository
# This script helps migrate the deals scraper to the correct repository

echo "🔄 SyncFlow Deals Repository Setup"
echo "==================================="
echo ""

# Check if we're in the right directory
if [ ! -f "syncflow_deals_scraper_improved.py" ]; then
    echo "❌ Error: syncflow_deals_scraper_improved.py not found in current directory"
    echo "Please run this script from the SyncFlow repository root"
    exit 1
fi

echo "📁 Current location: $(pwd)"
echo ""

# Create syncflow-deals directory
echo "📁 Creating syncflow-deals repository..."
mkdir -p ../syncflow-deals
cd ../syncflow-deals

# Initialize git if not already done
if [ ! -d ".git" ]; then
    git init
    git remote add origin https://github.com/dpchavali1/syncflow-deals.git
    echo "✅ Git repository initialized"
else
    echo "✅ Git repository already exists"
fi

# Copy files from SyncFlow repository
echo ""
echo "📋 Copying scraper files..."
cp ../SyncFlow/syncflow_deals_scraper_improved.py ./
cp ../SyncFlow/run_scraper.sh ./
cp ../SyncFlow/deploy_to_github.sh ./
cp ../SyncFlow/README.md ./
cp -r ../SyncFlow/.github ./

# Copy the current deals.json if it exists
if [ -f "../SyncFlow/deals.json" ]; then
    cp ../SyncFlow/deals.json ./
    echo "✅ Copied existing deals.json"
fi

# Make scripts executable
chmod +x run_scraper.sh
chmod +x deploy_to_github.sh

echo ""
echo "📁 Files copied:"
ls -la *.py *.sh *.md deals.json 2>/dev/null

echo ""
echo "🔧 Next steps:"
echo "1. Go to https://github.com/new and create 'syncflow-deals' repository"
echo "2. OR if it exists, ensure it's empty/ready for these files"
echo "3. Run: git add . && git commit -m 'Initial deals scraper setup' && git push -u origin main"

echo ""
echo "❓ Does the syncflow-deals repository already exist on GitHub?"
read -p "(y/N): " -n 1 -r
echo ""

if [[ $REPLY =~ ^[Yy]$ ]]; then
    echo "✅ Great! The repository exists."
    echo "🔄 Pushing files to GitHub..."
    git add .
    git commit -m "🤖 Deploy SyncFlow Deals Scraper

- Enhanced deal scraper with 25+ RSS sources
- Advanced deduplication (ASIN + title similarity)
- Quality scoring and categorization
- GitHub Actions automation (runs every 6 hours)
- Manual execution script included

Features:
✅ Smart duplicate removal
✅ URL sanitization (fixes broken links)
✅ 8 deal categories
✅ Automated updates every 6 hours"
    git push -u origin main

    echo ""
    echo "🎉 Success! The deals scraper is now deployed to:"
    echo "🔗 https://github.com/dpchavali1/syncflow-deals"
    echo ""
    echo "🤖 GitHub Actions will start running automatically every 6 hours!"
else
    echo "📝 To create the repository:"
    echo "1. Go to https://github.com/new"
    echo "2. Repository name: syncflow-deals"
    echo "3. Make it public"
    echo "4. Don't initialize with README"
    echo "5. Then run the git commands above"
fi

echo ""
echo "📊 Repository will be available at:"
echo "https://raw.githubusercontent.com/dpchavali1/syncflow-deals/main/deals.json"