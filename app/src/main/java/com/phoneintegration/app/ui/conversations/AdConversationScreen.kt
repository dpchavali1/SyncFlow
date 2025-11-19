package com.phoneintegration.app.ui.conversations

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.phoneintegration.app.ui.deals.DealCategoryChips
import com.phoneintegration.app.ui.deals.DealShimmerPlaceholder
import com.phoneintegration.app.ui.deals.DealCard
import com.phoneintegration.app.deals.model.Deal
import com.phoneintegration.app.deals.DealsRepository
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdConversationScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val repo = remember { DealsRepository(context) }

    var deals by remember { mutableStateOf<List<Deal>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var selectedCategory by remember { mutableStateOf("All") }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Load initial deals
    LaunchedEffect(Unit) {
        deals = repo.getDeals().sortedByDescending { it.timestamp }
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SyncFlow Deals") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },

        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    scope.launch {
                        refreshing = true

                        // 🔥 FIX: use refreshFromCloud()
                        val result = repo.refreshFromCloud()

                        deals = repo.getDeals()
                            .sortedByDescending { it.timestamp }

                        refreshing = false

                        if (result) {
                            snackbarHostState.showSnackbar("Deals updated ✓")
                        } else {
                            snackbarHostState.showSnackbar("Failed to refresh deals")
                        }
                    }
                }
            ) {
                if (refreshing) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(26.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh Deals")
                }
            }
        }
    ) { padding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {

            // CATEGORY FILTERS
            DealCategoryChips(
                selected = selectedCategory,
                onSelected = { selectedCategory = it }
            )

            val filtered = deals
                .filter { selectedCategory == "All" || it.category == selectedCategory }
                .sortedByDescending { it.timestamp }

            if (loading) {
                LazyColumn {
                    items(5) {
                        DealShimmerPlaceholder()
                    }
                }
            } else {
                LazyColumn {
                    items(filtered.size) { index ->
                        val deal = filtered[index]

                        DealCard(
                            deal = deal,
                            onClick = {
                                // ❗ FIX: URL already has affiliate tag
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(deal.url))
                                context.startActivity(intent)
                            }
                        )
                    }
                }
            }
        }
    }
}
