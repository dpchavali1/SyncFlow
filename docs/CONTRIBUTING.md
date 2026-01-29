# Contributing to SyncFlow

Thank you for your interest in contributing to SyncFlow! This document provides guidelines and best practices for contributing to the project.

## Code Style Guidelines

### Swift (macOS App)

```swift
// MARK: - File Header Template
//
//  FileName.swift
//  SyncFlowMac
//
//  Purpose: Brief description of what this file does
//  Dependencies: List key dependencies
//

import SwiftUI

// MARK: - Main Type

/// Documentation for the main type
/// - Note: Important notes for developers
struct MyView: View {

    // MARK: - Properties

    /// Description of property
    @State private var isLoading = false

    // MARK: - Body

    var body: some View {
        // Implementation
    }

    // MARK: - Private Methods

    /// Brief description
    /// - Parameter param: Description
    /// - Returns: What it returns
    private func doSomething(param: String) -> Bool {
        // Complex logic should have inline comments
        return true
    }
}

// MARK: - Supporting Types

/// Supporting struct/enum documentation
struct SupportingType {
    // ...
}
```

**Swift Guidelines:**
- Use `// MARK: -` to organize code sections
- Use `///` for documentation comments
- Keep functions under 50 lines when possible
- Prefer `guard` for early returns
- Use meaningful variable names
- Group related properties together

### Kotlin (Android App)

```kotlin
/**
 * FileName.kt
 *
 * Purpose: Brief description of what this file does
 * Dependencies: List key dependencies
 *
 * Architecture Notes:
 * - How this fits into the app architecture
 */

package com.phoneintegration.app.feature

import ...

/**
 * Main class documentation
 *
 * @property propertyName Description of property
 */
class MyService(
    private val context: Context
) {

    // region Properties

    /** Description of constant */
    companion object {
        private const val TAG = "MyService"
        private const val TIMEOUT_MS = 30000L
    }

    // endregion

    // region Public Methods

    /**
     * Brief description of what this method does
     *
     * @param param Description of parameter
     * @return Description of return value
     * @throws ExceptionType When this exception is thrown
     */
    suspend fun doSomething(param: String): Result<Data> {
        // Implementation with inline comments for complex logic
    }

    // endregion

    // region Private Methods

    private fun helperMethod() {
        // ...
    }

    // endregion
}
```

**Kotlin Guidelines:**
- Use `// region` / `// endregion` to organize code
- Use KDoc (`/** */`) for documentation
- Prefer `suspend` functions over callbacks
- Use `Result` type for error handling
- Keep Composables focused and small
- Extract reusable UI into separate components

### TypeScript/React (Web App)

```typescript
/**
 * ComponentName.tsx
 *
 * Purpose: Brief description of what this component does
 * Dependencies: List key dependencies
 */

import React from 'react'

// ============================================================================
// Types
// ============================================================================

/** Props for the component */
interface MyComponentProps {
  /** Description of prop */
  title: string
  /** Optional prop with default */
  isEnabled?: boolean
  /** Callback description */
  onAction: (id: string) => void
}

/** Internal state type */
interface State {
  isLoading: boolean
  data: DataType | null
}

// ============================================================================
// Component
// ============================================================================

/**
 * Brief component description
 *
 * @example
 * <MyComponent title="Hello" onAction={handleAction} />
 */
export function MyComponent({ title, isEnabled = true, onAction }: MyComponentProps) {
  // --- State ---
  const [state, setState] = useState<State>({ isLoading: false, data: null })

  // --- Effects ---
  useEffect(() => {
    // Effect logic with comments
  }, [dependency])

  // --- Handlers ---
  const handleClick = useCallback(() => {
    // Handler logic
    onAction('id')
  }, [onAction])

  // --- Render ---
  return (
    <div>
      {/* JSX with comments for complex sections */}
    </div>
  )
}

// ============================================================================
// Helper Functions
// ============================================================================

/** Helper function description */
function formatData(data: DataType): string {
  return data.toString()
}
```

**TypeScript Guidelines:**
- Use section separators (`// ===`) for organization
- Define interfaces for all props and state
- Use JSDoc for function documentation
- Prefer named exports over default exports
- Keep components under 200 lines
- Extract hooks for complex logic

### JavaScript (Cloud Functions)

```javascript
/**
 * functions/index.js
 *
 * Firebase Cloud Functions for SyncFlow
 *
 * Architecture:
 * - All functions are HTTPS callable unless noted
 * - Authentication required for all user operations
 * - Admin functions require admin claim
 */

const functions = require('firebase-functions')
const admin = require('firebase-admin')

// ============================================================================
// SECTION: User Operations
// ============================================================================

/**
 * Brief description of what this function does
 *
 * @param {Object} data - Input data
 * @param {string} data.param1 - Description of param1
 * @param {number} data.param2 - Description of param2
 * @param {Object} context - Firebase context with auth info
 * @returns {Promise<Object>} Description of return value
 * @throws {functions.https.HttpsError} When and why errors are thrown
 *
 * Database writes:
 * - users/{userId}/path - What is written
 *
 * Security:
 * - Requires authentication
 * - Users can only access their own data
 */
exports.myFunction = functions.https.onCall(async (data, context) => {
  // 1. Validate authentication
  if (!context.auth) {
    throw new functions.https.HttpsError('unauthenticated', 'Must be logged in')
  }

  // 2. Validate input
  const { param1, param2 } = data
  if (!param1) {
    throw new functions.https.HttpsError('invalid-argument', 'param1 required')
  }

  // 3. Perform operation
  try {
    // Implementation with step comments
    return { success: true }
  } catch (error) {
    console.error('myFunction error:', error)
    throw new functions.https.HttpsError('internal', 'Operation failed')
  }
})
```

## Git Commit Guidelines

### Commit Message Format

```
<type>: <short description>

<optional body with more details>

Co-Authored-By: Your Name <email>
```

### Types
- `feat`: New feature
- `fix`: Bug fix
- `docs`: Documentation only
- `style`: Code style (formatting, etc.)
- `refactor`: Code refactoring
- `perf`: Performance improvement
- `test`: Adding tests
- `chore`: Maintenance tasks

### Examples
```
feat: Add photo sync progress indicator

- Show upload percentage in status bar
- Add cancel button for in-progress syncs
- Handle network interruptions gracefully

fix: Resolve message duplication on reconnect

The real-time listener was re-adding existing messages
when the connection was restored. Added deduplication
based on message ID.
```

## Pull Request Process

1. **Create a feature branch**
   ```bash
   git checkout -b feature/my-feature
   ```

2. **Make your changes**
   - Follow code style guidelines
   - Add documentation comments
   - Test on both Android and macOS

3. **Commit with meaningful messages**

4. **Push and create PR**
   ```bash
   git push origin feature/my-feature
   ```

5. **PR Description Template**
   ```markdown
   ## Summary
   Brief description of changes

   ## Changes
   - Change 1
   - Change 2

   ## Testing
   - [ ] Tested on Android
   - [ ] Tested on macOS
   - [ ] Tested on Web (if applicable)

   ## Screenshots (if UI changes)
   ```

## Testing Requirements

### Before Submitting PR

1. **Android**
   ```bash
   ./gradlew test
   ./gradlew lint
   ```

2. **macOS**
   - Build succeeds in Xcode
   - No new warnings

3. **Cloud Functions**
   ```bash
   cd functions && npm test
   ```

4. **Manual Testing**
   - Test the feature end-to-end
   - Test edge cases
   - Test on both platforms if sync-related

## Documentation Requirements

### When to Add Documentation

1. **New files**: Always add file header comment
2. **Public APIs**: Document all public methods
3. **Complex logic**: Add inline comments
4. **Architecture changes**: Update ARCHITECTURE.md

### Documentation Checklist

- [ ] File header with purpose
- [ ] Public method documentation
- [ ] Parameter descriptions
- [ ] Return value descriptions
- [ ] Error conditions noted
- [ ] Usage examples for complex APIs

## Questions?

If you have questions about contributing:
1. Check existing documentation in `/docs`
2. Look at similar code in the codebase
3. Open a GitHub issue for discussion
