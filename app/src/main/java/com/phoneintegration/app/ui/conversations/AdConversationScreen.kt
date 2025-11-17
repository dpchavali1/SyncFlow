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
import androidx.compose.ui.platform.LocalContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdConversationScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val repo = remember { DealsRepository(context) }

    var deals by remember { mutableStateOf<List<Deal>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var selectedCategory by remember { mutableStateOf("All") }

    LaunchedEffect(Unit) {
        deals = repo.getDeals()
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

            val filtered = deals.filter {
                selectedCategory == "All" || it.category == selectedCategory
            }

            if (loading) {
                LazyColumn {
                    items(5) {
                        DealShimmerPlaceholder()
                    }
                }
            } else {
                val context = LocalContext.current   // ✔ allowed at top level

                LazyColumn {
                    items(filtered.size) { index ->
                        val deal = filtered[index]

                        DealCard(
                            deal = deal,
                            onClick = {
                                val url = "${deal.url}?tag=syncflow-20"
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                context.startActivity(intent)   // ✔ use context inside lambda
                            }
                        )
                    }
                }

            }
        }
    }
}
