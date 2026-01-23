To push the deals scraper to GitHub, run these commands in the syncflow-deals directory:

```bash
cd ../syncflow-deals

# Option 1: Use Personal Access Token (recommended)
git remote set-url origin https://YOUR_USERNAME:YOUR_TOKEN@github.com/dpchavali1/syncflow-deals.git
git push -u origin main

# Option 2: Use SSH (if you have SSH keys set up)
git remote set-url origin git@github.com:dpchavali1/syncflow-deals.git
git push -u origin main

# Option 3: Use GitHub CLI (if installed)
gh auth login
git push -u origin main
```

### **To Create a Personal Access Token:**

1. Go to https://github.com/settings/tokens
2. Click **"Generate new token (classic)"**
3. Give it a name like "SyncFlow Deals Scraper"
4. Select scopes: **`repo`** (full control of private repositories)
5. Click **Generate token**
6. **Copy the token immediately** (you won't see it again)

### **Then run:**
```bash
git remote set-url origin https://dpchavali1:YOUR_TOKEN@github.com/dpchavali1/syncflow-deals.git
git push -u origin main
```

Once pushed, GitHub Actions will automatically start running the scraper every 6 hours! 🤖

**The repository URL will be:**
https://raw.githubusercontent.com/dpchavali1/syncflow-deals/main/deals.json