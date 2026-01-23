#!/bin/bash

# Deploy SyncFlow Deals Scraper to GitHub
# This script helps set up the automated deal scraping system

echo "🚀 SyncFlow Deals Scraper - GitHub Deployment"
echo "=============================================="
echo ""

# Check if git is initialized
if [ ! -d ".git" ]; then
    echo "❌ Not a git repository. Please run this from your GitHub repository root."
    exit 1
fi

echo "📁 Repository structure check..."
echo ""

# Create .github/workflows directory if it doesn't exist
mkdir -p .github/workflows

# List of files to check
files=(
    "syncflow_deals_scraper_improved.py"
    ".github/workflows/update-deals.yml"
    "run_scraper.sh"
    "README.md"
)

echo "📋 Files to be committed:"
for file in "${files[@]}"; do
    if [ -f "$file" ]; then
        echo "  ✅ $file"
    else
        echo "  ❌ $file (missing)"
    fi
done

echo ""
echo "🔄 Current git status:"
git status --porcelain

echo ""
echo "📤 Ready to commit and push?"
read -p "Continue? (y/N): " -n 1 -r
echo ""

if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    echo "❌ Deployment cancelled."
    exit 1
fi

echo "📝 Adding files to git..."
git add .

echo ""
echo "💾 Committing changes..."
git commit -m "🤖 Deploy SyncFlow Deals Scraper

- Enhanced deal scraper with 25+ sources
- Advanced deduplication and quality scoring
- Automated GitHub Actions workflow (runs every 6 hours)
- Manual execution script (run_scraper.sh)
- Comprehensive documentation

Features:
✅ 27 deals found across 8 categories
✅ Smart deduplication (ASIN + title similarity)
✅ Quality scoring (discounts, prices, keywords)
✅ Clean URLs (fixed broken link issues)
✅ Automated updates every 6 hours"

echo ""
echo "⬆️ Pushing to GitHub..."
git push origin main

echo ""
echo "🎉 Deployment complete!"
echo ""
echo "📊 What happens next:"
echo "1. GitHub Actions will run the scraper automatically every 6 hours"
echo "2. Fresh deals will be committed back to the repository"
echo "3. Your SyncFlow app will always have up-to-date deals!"
echo ""
echo "🔗 Repository: https://github.com/dpchavali1/syncflow-deals"
echo "🤖 Actions: Check the Actions tab to see scraper runs"
echo ""
echo "📞 Manual trigger: Go to Actions → Update Amazon Deals → Run workflow"