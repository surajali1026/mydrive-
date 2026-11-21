package com.example.ui.components

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.data.model.AccountStatus
import com.example.data.model.CloudProviderType
import com.example.data.model.FileCategory
import com.example.ui.theme.AmberThreshold
import com.example.ui.theme.CoralCritical
import com.example.ui.theme.EmeraldHealthy
import com.example.ui.theme.JetBrainsMonoFontFamily
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

/**
 * Custom Canvas 70% Threshold Progress Bar with a crisp vertical marker at the
 * 70% automatic provider-switch limit.
 */
@Composable
fun ThresholdProgressBar(
    usageRatio: Float,
    modifier: Modifier = Modifier,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    showMarkerLabel: Boolean = true
) {
    val clamped = usageRatio.coerceIn(0f, 1f)
    val fillColor = when {
        clamped >= 0.90f -> CoralCritical
        clamped >= 0.70f -> AmberThreshold
        else -> EmeraldHealthy
    }
    val markerColor = MaterialTheme.colorScheme.onSurface

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
        ) {
            val barHeight = 8.dp.toPx()
            val barTop = (size.height - barHeight) / 2f
            val corner = CornerRadius(barHeight / 2f, barHeight / 2f)

            // Track
            drawRoundRect(
                color = trackColor,
                topLeft = Offset(0f, barTop),
                size = Size(size.width, barHeight),
                cornerRadius = corner
            )

            // Active fill
            if (clamped > 0f) {
                drawRoundRect(
                    color = fillColor,
                    topLeft = Offset(0f, barTop),
                    size = Size((size.width * clamped).coerceAtLeast(barHeight), barHeight),
                    cornerRadius = corner
                )
            }

            // Visible 70% auto-switch threshold marker line
            val thresholdX = size.width * 0.70f
            drawLine(
                color = markerColor.copy(alpha = 0.9f),
                start = Offset(thresholdX, 0f),
                end = Offset(thresholdX, size.height),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round
            )
        }

        if (showMarkerLabel) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${(clamped * 100).toInt()}% used",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = JetBrainsMonoFontFamily,
                        color = fillColor
                    )
                )
                Text(
                    text = "70% auto-switch limit",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = JetBrainsMonoFontFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
        }
    }
}

@Composable
fun ProviderBadge(
    provider: CloudProviderType,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = provider.accentColor.copy(alpha = 0.14f),
        contentColor = provider.accentColor
    ) {
        Row(
            modifier = Modifier
                .border(1.dp, provider.accentColor.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Cloud,
                contentDescription = provider.displayName,
                modifier = Modifier.size(12.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = provider.shortBadge,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
            )
        }
    }
}

@Composable
fun AccountStatusPill(
    status: AccountStatus,
    modifier: Modifier = Modifier
) {
    val (bg, fg) = when (status) {
        AccountStatus.CONNECTED -> EmeraldHealthy.copy(alpha = 0.16f) to EmeraldHealthy
        AccountStatus.EXPIRED_RECONNECT -> CoralCritical.copy(alpha = 0.16f) to CoralCritical
        AccountStatus.INTEGRATION_PENDING -> AmberThreshold.copy(alpha = 0.18f) to AmberThreshold
        AccountStatus.DISCONNECTED -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(fg)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = status.label,
            style = MaterialTheme.typography.labelMedium.copy(
                color = fg,
                fontWeight = FontWeight.SemiBold
            )
        )
    }
}

@Composable
fun FileThumbnailBox(
    category: FileCategory,
    thumbnailUri: String?,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(accentColor.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center
    ) {
        if (!thumbnailUri.isNullOrBlank() && category == FileCategory.PHOTOS) {
            AsyncImage(
                model = Uri.parse(thumbnailUri),
                contentDescription = "File thumbnail",
                modifier = Modifier.size(48.dp),
                contentScale = ContentScale.Crop
            )
        } else {
            val icon = when (category) {
                FileCategory.PHOTOS -> Icons.Filled.Image
                FileCategory.VIDEOS -> Icons.Filled.VideoFile
                FileCategory.AUDIO -> Icons.Filled.AudioFile
                FileCategory.DOCUMENTS -> Icons.Filled.Description
                else -> Icons.Filled.InsertDriveFile
            }
            Icon(
                imageVector = icon,
                contentDescription = category.label,
                tint = accentColor,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes < 0L) return "Unavailable"
    if (bytes == 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceIn(0, units.lastIndex)
    val value = bytes / 1024.0.pow(digitGroups.toDouble())
    return String.format(Locale.US, if (digitGroups >= 2) "%.2f %s" else "%.0f %s", value, units[digitGroups])
}

fun formatShortDate(epochMs: Long): String {
    if (epochMs <= 0L) return "Never"
    val fmt = SimpleDateFormat("MMM d, yyyy • HH:mm", Locale.getDefault())
    return fmt.format(Date(epochMs))
}

fun formatClockTime(hour: Int, minute: Int): String {
    val amPm = if (hour >= 12) "PM" else "AM"
    val h12 = when {
        hour == 0 -> 12
        hour > 12 -> hour - 12
        else -> hour
    }
    return String.format(Locale.US, "%02d:%02d %s", h12, minute, amPm)
}
