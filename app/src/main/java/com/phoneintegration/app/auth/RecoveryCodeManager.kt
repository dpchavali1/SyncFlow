package com.phoneintegration.app.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * RecoveryCodeManager handles account recovery codes.
 *
 * This provides a FREE alternative to SMS verification:
 * - Generate a unique recovery code on first launch
 * - User saves this code (screenshot, write down, etc.)
 * - If user reinstalls, they enter the code to recover their account
 * - No SMS costs, no phone number required
 */
class RecoveryCodeManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "RecoveryCodeManager"
        private const val PREFS_NAME = "syncflow_recovery"
        private const val KEY_RECOVERY_CODE = "recovery_code"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_SETUP_COMPLETE = "setup_complete"
        private const val KEY_SKIPPED = "setup_skipped"

        // Code format: SYNC-XXXX-XXXX-XXXX (16 chars + separators)
        private const val CODE_LENGTH = 12
        private val CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // No confusing chars (0/O, 1/I/L)

        @Volatile
        private var instance: RecoveryCodeManager? = null

        fun getInstance(context: Context): RecoveryCodeManager {
            return instance ?: synchronized(this) {
                instance ?: RecoveryCodeManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val auth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase.getInstance()
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Check if user has completed recovery setup (either set up code or skipped)
     */
    fun isSetupComplete(): Boolean {
        return prefs.getBoolean(KEY_SETUP_COMPLETE, false) || prefs.getBoolean(KEY_SKIPPED, false)
    }

    /**
     * Check if user skipped recovery setup
     */
    fun hasSkippedSetup(): Boolean {
        return prefs.getBoolean(KEY_SKIPPED, false)
    }

    /**
     * Get the stored recovery code (if any)
     */
    fun getStoredRecoveryCode(): String? {
        return prefs.getString(KEY_RECOVERY_CODE, null)
    }

    /**
     * Get the stored user ID
     */
    fun getStoredUserId(): String? {
        return prefs.getString(KEY_USER_ID, null)
    }

    /**
     * Generate a new recovery code
     */
    fun generateRecoveryCode(): String {
        val random = SecureRandom()
        val code = StringBuilder()

        repeat(CODE_LENGTH) {
            code.append(CODE_CHARS[random.nextInt(CODE_CHARS.length)])
        }

        return formatRecoveryCode(code.toString())
    }

    /**
     * Format a raw recovery code as SYNC-XXXX-XXXX-XXXX
     */
    fun formatRecoveryCode(rawCode: String): String {
        // Remove any existing formatting and normalize
        val clean = rawCode.uppercase().replace("-", "").replace(" ", "")

        // Remove SYNC prefix if present
        val codeOnly = if (clean.startsWith("SYNC")) clean.substring(4) else clean

        // Ensure we have exactly 12 characters
        if (codeOnly.length < 12) return rawCode // Can't format, return as-is

        // Format as SYNC-XXXX-XXXX-XXXX
        return "SYNC-${codeOnly.substring(0, 4)}-${codeOnly.substring(4, 8)}-${codeOnly.substring(8, 12)}"
    }

    /**
     * Hash a recovery code for secure storage in Firebase
     */
    private fun hashCode(code: String): String {
        val normalizedCode = code.uppercase().replace("-", "").replace(" ", "")
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(normalizedCode.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Set up a new recovery code for the current user
     * Returns the generated code IMMEDIATELY - Firebase sync happens in background
     */
    suspend fun setupRecoveryCode(): Result<String> {
        return try {
            // Ensure user is signed in (anonymous is fine) - with timeout
            var user = auth.currentUser
            if (user == null) {
                Log.d(TAG, "No user, signing in anonymously")
                try {
                    val result = withTimeout(5000) { auth.signInAnonymously().await() }
                    user = result.user
                } catch (e: Exception) {
                    Log.e(TAG, "Anonymous sign-in failed/timeout, continuing anyway", e)
                }
            }

            val userId = user?.uid ?: run {
                // If we still don't have a user, generate a local-only code
                Log.w(TAG, "No Firebase user, generating local-only code")
                val code = generateRecoveryCode()
                prefs.edit()
                    .putString(KEY_RECOVERY_CODE, code)
                    .putBoolean(KEY_SETUP_COMPLETE, true)
                    .putBoolean(KEY_SKIPPED, false)
                    .apply()
                return Result.success(code)
            }

            Log.d(TAG, "Setting up recovery code for user: $userId")

            // Generate new code INSTANTLY
            val code = generateRecoveryCode()
            val codeHash = hashCode(code)

            // Store locally FIRST (instant) - user sees code immediately
            prefs.edit()
                .putString(KEY_RECOVERY_CODE, code)
                .putString(KEY_USER_ID, userId)
                .putBoolean(KEY_SETUP_COMPLETE, true)
                .putBoolean(KEY_SKIPPED, false)
                .apply()

            Log.d(TAG, "Recovery code generated: ${code.take(9)}... - syncing to Firebase in background")

            // Sync to Firebase in BACKGROUND (fire and forget - don't block UI)
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    // Store mapping in Firebase: recovery_codes/{hash} -> userId
                    val recoveryRef = database.reference.child("recovery_codes").child(codeHash)
                    recoveryRef.setValue(mapOf(
                        "userId" to userId,
                        "createdAt" to System.currentTimeMillis(),
                        "platform" to "android"
                    ))

                    // Also store the code under the user's data for easy access later
                    val userRecoveryRef = database.reference.child("users").child(userId).child("recovery_info")
                    userRecoveryRef.setValue(mapOf(
                        "code" to code,
                        "createdAt" to System.currentTimeMillis(),
                        "codeHash" to codeHash
                    ))

                    Log.d(TAG, "Recovery code synced to Firebase successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Background Firebase sync failed (code still works locally)", e)
                }
            }

            Result.success(code)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up recovery code", e)
            Result.failure(e)
        }
    }

    /**
     * Recover account using a recovery code
     * Returns the user ID if successful
     * Uses Cloud Function for faster recovery (avoids Firebase offline mode issues)
     */
    suspend fun recoverWithCode(code: String): Result<String> {
        return try {
            val normalizedCode = code.uppercase().replace(" ", "").replace("-", "")
            val codeHash = hashCode(normalizedCode)

            Log.d(TAG, "Attempting recovery with code hash: ${codeHash.take(8)}...")

            // Use Cloud Function for fast recovery
            val functions = com.google.firebase.functions.FirebaseFunctions.getInstance()
            val result = functions
                .getHttpsCallable("recoverAccount")
                .call(mapOf("codeHash" to codeHash))
                .await()

            val data = result.data as? Map<*, *>
            val success = data?.get("success") as? Boolean ?: false
            val userId = data?.get("userId") as? String

            if (!success || userId == null) {
                Log.w(TAG, "Recovery failed: success=$success, userId=$userId")
                return Result.failure(Exception("Invalid recovery code. Please check and try again."))
            }

            Log.d(TAG, "Found userId via Cloud Function: $userId")

            // First sign in anonymously to get a session
            if (auth.currentUser == null) {
                auth.signInAnonymously().await()
            }

            // Store the recovered userId - the app will use this for data access
            // Format the code properly before storing (SYNC-XXXX-XXXX-XXXX)
            val formattedCode = formatRecoveryCode(normalizedCode)
            prefs.edit()
                .putString(KEY_RECOVERY_CODE, formattedCode)
                .putString(KEY_USER_ID, userId)
                .putBoolean(KEY_SETUP_COMPLETE, true)
                .putBoolean(KEY_SKIPPED, false)
                .apply()

            Log.d(TAG, "Recovery successful for user: $userId")
            Result.success(userId)
        } catch (e: Exception) {
            Log.e(TAG, "Error recovering with code", e)
            val message = when {
                e.message?.contains("not-found") == true -> "Invalid recovery code. Please check and try again."
                e.message?.contains("UNAVAILABLE") == true -> "Network error. Please check your connection."
                else -> e.message ?: "Recovery failed"
            }
            Result.failure(Exception(message))
        }
    }

    /**
     * Skip recovery setup (user accepts data loss risk)
     */
    suspend fun skipSetup(): Result<String> {
        return try {
            // Sign in anonymously
            var user = auth.currentUser
            if (user == null) {
                val result = auth.signInAnonymously().await()
                user = result.user
            }

            val userId = user?.uid ?: return Result.failure(Exception("Failed to sign in"))

            // Mark as skipped
            prefs.edit()
                .putString(KEY_USER_ID, userId)
                .putBoolean(KEY_SKIPPED, true)
                .putBoolean(KEY_SETUP_COMPLETE, false)
                .apply()

            Log.d(TAG, "Recovery setup skipped, using anonymous auth: $userId")
            Result.success(userId)
        } catch (e: Exception) {
            Log.e(TAG, "Error skipping setup", e)
            Result.failure(e)
        }
    }

    /**
     * Get the effective user ID (recovered or current)
     * This should be used instead of FirebaseAuth.currentUser.uid
     */
    fun getEffectiveUserId(): String? {
        // First check if we have a recovered userId
        val storedUserId = prefs.getString(KEY_USER_ID, null)
        if (storedUserId != null) {
            return storedUserId
        }
        // Fall back to current Firebase user
        return auth.currentUser?.uid
    }

    /**
     * Fetch recovery code from Firebase (if local copy is lost)
     */
    suspend fun fetchRecoveryCodeFromFirebase(): String? {
        return try {
            val userId = getEffectiveUserId() ?: return null
            val userRecoveryRef = database.reference.child("users").child(userId).child("recovery_info")
            val snapshot = userRecoveryRef.get().await()

            if (snapshot.exists()) {
                val code = snapshot.child("code").getValue(String::class.java)
                // Update local cache if found
                if (code != null) {
                    prefs.edit().putString(KEY_RECOVERY_CODE, code).apply()
                }
                code
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching recovery code from Firebase", e)
            null
        }
    }

    /**
     * Get recovery code (from local cache or Firebase)
     * Always returns formatted code (SYNC-XXXX-XXXX-XXXX)
     */
    suspend fun getRecoveryCode(): String? {
        // First try local cache
        val localCode = getStoredRecoveryCode()
        if (localCode != null) {
            // Ensure proper formatting when returning
            return formatRecoveryCode(localCode)
        }
        // Try fetching from Firebase
        val firebaseCode = fetchRecoveryCodeFromFirebase()
        return firebaseCode?.let { formatRecoveryCode(it) }
    }

    /**
     * Clear all recovery data (for testing/logout)
     */
    fun clearRecoveryData() {
        prefs.edit().clear().apply()
    }
}
