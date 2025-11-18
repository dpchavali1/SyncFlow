package com.phoneintegration.app.ui.chat

import com.phoneintegration.app.SmsMessage
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import com.phoneintegration.app.MessageCategory
import com.phoneintegration.app.ui.shared.formatTimestamp
import androidx.compose.material.icons.filled.Done
import androidx.compose.foundation.isSystemInDarkTheme
import coil.compose.AsyncImage
import coil.compose.AsyncImage
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material3.TextButton
import android.provider.Telephony.Sms
import androidx.work.ListenableWorker.Result.retry

@Composable
fun MessageBubble(
    sms: SmsMessage,
    onLongPress: () -> Unit,
    onRetryMms: (SmsMessage) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isSent = sms.type == 2   // 1 = received, 2 = sent

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

                if (sms.isMms && sms.id < 0) {
                    Text(
                        text = "MMS send failed",
                        color = Color.Red,
                        style = MaterialTheme.typography.labelMedium
                    )

                    TextButton(onClick = { onRetryMms(sms) }) {
                        Text("Retry")
                    }
                }

                // MMS attachments block
                if (sms.isMms && sms.mmsAttachments.isNotEmpty()) {

                    sms.mmsAttachments.take(1).forEach { attach ->
                        when {
                            attach.isImage() -> {
                                AsyncImage(
                                    model = attach.filePath,
                                    contentDescription = "MMS Image",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 350.dp)
                                        .clip(RoundedCornerShape(18.dp))
                                )
                            }
                            attach.isVideo() -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(250.dp)
                                        .clip(RoundedCornerShape(18.dp))
                                ) {
                                    AsyncImage(
                                        model = attach.filePath,
                                        contentDescription = "MMS Video Thumbnail",
                                        modifier = Modifier.matchParentSize()
                                    )
                                    Icon(
                                        Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier
                                            .size(48.dp)
                                            .align(Alignment.Center)
                                    )
                                }
                            }
                            attach.isAudio() -> {
                                Text(
                                    "🎵 Audio attachment (${attach.fileName ?: "audio"})",
                                    color = bubbleTextColor(isSent)
                                )
                            }
                            attach.isVCard() -> {
                                Text(
                                    "📇 Contact Card (${attach.fileName ?: "contact"})",
                                    color = bubbleTextColor(isSent)
                                )
                            }
                        }
                    }

                    // If more attachments exist, show "+3 more"
                    if (sms.mmsAttachments.size > 1) {
                        Text(
                            text = "+${sms.mmsAttachments.size - 1} more",
                            color = bubbleTextColor(isSent),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }

                    if (!sms.body.isNullOrBlank())
                        Spacer(modifier = Modifier.height(6.dp))
                }

                // TEXT part (SMS or MMS text)
                if (sms.body.isNotBlank()) {
                    Text(
                        text = sms.body,
                        color = bubbleTextColor(isSent),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                // TIMESTAMP + CHECKMARK
                Row(
                    modifier = Modifier.align(Alignment.End),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatTimestamp(sms.date),
                        color = bubbleTextColor(isSent).copy(alpha = 0.65f),
                        style = MaterialTheme.typography.labelSmall
                    )

                    if (isSent) {
                        Icon(
                            imageVector = androidx.compose.material.icons.Icons.Default.Done,
                            contentDescription = "Sent",
                            modifier = Modifier.size(12.dp),
                            tint = bubbleTextColor(isSent).copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }
}

/**
 * BUBBLE BACKGROUND COLOR
 * Dark-mode safe auto colors.
 */
@Composable
private fun bubbleColor(sms: SmsMessage, isSent: Boolean): Color {
    val isDark = isSystemInDarkTheme()

    return if (isSent) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        when (sms.category) {
            MessageCategory.OTP ->
                if (isDark) MaterialTheme.colorScheme.surfaceVariant else Color(0xFFD6EAF8)
            MessageCategory.TRANSACTION ->
                if (isDark) MaterialTheme.colorScheme.surfaceVariant else Color(0xFFD1F2EB)
            MessageCategory.PERSONAL ->
                if (isDark) MaterialTheme.colorScheme.surfaceVariant else Color(0xFFFDEDEC)
            MessageCategory.PROMOTION ->
                if (isDark) MaterialTheme.colorScheme.surfaceVariant else Color(0xFFF9E79F)
            MessageCategory.ALERT ->
                if (isDark) MaterialTheme.colorScheme.surfaceVariant else Color(0xFFFADBD8)

            else -> MaterialTheme.colorScheme.surfaceVariant
        }
    }
}


/**
 * BUBBLE TEXT COLOR
 */
@Composable
private fun bubbleTextColor(isSent: Boolean): Color {
    return if (isSent)
        MaterialTheme.colorScheme.onPrimaryContainer
    else
        MaterialTheme.colorScheme.onSurface
}
