package com.ararshot.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ararshot.app.engine.AnimationModelManager
import com.ararshot.app.model.AnimationModelsUiState
import com.ararshot.app.model.AnimationStyle
import com.ararshot.app.ui.theme.ArArColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnimationModelManagerSheet(
    state: AnimationModelsUiState,
    onDownload: (AnimationStyle) -> Unit,
    onCancelDownload: () -> Unit,
    onDelete: (AnimationStyle) -> Unit,
    onDeleteAll: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ArArColors.Surface1,
        contentColor = ArArColors.TextPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Model Animasi",
                        style = MaterialTheme.typography.titleLarge,
                        color = ArArColors.TextPrimary
                    )
                    Text(
                        text = "Terpasang ${state.installedIds.size}/${AnimationStyle.entries.size} · ${AnimationModelManager.formatBytes(state.totalInstalledBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = ArArColors.TextSecondary
                    )
                }
                if (state.installedIds.isNotEmpty() && state.downloadingId == null) {
                    Text(
                        text = "Hapus Semua",
                        style = MaterialTheme.typography.labelLarge,
                        color = ArArColors.Danger,
                        modifier = Modifier
                            .clip(RoundedCornerShape(100))
                            .clickable(onClick = onDeleteAll)
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            Text(
                text = "Unduh hanya gaya animasi yang Anda butuhkan agar penyimpanan tetap hemat. Setelah terpasang, model berjalan offline di perangkat.",
                style = MaterialTheme.typography.bodySmall,
                color = ArArColors.TextMuted
            )

            Spacer(Modifier.height(2.dp))

            AnimationStyle.entries.forEach { style ->
                val isInstalled = style.id in state.installedIds
                val isDownloading = state.downloadingId == style.id
                val otherDownloading = state.downloadingId != null && !isDownloading

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(ArArColors.Surface2)
                        .border(
                            width = 1.dp,
                            color = if (isInstalled) ArArColors.AccentCyan.copy(alpha = 0.35f) else ArArColors.Border,
                            shape = RoundedCornerShape(14.dp)
                        )
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = style.displayName,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = ArArColors.TextPrimary
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = style.sizeLabel,
                                    fontSize = 11.sp,
                                    color = ArArColors.TextMuted,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(ArArColors.Surface3)
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = style.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = ArArColors.TextSecondary
                            )
                        }

                        Spacer(Modifier.width(10.dp))

                        when {
                            isDownloading -> {
                                IconButton(
                                    onClick = onCancelDownload,
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(CircleShape)
                                        .background(ArArColors.Surface3)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Batal",
                                        tint = ArArColors.TextPrimary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            isInstalled -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = "Terpasang",
                                        tint = ArArColors.Success,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    IconButton(
                                        onClick = { onDelete(style) },
                                        enabled = !otherDownloading,
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.DeleteOutline,
                                            contentDescription = "Hapus model",
                                            tint = ArArColors.TextSecondary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                            else -> {
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(100))
                                        .background(
                                            if (otherDownloading) ArArColors.Surface3
                                            else ArArColors.Accent.copy(alpha = 0.22f)
                                        )
                                        .border(
                                            1.dp,
                                            if (otherDownloading) ArArColors.Border else ArArColors.AccentCyan.copy(alpha = 0.5f),
                                            RoundedCornerShape(100)
                                        )
                                        .clickable(enabled = !otherDownloading) { onDownload(style) }
                                        .padding(horizontal = 12.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CloudDownload,
                                        contentDescription = null,
                                        tint = if (otherDownloading) ArArColors.TextMuted else ArArColors.AccentCyan,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(Modifier.width(5.dp))
                                    Text(
                                        text = "Unduh",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (otherDownloading) ArArColors.TextMuted else Color.White
                                    )
                                }
                            }
                        }
                    }

                    if (isDownloading) {
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            LinearProgressIndicator(
                                progress = { state.downloadProgress.coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(5.dp)
                                    .clip(RoundedCornerShape(100)),
                                color = ArArColors.AccentCyan,
                                trackColor = ArArColors.Surface3
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = "${(state.downloadProgress * 100).toInt()}%",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = ArArColors.AccentCyan
                            )
                        }
                    }
                }
            }
        }
    }
}
