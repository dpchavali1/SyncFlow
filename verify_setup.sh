#!/bin/bash

# Verify SyncFlow Deals Repository Setup
echo "🔍 Verifying SyncFlow Deals Repository Setup"
echo "============================================="
echo ""

# Check if we're in the right directory
if [ ! -f "syncflow_deals_scraper_improved.py" ]; then
    echo "❌ Error: Not in syncflow-deals repository"
    echo "Please run this from the syncflow-deals repository root"
    exit 1
fi

echo "📁 Current location: $(pwd)"
echo ""

# Check required files
echo "📋 Checking required files:"
files=("syncflow_deals_scraper_improved.py" "run_scraper.sh" ".github/workflows/update-deals.yml" "README.md")
all_present=true

for file in "${files[@]}"; do
    if [ -f "$file" ]; then
        echo "  ✅ $file"
    else
        echo "  ❌ $file (missing)"
        all_present=false
    fi
done

echo ""

# Check git status
if [ -d ".git" ]; then
    echo "🔧 Git status:"
    git status --porcelain
    echo ""

    echo "📡 Remote repository:"
    git remote -v
    echo ""
else
    echo "❌ Not a git repository"
    all_present=false
fi

echo ""

# Check if deals.json exists
if [ -f "deals.json" ]; then
    deal_count=$(python3 -c "import json; print(len(json.load(open('deals.json'))['deals']))" 2>/dev/null)
    echo "📊 Current deals: $deal_count deals in deals.json"
else
    echo "⚠️ deals.json not found (will be created on first run)"
fi

echo ""

if [ "$all_present" = true ]; then
    echo "✅ Repository setup looks good!"
    echo ""
    echo "🚀 Ready to deploy:"
    echo "1. If repository doesn't exist on GitHub:"
    echo "   - Go to https://github.com/new"
    echo "   - Create 'syncflow-deals' repository (public)"
    echo "   - Don't initialize with README"
    echo ""
    echo "2. Deploy the code:"
    echo "   git add ."
    echo "   git commit -m 'Deploy SyncFlow Deals Scraper'"
    echo "   git push -u origin main"
    echo ""
    echo "3. Monitor automated runs:"
    echo "   - Go to Actions tab on GitHub"
    echo "   - Check 'Update Amazon Deals' workflow"
    echo ""
    echo "🤖 The scraper will run every 6 hours automatically!"
else
    echo "❌ Repository setup incomplete. Please run setup_deals_repo.sh first."
fi

echo ""
echo "🔗 Final URL for your app:"
echo "https://raw.githubusercontent.com/dpchavali1/syncflow-deals/main/deals.json"