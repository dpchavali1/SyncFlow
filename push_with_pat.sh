#!/bin/bash

# Push SyncFlow Deals to GitHub with PAT
echo "🔑 GitHub Authentication Setup"
echo "=============================="
echo ""

echo "📋 You need a Personal Access Token (PAT):"
echo ""
echo "1. Go to: https://github.com/settings/tokens"
echo "2. Click: 'Generate new token (classic)'"
echo "3. Name: 'SyncFlow Deals Scraper'"
echo "4. Scopes: Check 'repo' (full control of private repositories)"
echo "5. Click: 'Generate token'"
echo "6. COPY the token immediately (you won't see it again!)"
echo ""

read -p "Enter your GitHub username: " username
read -s -p "Enter your Personal Access Token: " token
echo ""
echo ""

if [ -z "$token" ]; then
    echo "❌ No token provided. Please try again."
    exit 1
fi

echo "🔧 Setting up remote URL..."
git remote set-url origin "https://$username:$token@github.com/dpchavali1/syncflow-deals.git"

echo "📤 Pushing to GitHub..."
if git push -u origin main; then
    echo ""
    echo "🎉 SUCCESS! SyncFlow Deals Scraper is now live on GitHub!"
    echo ""
    echo "🤖 GitHub Actions will start running automatically:"
    echo "   • First run: Immediate"
    echo "   • Schedule: Every 6 hours"
    echo "   • Monitor: https://github.com/dpchavali1/syncflow-deals/actions"
    echo ""
    echo "📊 Your app will read deals from:"
    echo "   https://raw.githubusercontent.com/dpchavali1/syncflow-deals/main/deals.json"
    echo ""
    echo "🚀 The deals scraper is now fully automated!"
else
    echo ""
    echo "❌ Push failed. Please check:"
    echo "   • Token is correct and has 'repo' scope"
    echo "   • Repository exists and you have access"
    echo "   • Network connection is stable"
    echo ""
    echo "🔄 You can try again with: git push -u origin main"
fi