package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.FileCategory
import com.example.model.TransferItem
import com.example.ui.theme.LinkoCyan
import com.example.ui.theme.LinkoMint
import com.example.ui.theme.LinkoPurple

@Composable
fun FileItemRow(
    item: TransferItem,
    modifier: Modifier = Modifier,
    isSelectable: Boolean = true,
    isSelected: Boolean = false,
    showCompletedStatus: Boolean = false,
    onToggleSelect: () -> Unit = {}
) {
    val (icon, tintColor, bgTint) = when (item.category) {
        FileCategory.APPS -> Triple(Icons.Rounded.Android, LinkoMint, LinkoMint.copy(alpha = 0.15f))
        FileCategory.VIDEOS -> Triple(Icons.Rounded.Videocam, Color(0xFFFF5252), Color(0xFFFF5252).copy(alpha = 0.15f))
        FileCategory.IMAGES -> Triple(Icons.Rounded.Image, LinkoCyan, LinkoCyan.copy(alpha = 0.15f))
        FileCategory.AUDIO -> Triple(Icons.Rounded.AudioFile, LinkoPurple, LinkoPurple.copy(alpha = 0.15f))
        FileCategory.ARCHIVES -> Triple(Icons.Rounded.FolderZip, Color(0xFFFFAB00), Color(0xFFFFAB00).copy(alpha = 0.15f))
        FileCategory.DOCUMENTS -> Triple(Icons.Rounded.Description, Color(0xFF448AFF), Color(0xFF448AFF).copy(alpha = 0.15f))
        FileCategory.ALL -> Triple(Icons.Rounded.InsertDriveFile, Color.White, Color.White.copy(alpha = 0.15f))
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = isSelectable, onClick = onToggleSelect)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Icon badge
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(bgTint),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = item.category.titleAr,
                tint = tintColor,
                modifier = Modifier.size(26.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Name and size
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.formattedSize,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                )
                Text(
                    text = " • ${item.category.titleAr}",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = tintColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                )
            }
        }

        // Selection or completion check
        if (showCompletedStatus) {
            Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = "تم النقل",
                tint = LinkoMint,
                modifier = Modifier.size(24.dp)
            )
        } else if (isSelectable) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggleSelect() },
                colors = CheckboxDefaults.colors(
                    checkedColor = LinkoCyan,
                    checkmarkColor = Color(0xFF080D1A)
                ),
                modifier = Modifier.testTag("select_item_${item.id}")
            )
        }
    }
}
