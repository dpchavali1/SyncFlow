package com.phoneintegration.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max
import kotlin.math.min

private const val TRIAL_DAYS = 7 // 7 day trial
private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L

// Trial/Free tier: 500MB upload/month, 1GB storage
private const val TRIAL_MONTHLY_UPLOAD_BYTES = 500L * 1024L * 1024L
private const val TRIAL_STORAGE_BYTES = 1L * 1024L * 1024L * 1024L

// Paid tier: 3GB upload/month, 15GB storage
private const val PAID_MONTHLY_UPLOAD_BYTES = 3L * 1024L * 1024L * 1024L
private const val PAID_STORAGE_BYTES = 15L * 1024L * 1024L * 1024L

private data class UsageSummary(
    val plan: String?,
    val planExpiresAt: Long?,
    val trialStartedAt: Long?,
    val storageBytes: Long,
    val monthlyUploadBytes: Long,
    val monthlyMmsBytes: Long,
    val monthlyFileBytes: Long,
    val lastUpdatedAt: Long?,
    val isPaid: Boolean
)

private sealed class UsageUiState {
    object Loading : UsageUiState()
    data class Loaded(val summary: UsageSummary) : UsageUiState()
    data class Error(val message: String) : UsageUiState()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageSettingsScreen(onBack: () -> Unit) {
    val auth = remember { FirebaseAuth.getInstance() }
    val database = remember { FirebaseDatabase.getInstance() }
    val scope = rememberCoroutineScope()
    val currentUserId = auth.currentUser?.uid

    var state by remember { mutableStateOf<UsageUiState>(UsageUiState.Loading) }

    val loadUsage: () -> Unit = {
        scope.launch {
            val userId = auth.currentUser?.uid
            if (userId.isNullOrBlank()) {
                state = UsageUiState.Error("Not signed in")
                return@launch
            }

            state = UsageUiState.Loading
            try {
                // FIRST: Try to load from users/{uid}/usage
                val snapshot = database.reference
                    .child("users")
                    .child(userId)
                    .child("usage")
                    .get()
                    .await()

                // If usage data exists, use it
                if (snapshot.exists() && snapshot.child("plan").value != null) {
                    state = UsageUiState.Loaded(parseUsage(snapshot))
                    return@launch
                }

                // FALLBACK: Check subscription_records/{uid}/active (persists after user deletion)
                val subscriptionSnapshot = database.reference
                    .child("subscription_records")
                    .child(userId)
                    .child("active")
                    .get()
                    .await()

                // If subscription record exists, use it with empty usage stats
                if (subscriptionSnapshot.exists()) {
                    state = UsageUiState.Loaded(parseUsage(snapshot, subscriptionSnapshot))
                } else {
                    state = UsageUiState.Loaded(parseUsage(snapshot))
                }
            } catch (e: Exception) {
                state = UsageUiState.Error(e.message ?: "Failed to load usage")
            }
        }
    }

    LaunchedEffect(Unit) {
        loadUsage()
    }

    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Usage & Limits") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = loadUsage) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (val current = state) {
                UsageUiState.Loading -> {
                    Spacer(modifier = Modifier.height(24.dp))
                    CircularProgressIndicator()
                }
                is UsageUiState.Error -> {
                    Text(
                        text = current.message,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                is UsageUiState.Loaded -> {
                    val summary = current.summary
                    val now = System.currentTimeMillis()
                    val planLabel = planLabel(summary.plan, summary.isPaid)
                    val trialDays = trialDaysRemaining(summary.trialStartedAt, now)
                    val monthlyLimit = if (summary.isPaid) PAID_MONTHLY_UPLOAD_BYTES else TRIAL_MONTHLY_UPLOAD_BYTES
                    val storageLimit = if (summary.isPaid) PAID_STORAGE_BYTES else TRIAL_STORAGE_BYTES

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .padding(16.dp)
                                .fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("User ID", style = MaterialTheme.typography.titleMedium)
                                val clipboardManager = LocalClipboardManager.current
                                val coroutineScope = rememberCoroutineScope()

                                IconButton(
                                    onClick = {
                                        currentUserId?.let { userId ->
                                            clipboardManager.setText(AnnotatedString(userId))
                                            coroutineScope.launch {
                                                snackbarHostState.showSnackbar("User ID copied to clipboard")
                                            }
                                        }
                                    }
                                ) {
                                    Icon(
                                        Icons.Filled.ContentCopy,
                                        contentDescription = "Copy User ID",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Text(
                                text = currentUserId ?: "Unknown",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .padding(16.dp)
                                .fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("Plan", style = MaterialTheme.typography.titleMedium)
                            Text(planLabel, style = MaterialTheme.typography.bodyLarge)
                            if (!summary.isPaid) {
                                Text(
                                    text = if (summary.trialStartedAt == null) {
                                        "Trial not started (starts on first upload)"
                                    } else if (trialDays > 0) {
                                        "$trialDays days remaining in trial"
                                    } else {
                                        "Trial expired"
                                    },
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            } else if (summary.planExpiresAt != null) {
                                Text(
                                    text = "Renews on ${formatDate(summary.planExpiresAt)}",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .padding(16.dp)
                                .fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text("Monthly uploads", style = MaterialTheme.typography.titleMedium)
                            UsageBar(
                                label = "${formatBytes(summary.monthlyUploadBytes)} / ${formatBytes(monthlyLimit)}",
                                progress = ratio(summary.monthlyUploadBytes, monthlyLimit)
                            )
                            Text(
                                text = "MMS: ${formatBytes(summary.monthlyMmsBytes)} • Files: ${formatBytes(summary.monthlyFileBytes)}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .padding(16.dp)
                                .fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text("Storage", style = MaterialTheme.typography.titleMedium)
                            UsageBar(
                                label = "${formatBytes(summary.storageBytes)} / ${formatBytes(storageLimit)}",
                                progress = ratio(summary.storageBytes, storageLimit)
                            )
                        }
                    }

                    if (summary.lastUpdatedAt != null) {
                        Text(
                            text = "Last updated ${formatDateTime(summary.lastUpdatedAt)}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UsageBar(label: String, progress: Float) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
    }
}

private fun parseUsage(snapshot: DataSnapshot, subscriptionSnapshot: DataSnapshot? = null): UsageSummary {
    // Load plan and expiry data
    var plan = snapshot.child("plan").getValue(String::class.java)
    var planExpiresAt = snapshot.child("planExpiresAt").longValue()

    // FALLBACK: If no plan in usage snapshot, check subscription_records
    if (plan.isNullOrBlank() && subscriptionSnapshot != null && subscriptionSnapshot.exists()) {
        plan = subscriptionSnapshot.child("plan").getValue(String::class.java)
        planExpiresAt = subscriptionSnapshot.child("planExpiresAt").longValue()
    }

    val trialStartedAt = snapshot.child("trialStartedAt").longValue()
    val storageBytes = snapshot.child("storageBytes").longValue() ?: 0L
    val lastUpdatedAt = snapshot.child("lastUpdatedAt").longValue()

    val periodKey = currentPeriodKey()
    val monthlySnapshot = snapshot.child("monthly").child(periodKey)
    val monthlyUploadBytes = monthlySnapshot.child("uploadBytes").longValue() ?: 0L
    val monthlyMmsBytes = monthlySnapshot.child("mmsBytes").longValue() ?: 0L
    val monthlyFileBytes = monthlySnapshot.child("fileBytes").longValue() ?: 0L

    val now = System.currentTimeMillis()
    val isPaid = isPaidPlan(plan, planExpiresAt, now)

    return UsageSummary(
        plan = plan,
        planExpiresAt = planExpiresAt,
        trialStartedAt = trialStartedAt,
        storageBytes = storageBytes,
        monthlyUploadBytes = monthlyUploadBytes,
        monthlyMmsBytes = monthlyMmsBytes,
        monthlyFileBytes = monthlyFileBytes,
        lastUpdatedAt = lastUpdatedAt,
        isPaid = isPaid
    )
}

private fun DataSnapshot.longValue(): Long? {
    val value = this.value ?: return null
    return when (value) {
        is Long -> value
        is Double -> value.toLong()
        is Int -> value.toLong()
        is String -> value.toLongOrNull()
        else -> null
    }
}

private fun planLabel(plan: String?, isPaid: Boolean): String {
    if (!isPaid) return "Trial"
    return when (plan?.lowercase(Locale.US)) {
        "lifetime" -> "Lifetime"
        "yearly" -> "Yearly"
        "monthly" -> "Monthly"
        "paid" -> "Paid"
        else -> "Paid"
    }
}

private fun trialDaysRemaining(trialStartedAt: Long?, now: Long): Int {
    if (trialStartedAt == null) return TRIAL_DAYS
    val end = trialStartedAt + TRIAL_DAYS * MILLIS_PER_DAY
    val remaining = end - now
    return max(0, (remaining / MILLIS_PER_DAY).toInt())
}

private fun currentPeriodKey(): String {
    val formatter = SimpleDateFormat("yyyyMM", Locale.US)
    formatter.timeZone = TimeZone.getTimeZone("UTC")
    return formatter.format(Date())
}

private fun isPaidPlan(plan: String?, planExpiresAt: Long?, now: Long): Boolean {
    val normalized = plan?.lowercase(Locale.US) ?: return false
    if (normalized == "lifetime") {
        return true
    }
    if (normalized == "monthly" || normalized == "yearly" || normalized == "paid") {
        return planExpiresAt?.let { it > now } ?: true
    }
    return false
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format(Locale.US, "%.1f GB", gb)
}

private fun ratio(used: Long, limit: Long): Float {
    if (limit <= 0L) return 0f
    return min(1f, used.toFloat() / limit.toFloat())
}

private fun formatDate(timestamp: Long): String {
    val formatter = SimpleDateFormat("MMM d, yyyy", Locale.US)
    return formatter.format(Date(timestamp))
}

private fun formatDateTime(timestamp: Long): String {
    val formatter = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.US)
    return formatter.format(Date(timestamp))
}
