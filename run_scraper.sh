#!/bin/bash

# SyncFlow Deals Scraper Runner
# This script runs the improved deal scraper and shows results

echo "🤖 SyncFlow Deals Scraper"
echo "=========================="
echo ""

# Check if Python is available
if ! command -v python3 &> /dev/null; then
    echo "❌ Python 3 is not installed. Please install Python 3 first."
    exit 1
fi

# Install dependencies if needed
echo "📦 Installing/updating dependencies..."
pip3 install --quiet feedparser requests beautifulsoup4 lxml

if [ $? -ne 0 ]; then
    echo "❌ Failed to install dependencies"
    exit 1
fi

echo ""
echo "🔍 Starting deal scraping..."
echo "This may take 2-3 minutes..."
echo ""

# Run the scraper
python3 syncflow_deals_scraper_improved.py

# Show results
if [ -f "deals.json" ]; then
    DEAL_COUNT=$(python3 -c "import json; print(len(json.load(open('deals.json'))['deals']))")
    echo ""
    echo "✅ Scraping completed successfully!"
    echo "📊 Found $DEAL_COUNT deals"
    echo ""
    echo "📂 Output saved to: deals.json"
    echo ""
    echo "🔝 Top 3 deals:"
    python3 -c "
import json
deals = json.load(open('deals.json'))['deals'][:3]
for i, deal in enumerate(deals, 1):
    print(f'{i}. {deal[\"title\"][:60]}... (${deal[\"price\"]})')
"
else
    echo "❌ Scraping failed - no deals.json file created"
    exit 1
fi

echo ""
echo "🎉 Done! Deals are ready for your app."