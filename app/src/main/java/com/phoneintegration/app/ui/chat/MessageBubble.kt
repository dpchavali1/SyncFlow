package com.phoneintegration.app.ui.chat

import com.phoneintegration.app.SmsMessage
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.phoneintegration.app.MessageCategory
import com.phoneintegration.app.ui.shared.formatTimestamp

@Composable
fun MessageBubble(
    sms: SmsMessage,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isSent = sms.type == 2  // 1 = received, 2 = sent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = if (isSent) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 260.dp)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onLongPress = { onLongPress() }
                    )
                }
                .background(
                    color = bubbleColor(sms, isSent),
                    shape = RoundedCornerShape(
                        topStart = 12.dp,
                        topEnd = 12.dp,
                        bottomStart = if (isSent) 12.dp else 4.dp,
                        bottomEnd = if (isSent) 4.dp else 12.dp
                    )
                )
                .padding(12.dp)
        ) {
            Column {
                // Body
                Text(
                    text = sms.body,
                    color = if (isSent) Color.White else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Timestamp with delivery status
                Row(
                    modifier = Modifier.align(Alignment.End),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatTimestamp(sms.date),
                        color = if (isSent) Color.White.copy(alpha = 0.7f)
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.labelSmall
                    )
                    
                    // Show checkmark for sent messages
                    if (isSent) {
                        Icon(
                            imageVector = androidx.compose.material.icons.Icons.Default.Done,
                            contentDescription = "Sent",
                            modifier = Modifier.size(12.dp),
                            tint = Color.White.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Bubble color logic:
 * - Sent messages: blue bubble
 * - Received messages: category-based tint
 */
@Composable
private fun bubbleColor(sms: SmsMessage, isSent: Boolean): Color {
    if (isSent) {
        return MaterialTheme.colorScheme.primary
    }

    val catColor = when (sms.category) {
        MessageCategory.OTP -> Color(0xFFD6EAF8)
        MessageCategory.TRANSACTION -> Color(0xFFD1F2EB)
        MessageCategory.PERSONAL -> Color(0xFFFDEDEC)
        MessageCategory.PROMOTION -> Color(0xFFF9E79F)
        MessageCategory.ALERT -> Color(0xFFFADBD8)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    return catColor
}
