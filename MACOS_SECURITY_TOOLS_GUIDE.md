# Free Security Tools for macOS Development

## macOS COMPATIBILITY: ✅ ALL TOOLS SUPPORTED

All recommended security tools work perfectly on macOS! Here's your complete macOS setup guide:

---

## 📦 INSTALLATION METHODS

### 1. Homebrew (Primary Package Manager)
```bash
# Install Homebrew if not already installed
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"

# Essential security tools
brew install gitleaks trivy nmap lynis swiftlint wireshark mitmproxy

# Development tools
brew install node npm python3 openjdk

# Mobile development
brew install android-studio
```

### 2. Node.js/npm (JavaScript/TypeScript)
```bash
# Install Node.js (includes npm)
brew install node

# Security tools for JS/TS projects
npm install --save-dev eslint eslint-plugin-security eslint-plugin-xss
npm install -g snyk
```

### 3. Python/pip (Multi-purpose tools)
```bash
# Install Python 3
brew install python3

# Security tools
pip3 install bandit qark androguard trufflehog detect-secrets sqlmap objection frida-tools
```

### 4. Docker (Containerized tools)
```bash
# Install Docker Desktop for Mac
brew install --cask docker

# Run containerized security tools
docker run -d --name sonarqube -p 9000:9000 sonarqube:community
docker run -u zap -p 8080:8080 -i owasp/zap2docker-stable zap.sh -daemon
```

---

## 🔧 macOS-SPECIFIC TOOL CONFIGURATIONS

### SwiftLint (macOS/iOS Development)
```bash
# Install
brew install swiftlint

# Create .swiftlint.yml in project root
included:
  - SyncFlowMac
excluded:
  - Pods
  - Carthage

# Run analysis
swiftlint
```

### Xcode Clang Static Analyzer
```bash
# In your project directory
xcodebuild -scheme SyncFlowMac -configuration Debug clean analyze

# Or use Xcode GUI:
# Product → Analyze (Shift+Cmd+B)
```

### Android Studio Integration
```bash
# Android Lint is built into Android Studio
# Run: Analyze → Inspect Code
# Or command line:
./gradlew lint
```

### Wireshark for macOS
```bash
# Install GUI version
brew install --cask wireshark

# Or CLI version (tshark)
brew install wireshark

# Capture network traffic
sudo tshark -i en0 -f "host firestore.googleapis.com"
```

---

## 🚀 QUICK macOS SECURITY SETUP (30 minutes)

```bash
# 1. Install Homebrew (if not installed)
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"

# 2. Install essential security tools
brew install gitleaks swiftlint nmap lynis

# 3. Install development tools
brew install node python3
npm install --save-dev eslint eslint-plugin-security

# 4. Set up pre-commit hooks
npm install --save-dev husky lint-staged
npx husky install
npx husky add .husky/pre-commit "npx lint-staged"

# 5. Create lint-staged config in package.json
{
  "lint-staged": {
    "*.{js,ts,tsx}": ["eslint --fix", "gitleaks protect --staged"],
    "*.{swift}": ["swiftlint"],
    "*.{java,kt}": ["./gradlew spotlessCheck"]
  }
}

# 6. Test tools
eslint . --ext .js,.ts,.tsx
gitleaks detect --verbose
swiftlint
npm audit
```

---

## 🐳 DOCKER TOOLS FOR macOS

```bash
# 1. Install Docker Desktop
brew install --cask docker

# 2. Run SonarQube
docker run -d --name sonarqube \
  -p 9000:9000 \
  -v sonarqube_data:/opt/sonarqube/data \
  sonarqube:community

# 3. Run OWASP ZAP
docker run -u zap \
  -p 8080:8080 \
  -i owasp/zap2docker-stable \
  zap.sh -daemon -host 0.0.0.0 -port 8080

# 4. Run Trivy vulnerability scanner
docker run --rm \
  -v $(pwd):/app \
  aquasecurity/trivy:latest \
  fs --format table /app
```

---

## 📱 MOBILE DEVELOPMENT SECURITY (macOS)

### iOS/macOS Security Tools
```bash
# SwiftLint for code quality
brew install swiftlint

# Create .swiftlint.yml
disabled_rules:
  - trailing_whitespace
  - vertical_whitespace

# Run analysis
swiftlint lint --config .swiftlint.yml
```

### Android Security Tools
```bash
# Android Lint (built-in)
./gradlew lint

# QARK for Android APK analysis
pip3 install qark
qark --apk app/build/outputs/apk/debug/app-debug.apk

# Frida for runtime analysis
pip3 install frida-tools
frida-ps -U  # List running processes
```

---

## 🔐 macOS-SPECIFIC SECURITY CONSIDERATIONS

### 1. System Integrity Protection (SIP)
```bash
# Check SIP status
csrutil status

# Some tools may need SIP disabled for certain operations
# WARNING: Only disable temporarily for security testing
```

### 2. Gatekeeper & Quarantine
```bash
# Check downloaded app signatures
spctl -a /Applications/SyncFlow.app

# Remove quarantine attribute
xattr -d com.apple.quarantine /path/to/file
```

### 3. Keychain Access
```bash
# Store secrets securely
security add-generic-password -a "SyncFlow" -s "api-key" -w "your-secret"

# Retrieve secrets
security find-generic-password -a "SyncFlow" -s "api-key" -w
```

---

## 🔧 CI/CD WITH GITHUB ACTIONS (macOS runners)

```yaml
# .github/workflows/macos-security.yml
name: macOS Security Checks
on: [push, pull_request]

jobs:
  security-macos:
    runs-on: macos-latest
    steps:
      - uses: actions/checkout@v4
      
      # Install tools
      - name: Setup Homebrew
        run: brew update
        
      - name: Install Security Tools
        run: |
          brew install gitleaks swiftlint nmap
          
      # iOS/macOS Security
      - name: Run SwiftLint
        run: swiftlint --strict
        
      - name: Run Clang Static Analyzer
        run: |
          xcodebuild -scheme SyncFlowMac -configuration Debug clean analyze
          
      # Network Security
      - name: Run Nmap Port Scan
        run: nmap -sV localhost
        
      # Secret Detection
      - name: Run GitLeaks
        run: gitleaks detect --verbose --redact
```

---

## 🖥️ GUI TOOLS FOR macOS

### Native macOS Applications
```bash
# Wireshark GUI
brew install --cask wireshark

# Burp Suite Community Edition
# Download from: https://portswigger.net/burp/communitydownload

# Postman
brew install --cask postman

# Visual Studio Code (for development)
brew install --cask visual-studio-code
```

### Browser Security Tools
- **Chrome DevTools**: Built-in security analysis
- **Firefox Developer Tools**: Network and security inspection
- **Safari Web Inspector**: iOS/macOS web debugging

---

## 📊 MONITORING & LOGGING (macOS)

### Local ELK Stack
```bash
# Docker Compose for local monitoring
cat > docker-compose.yml << EOF
version: '3'
services:
  elasticsearch:
    image: docker.elastic.co/elasticsearch/elasticsearch:8.11.0
    environment:
      - discovery.type=single-node
      - xpack.security.enabled=false
    ports:
      - "9200:9200"
      
  logstash:
    image: docker.elastic.co/logstash/logstash:8.11.0
    volumes:
      - ./logstash.conf:/usr/share/logstash/pipeline/logstash.conf
    ports:
      - "5044:5044"
      
  kibana:
    image: docker.elastic.co/kibana/kibana:8.11.0
    ports:
      - "5601:5601"
EOF

docker-compose up -d
```

### System Monitoring
```bash
# Activity Monitor (built-in)
# - Monitor CPU, Memory, Network
# - Check for suspicious processes

# Console.app (built-in)
# - System log analysis
# - Application crash logs

# Terminal commands
top -u  # Process monitoring
netstat -an  # Network connections
lsof -i :443  # Open connections
```

---

## 🚨 macOS SECURITY BEST PRACTICES

### Development Environment
```bash
# Enable FileVault
sudo fdesetup enable

# Enable Firewall
sudo /usr/libexec/ApplicationFirewall/socketfilterfw --setglobalstate on

# Enable Stealth Mode
sudo /usr/libexec/ApplicationFirewall/socketfilterfw --setstealthmode on

# Regular system updates
softwareupdate -l  # Check for updates
softwareupdate -i -a  # Install all updates
```

### Code Signing & Notarization
```bash
# For distribution (future)
codesign --deep --force --verify --sign "Developer ID" SyncFlow.app
```

---

## 📈 SUCCESS METRICS FOR macOS

### Day 1 Achievements
- ✅ ESLint security rules configured
- ✅ GitLeaks secret detection active
- ✅ SwiftLint code quality checks
- ✅ npm audit running

### Week 1 Achievements
- ✅ SonarQube analysis integrated
- ✅ OWASP ZAP automated scanning
- ✅ GitHub Actions security workflow
- ✅ Pre-commit hooks active

### Month 1 Achievements
- ✅ Full CI/CD security pipeline
- ✅ 90% vulnerability detection coverage
- ✅ Automated dependency updates
- ✅ Security monitoring dashboard

---

## 🎯 macOS-SPECIFIC SECURITY WORKFLOW

```bash
# Daily development workflow
cd /Users/dchavali/GitHub/SyncFlow

# 1. Pre-commit checks (automatic)
git add .
git commit -m "feat: add new feature"
# → ESLint, GitLeaks, SwiftLint run automatically

# 2. Manual security checks
npm audit                    # Dependency vulnerabilities
gitleaks detect --verbose    # Secret detection
swiftlint                    # Code quality
trivy fs .                   # File system vulnerabilities

# 3. Push to trigger CI/CD
git push origin main
# → GitHub Actions runs full security suite

# 4. Monitor results
# Check GitHub Security tab
# Review SonarQube dashboard
# Monitor Sentry error tracking
```

**Your MacBook is the perfect development environment for comprehensive security testing! All tools work natively and integrate seamlessly with macOS development workflows. 🎯💻**