package com.phoneintegration.app.ui.deals

import androidx.compose.foundation.layout.*
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

val DEAL_CATEGORIES = listOf(
    "All",
    "Tech",
    "Home",
    "Fitness",
    "Accessories",
    "Gifts"
)

@Composable
fun DealCategoryChips(
    selected: String,
    onSelected: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        DEAL_CATEGORIES.forEach { category ->
            FilterChip(
                selected = selected == category,
                onClick = { onSelected(category) },
                label = { Text(category) },
                modifier = Modifier,
                colors = FilterChipDefaults.filterChipColors()
            )
        }
    }
}
