package com.phoneintegration.app.auth

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.phoneintegration.app.security.SecurityEvent
import com.phoneintegration.app.security.SecurityEventType
import com.phoneintegration.app.security.SecurityMonitor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

/**
 * Enhanced Authentication Manager with secure session management.
 * Provides centralized authentication state management and security features.
 */
class AuthManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "AuthManager"
        private const val SESSION_TIMEOUT_MINUTES = 30L // Auto logout after 30 minutes of inactivity
        private const val TOKEN_REFRESH_BUFFER_MINUTES = 5L // Refresh token 5 minutes before expiry

        @Volatile
        private var instance: AuthManager? = null

        fun getInstance(context: Context): AuthManager {
            return instance ?: synchronized(this) {
                instance ?: AuthManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val auth = FirebaseAuth.getInstance()
    private val securityMonitor: SecurityMonitor? = try {
        SecurityMonitor.getInstance(context)
    } catch (e: Exception) {
        android.util.Log.w(TAG, "Failed to initialize SecurityMonitor, continuing without it", e)
        null
    }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Authentication state
    private val _authState = MutableStateFlow<AuthState>(AuthState.Unauthenticated)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    // Session tracking
    private var lastActivityTime = System.currentTimeMillis()
    private var sessionTimeoutJob: Job? = null
    private var tokenRefreshJob: Job? = null

    // Security settings
    private val _securitySettings = MutableStateFlow(SecuritySettings())
    val securitySettings: StateFlow<SecuritySettings> = _securitySettings.asStateFlow()

    init {
        setupAuthStateListener()
        startSessionMonitoring()
    }

    /**
     * Setup Firebase Auth state listener
     */
    private fun setupAuthStateListener() {
        auth.addAuthStateListener { firebaseAuth ->
            val user = firebaseAuth.currentUser
            scope.launch {
                if (user != null) {
                    _authState.value = AuthState.Authenticated(user)
                    startTokenRefreshMonitoring(user)

                    // Log successful authentication
                    securityMonitor?.logEvent(SecurityEvent(
                        type = SecurityEventType.AUTH_SUCCESS,
                        message = "User authenticated successfully",
                        metadata = mapOf("userId" to user.uid)
                    ))

                    Log.i(TAG, "User authenticated: ${user.uid}")
                } else {
                    _authState.value = AuthState.Unauthenticated
                    stopTokenRefreshMonitoring()

                    // Log unauthentication
                    securityMonitor?.logEvent(SecurityEvent(
                        type = SecurityEventType.SESSION_FORCED_LOGOUT,
                        message = "User session ended",
                        metadata = mapOf("reason" to "auth_state_changed")
                    ))

                    Log.i(TAG, "User unauthenticated")
                }
            }
        }
    }

    /**
     * Start session timeout monitoring
     */
    private fun startSessionMonitoring() {
        sessionTimeoutJob?.cancel()
        sessionTimeoutJob = scope.launch {
            while (isActive) {
                delay(TimeUnit.MINUTES.toMillis(1)) // Check every minute

                val currentTime = System.currentTimeMillis()
                val timeSinceLastActivity = currentTime - lastActivityTime

                if (_authState.value is AuthState.Authenticated &&
                    timeSinceLastActivity > TimeUnit.MINUTES.toMillis(SESSION_TIMEOUT_MINUTES)) {

                    // Log session timeout
                    securityMonitor?.logEvent(SecurityEvent(
                        type = SecurityEventType.SESSION_TIMEOUT,
                        message = "Session timed out due to inactivity",
                        metadata = mapOf(
                            "timeoutMinutes" to SESSION_TIMEOUT_MINUTES.toString(),
                            "inactiveMinutes" to (timeSinceLastActivity / (1000 * 60)).toString()
                        )
                    ))

                    Log.w(TAG, "Session timeout - logging out due to inactivity")
                    logout()
                    break
                }
            }
        }
    }

    /**
     * Start token refresh monitoring
     */
    private fun startTokenRefreshMonitoring(user: FirebaseUser) {
        tokenRefreshJob?.cancel()
        tokenRefreshJob = scope.launch {
            while (isActive && _authState.value is AuthState.Authenticated) {
                try {
                    user.getIdToken(false).await().let { tokenResult ->
                        val expirationTime = tokenResult.expirationTimestamp
                        val currentTime = System.currentTimeMillis()
                        val timeUntilExpiry = expirationTime - currentTime

                        // Refresh token if expiring soon
                        if (timeUntilExpiry < TimeUnit.MINUTES.toMillis(TOKEN_REFRESH_BUFFER_MINUTES)) {
                            Log.d(TAG, "Refreshing authentication token")
                            user.getIdToken(true).await()
                        }

                        // Wait before next check (don't spam)
                        delay(TimeUnit.MINUTES.toMillis(5))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error monitoring token refresh", e)
                    delay(TimeUnit.MINUTES.toMillis(1))
                }
            }
        }
    }

    /**
     * Stop token refresh monitoring
     */
    private fun stopTokenRefreshMonitoring() {
        tokenRefreshJob?.cancel()
        tokenRefreshJob = null
    }

    /**
     * Update last activity time (call this on user interactions)
     */
    fun updateActivity() {
        lastActivityTime = System.currentTimeMillis()
    }

    /**
     * Sign in anonymously with enhanced security
     */
    suspend fun signInAnonymously(): Result<String> {
        return try {
            val result = auth.signInAnonymously().await()
            val userId = result.user?.uid ?: throw Exception("No user ID returned")

            updateActivity()

            // Log successful authentication
            securityMonitor?.logEvent(SecurityEvent(
                type = SecurityEventType.AUTH_SUCCESS,
                message = "Anonymous authentication successful",
                metadata = mapOf("userId" to userId, "authMethod" to "anonymous")
            ))

            Log.i(TAG, "Anonymous sign in successful: $userId")
            Result.success(userId)

        } catch (e: Exception) {
            // Log authentication failure
            securityMonitor?.logEvent(SecurityEvent(
                type = SecurityEventType.AUTH_FAILED,
                message = "Anonymous authentication failed: ${e.message}",
                metadata = mapOf("error" to e.message.toString(), "authMethod" to "anonymous")
            ))

            Log.e(TAG, "Anonymous sign in failed", e)
            Result.failure(e)
        }
    }

    /**
     * Get current user ID with security checks
     */
    fun getCurrentUserId(): String? {
        val user = auth.currentUser
        return if (user != null && isSessionValid()) {
            updateActivity()
            user.uid
        } else {
            null
        }
    }

    /**
     * Check if current session is valid
     */
    private fun isSessionValid(): Boolean {
        val currentTime = System.currentTimeMillis()
        val timeSinceLastActivity = currentTime - lastActivityTime
        return timeSinceLastActivity <= TimeUnit.MINUTES.toMillis(SESSION_TIMEOUT_MINUTES)
    }

    /**
     * Force refresh authentication token
     */
    suspend fun refreshToken(): Result<Unit> {
        return try {
            val user = auth.currentUser ?: return Result.failure(Exception("No authenticated user"))
            user.getIdToken(true).await()
            updateActivity()
            Log.d(TAG, "Token refreshed successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Token refresh failed", e)
            Result.failure(e)
        }
    }

    /**
     * Logout with cleanup
     */
    fun logout() {
        try {
            auth.signOut()
            stopTokenRefreshMonitoring()
            sessionTimeoutJob?.cancel()
            _authState.value = AuthState.Unauthenticated
            Log.i(TAG, "User logged out successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error during logout", e)
        }
    }

    /**
     * Check if user is authenticated and session is valid
     */
    fun isAuthenticated(): Boolean {
        return auth.currentUser != null && isSessionValid()
    }

    /**
     * Get current Firebase user with security validation
     */
    fun getCurrentUser(): FirebaseUser? {
        return if (isAuthenticated()) {
            updateActivity()
            auth.currentUser
        } else {
            null
        }
    }

    /**
     * Update security settings
     */
    fun updateSecuritySettings(settings: SecuritySettings) {
        _securitySettings.value = settings
        Log.d(TAG, "Security settings updated: sessionTimeout=${settings.sessionTimeoutMinutes}min")
    }

    /**
     * Cleanup resources
     */
    fun cleanup() {
        sessionTimeoutJob?.cancel()
        tokenRefreshJob?.cancel()
        scope.cancel()
    }
}

/**
 * Authentication state sealed class
 */
sealed class AuthState {
    object Unauthenticated : AuthState()
    data class Authenticated(val user: FirebaseUser) : AuthState()
}

/**
 * Security settings data class
 */
data class SecuritySettings(
    val sessionTimeoutMinutes: Int = 30,
    val requireBiometricForSensitiveOperations: Boolean = false,
    val enableSessionTimeout: Boolean = true,
    val enableTokenAutoRefresh: Boolean = true
)