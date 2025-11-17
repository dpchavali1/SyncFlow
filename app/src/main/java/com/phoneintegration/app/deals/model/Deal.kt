package com.phoneintegration.app.deals.model

import kotlinx.serialization.Serializable

@Serializable
data class Deal(
    val id: String,
    val title: String,
    val price: String,
    val image: String,
    val url: String,
    val category: String = "Tech"
)
