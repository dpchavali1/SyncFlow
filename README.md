# 🤖 SyncFlow Deals Scraper

Automatically scrapes and aggregates Amazon deals from multiple RSS sources to provide fresh, high-quality deals for the SyncFlow messaging app.

## 📊 Features

- **25+ Deal Sources**: RSS feeds from Reddit, SlickDeals, DealNews, and Amazon deal blogs
- **Smart Deduplication**: Advanced duplicate removal using ASIN codes and title similarity
- **Quality Scoring**: Prioritizes deals based on discounts, prices, and keywords
- **Category Classification**: Automatically categorizes deals (Tech, Gaming, Home, Pets, etc.)
- **URL Sanitization**: Cleans malformed URLs to ensure all links work
- **Automated Updates**: Runs every 6 hours via GitHub Actions

## 🚀 Quick Start

### Manual Execution

```bash
# Make the script executable
chmod +x run_scraper.sh

# Run the scraper
./run_scraper.sh
```

Or manually:
```bash
pip3 install feedparser requests beautifulsoup4 lxml
python3 syncflow_deals_scraper_improved.py
```

### Automated Execution

The scraper runs automatically every 6 hours via GitHub Actions. To trigger manually:

1. Go to your repository on GitHub
2. Click **Actions** tab
3. Select **Update Amazon Deals** workflow
4. Click **Run workflow**

## 📁 Output Format

The scraper generates `deals.json` with this structure:

```json
{
  "deals": [
    {
      "id": "B07P5RX1LR",
      "title": "Amazon Essentials Hoodies, Sherpa-Lined Pullover",
      "image": "https://m.media-amazon.com/images/I/B07P5RX1LR._AC_SL1500_.jpg",
      "price": "$8.03",
      "rating": "N/A",
      "reviews": "N/A",
      "url": "https://www.amazon.com/dp/B07P5RX1LR?tag=syncflow-20",
      "category": "General",
      "timestamp": 1768849297,
      "score": 6,
      "discount": 60
    }
  ]
}
```

## 🔧 Configuration

### Deal Sources

Edit `RSS_FEEDS` in `syncflow_deals_scraper_improved.py` to add/remove sources:

```python
RSS_FEEDS = [
    "https://slickdeals.net/newsearch.php?searchin=first&rss=1",
    "https://www.reddit.com/r/buildapcsales/.rss",
    # Add more sources here...
]
```

### Affiliate Settings

```python
AFFILIATE_TAG = "syncflow-20"  # Your Amazon affiliate tag
MAX_DEALS = 80                 # Maximum deals to collect
REQUEST_TIMEOUT = 15           # Timeout for HTTP requests
```

## 📈 Deal Categories

The scraper automatically categorizes deals into:

- **Tech**: Laptops, phones, accessories, components
- **Gaming**: Consoles, games, controllers, PCs
- **Home**: Kitchen, lighting, appliances, furniture
- **Pets**: Food, toys, beds, accessories
- **Beauty**: Personal care, cosmetics, health
- **Fitness**: Exercise equipment, supplements, apparel
- **Accessories**: Cases, chargers, bags, peripherals
- **Gifts**: Toys, games, seasonal items
- **General**: Everything else

## 🎯 Quality Metrics

Each deal gets scored based on:
- **Price Attractiveness**: Lower prices = higher scores
- **Discount Percentage**: 50%+ discounts get bonus points
- **Keywords**: "New", "Hot", "Limited" boost scores
- **Category Popularity**: Popular categories score higher

## 🔄 Automation

### GitHub Actions Setup

The workflow (`.github/workflows/update-deals.yml`) automatically:

1. Runs every 6 hours on schedule
2. Triggers when the scraper script is updated
3. Allows manual execution
4. Commits updated `deals.json` back to the repository
5. Creates a summary of changes

### Repository Structure

```
syncflow-deals/
├── .github/workflows/
│   └── update-deals.yml          # GitHub Actions workflow
├── syncflow_deals_scraper_improved.py  # Main scraper script
├── run_scraper.sh               # Manual execution script
├── deals.json                   # Generated deals data
└── README.md                    # This file
```

## 📊 Monitoring

Check the **Actions** tab in your GitHub repository to see:
- When the scraper last ran
- How many deals were found
- Any errors or failures
- Deal category breakdown

## 🛠️ Troubleshooting

### Common Issues

1. **"Module not found" errors**:
   ```bash
   pip3 install feedparser requests beautifulsoup4 lxml
   ```

2. **No deals found**:
   - Check RSS feed URLs are still active
   - Some feeds may have rate limiting
   - Try running during off-peak hours

3. **GitHub Actions failing**:
   - Check repository secrets
   - Ensure proper permissions
   - Review action logs for specific errors

### Debug Mode

Add debug prints to see what's happening:

```python
# In the main() function, add:
print(f"[DEBUG] Processing {len(entries)} entries from {feed_url}")
```

## 📈 Performance

- **Execution Time**: 2-3 minutes for full run
- **Rate Limiting**: 1.5 second delays between requests
- **Error Handling**: Continues even if some sources fail
- **Memory Usage**: Minimal, processes deals in batches

## 🤝 Contributing

1. Fork the repository
2. Add new RSS sources to `RSS_FEEDS`
3. Improve categorization logic
4. Enhance duplicate detection
5. Test thoroughly before submitting

## 📄 License

This project is part of the SyncFlow ecosystem. See main repository for license details.

---

**Happy deal hunting! 🎁**