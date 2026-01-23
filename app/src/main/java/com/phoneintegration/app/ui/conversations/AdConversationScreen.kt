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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
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
    var filteredDeals by remember { mutableStateOf<List<Deal>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var selectedCategory by remember { mutableStateOf("All") }
    var searchQuery by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Load initial deals
    LaunchedEffect(Unit) {
        deals = repo.getDeals() // Get deals
        filteredDeals = deals
        loading = false
    }

    // Update filtered deals when category or search changes
    LaunchedEffect(selectedCategory, searchQuery, deals) {
        filteredDeals = when {
            searchQuery.isNotEmpty() -> {
                // Use search functionality
                scope.launch {
                    filteredDeals = repo.searchDeals(searchQuery)
                }
                deals // Temporary fallback while searching
            }
            selectedCategory != "All" -> {
                deals.filter { it.category.equals(selectedCategory, ignoreCase = true) }
            }
            else -> deals
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SyncFlow Deals") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    // Search button
                    IconButton(onClick = { showSearch = !showSearch }) {
                        Icon(
                            if (showSearch) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = if (showSearch) "Close search" else "Search deals"
                        )
                    }

                    // Refresh button
                    IconButton(
                        onClick = {
                            scope.launch {
                                refreshing = true
                                val result = repo.refreshDeals()
                                deals = repo.getDeals()
                                filteredDeals = deals
                                refreshing = false

                                if (result) {
                                    snackbarHostState.showSnackbar("✅ Deals updated!")
                                } else {
                                    snackbarHostState.showSnackbar("❌ Failed to refresh")
                                }
                            }
                        },
                        enabled = !refreshing
                    ) {
                        if (refreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh Deals")
                        }
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

                        // Refresh deals
                        val result = repo.refreshDeals()

                        deals = repo.getDeals()
                        filteredDeals = deals

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

            // Search bar (expandable)
            if (showSearch) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search deals...") },
                    leadingIcon = { Icon(Icons.Default.Search, "Search") },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, "Clear search")
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    singleLine = true
                )
            }

            // Derive categories dynamically from the deals
            val dealCategories = remember(deals) {
                listOf("All") + deals.map { it.category }.distinct().sorted()
            }

            DealCategoryChips(
                categories = dealCategories,
                selected = selectedCategory,
                onSelected = {
                    selectedCategory = it
                    if (showSearch) searchQuery = "" // Clear search when changing categories
                }
            )

            // Show deal count and refresh info
            if (!loading && filteredDeals.isNotEmpty()) {
                Text(
                    "${filteredDeals.size} deals available • Last updated: ${java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            if (loading) {
                LazyColumn {
                    items(5) {
                        DealShimmerPlaceholder()
                    }
                }
            } else {
                LazyColumn {
                    items(filteredDeals.size) { index ->
                        val deal = filteredDeals[index]

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
