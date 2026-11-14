package ir.modiriatsarmaye.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * سیستم طراحی یکپارچه دکمه‌ها (Global Button Design System)
 * تضمین ابعاد استاندارد ۴۸ دی‌پی، شعاع گوشه یکسان، فاصله‌بندی منظم،
 * ترازبندی دقیق فارسی RTL و واکنش‌گرایی در صفحه‌نمایش‌های باریک.
 */
object AppButtonDefaults {
    val StandardHeight: Dp = 48.dp
    val MinTouchTarget: Dp = 48.dp
    val CornerRadius: Dp = 12.dp
    val Shape: RoundedCornerShape = RoundedCornerShape(CornerRadius)
    val IconSize: Dp = 18.dp
    val IconSpacing: Dp = 8.dp
    val ContentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
    val CompactContentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    val PairSpacing: Dp = 10.dp
    val StackedPairBreakpoint: Dp = 270.dp

    @Composable
    fun textStyle(): TextStyle = MaterialTheme.typography.labelLarge.copy(
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun AppButtonContent(
    text: String,
    icon: ImageVector?,
    isLoading: Boolean,
    maxLines: Int
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(AppButtonDefaults.IconSize),
                color = LocalContentColor.current,
                strokeWidth = 2.dp
            )
            Spacer(modifier = Modifier.width(AppButtonDefaults.IconSpacing))
        } else if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(AppButtonDefaults.IconSize)
            )
            Spacer(modifier = Modifier.width(AppButtonDefaults.IconSpacing))
        }

        Text(
            text = text,
            style = AppButtonDefaults.textStyle(),
            textAlign = TextAlign.Center,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun AppPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    isLoading: Boolean = false,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    contentPadding: PaddingValues = AppButtonDefaults.ContentPadding,
    maxLines: Int = 2
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = AppButtonDefaults.Shape,
        colors = colors,
        contentPadding = contentPadding,
        modifier = modifier.defaultMinSize(
            minWidth = AppButtonDefaults.MinTouchTarget,
            minHeight = AppButtonDefaults.StandardHeight
        )
    ) {
        AppButtonContent(
            text = text,
            icon = icon,
            isLoading = isLoading,
            maxLines = maxLines
        )
    }
}

@Composable
fun AppDestructiveButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    isLoading: Boolean = false,
    enabled: Boolean = true,
    contentPadding: PaddingValues = AppButtonDefaults.ContentPadding,
    maxLines: Int = 2
) {
    AppPrimaryButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        icon = icon,
        isLoading = isLoading,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError
        ),
        contentPadding = contentPadding,
        maxLines = maxLines
    )
}

@Composable
fun AppOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    isLoading: Boolean = false,
    enabled: Boolean = true,
    borderColor: Color = MaterialTheme.colorScheme.outline,
    contentColor: Color = MaterialTheme.colorScheme.primary,
    contentPadding: PaddingValues = AppButtonDefaults.ContentPadding,
    maxLines: Int = 2
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = AppButtonDefaults.Shape,
        border = BorderStroke(1.dp, borderColor),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = contentColor),
        contentPadding = contentPadding,
        modifier = modifier.defaultMinSize(
            minWidth = AppButtonDefaults.MinTouchTarget,
            minHeight = AppButtonDefaults.StandardHeight
        )
    ) {
        AppButtonContent(
            text = text,
            icon = icon,
            isLoading = isLoading,
            maxLines = maxLines
        )
    }
}

@Composable
fun AppTonalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    contentPadding: PaddingValues = AppButtonDefaults.ContentPadding,
    maxLines: Int = 2
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        shape = AppButtonDefaults.Shape,
        contentPadding = contentPadding,
        modifier = modifier.defaultMinSize(
            minWidth = AppButtonDefaults.MinTouchTarget,
            minHeight = AppButtonDefaults.StandardHeight
        )
    ) {
        AppButtonContent(
            text = text,
            icon = icon,
            isLoading = false,
            maxLines = maxLines
        )
    }
}

@Composable
fun AppTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    contentColor: Color = MaterialTheme.colorScheme.primary,
    contentPadding: PaddingValues = AppButtonDefaults.CompactContentPadding,
    maxLines: Int = 2
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        shape = AppButtonDefaults.Shape,
        colors = ButtonDefaults.textButtonColors(contentColor = contentColor),
        contentPadding = contentPadding,
        modifier = modifier.defaultMinSize(
            minWidth = AppButtonDefaults.MinTouchTarget,
            minHeight = AppButtonDefaults.StandardHeight
        )
    ) {
        AppButtonContent(
            text = text,
            icon = icon,
            isLoading = false,
            maxLines = maxLines
        )
    }
}

@Composable
fun AppIconActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    enabled: Boolean = true
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(
            minWidth = AppButtonDefaults.MinTouchTarget,
            minHeight = AppButtonDefaults.MinTouchTarget
        )
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint
        )
    }
}

@Composable
fun AppFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    centerLabel: Boolean = false
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        shape = AppButtonDefaults.Shape,
        label = {
            val textStyle = AppButtonDefaults.textStyle().copy(
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
            )
            if (centerLabel) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        style = textStyle,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else {
                Text(
                    text = label,
                    style = textStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        modifier = modifier.defaultMinSize(
            minWidth = AppButtonDefaults.MinTouchTarget,
            minHeight = AppButtonDefaults.StandardHeight
        )
    )
}

/**
 * چیدمان جفت دکمه هم‌عرض و هم‌ارتفاع برای عملیات‌های هم‌ارزش.
 * در صفحه‌نمایش‌های بسیار باریک (زیر ۲۷۰ دی‌پی عرض داخلی)، دکمه‌ها به‌صورت عمودی
 * با عرض کامل و مساوی روی هم چیده می‌شوند تا متن فارسی قطع نشود.
 */
@Composable
fun AppActionPair(
    modifier: Modifier = Modifier,
    spacing: Dp = AppButtonDefaults.PairSpacing,
    stackedBreakpoint: Dp = AppButtonDefaults.StackedPairBreakpoint,
    firstButton: @Composable (Modifier) -> Unit,
    secondButton: @Composable (Modifier) -> Unit
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        if (maxWidth < stackedBreakpoint) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(spacing)
            ) {
                firstButton(
                    Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = AppButtonDefaults.StandardHeight)
                )
                secondButton(
                    Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = AppButtonDefaults.StandardHeight)
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(spacing)
            ) {
                firstButton(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .defaultMinSize(minHeight = AppButtonDefaults.StandardHeight)
                )
                secondButton(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .defaultMinSize(minHeight = AppButtonDefaults.StandardHeight)
                )
            }
        }
    }
}

/**
 * جفت دکمه‌های استاندارد دیالوگ‌ها (تایید و انصراف) با ارتفاع ۴۸ دی‌پی و عرض یکسان.
 */
@Composable
fun AppDialogActionPair(
    confirmText: String,
    onConfirm: () -> Unit,
    dismissText: String = "انصراف",
    onDismiss: () -> Unit,
    isDestructive: Boolean = false,
    confirmTestTag: String? = null,
    dismissTestTag: String? = null
) {
    AppActionPair(
        firstButton = { itemModifier ->
            val mod = if (confirmTestTag != null) itemModifier.testTag(confirmTestTag) else itemModifier
            if (isDestructive) {
                AppDestructiveButton(
                    text = confirmText,
                    onClick = onConfirm,
                    modifier = mod
                )
            } else {
                AppPrimaryButton(
                    text = confirmText,
                    onClick = onConfirm,
                    modifier = mod
                )
            }
        },
        secondButton = { itemModifier ->
            val mod = if (dismissTestTag != null) itemModifier.testTag(dismissTestTag) else itemModifier
            AppOutlinedButton(
                text = dismissText,
                onClick = onDismiss,
                modifier = mod
            )
        }
    )
}

/**
 * نوار دکمه‌های ویرایش و حذف یکپارچه برای کارت‌های تراکنش در سراسر برنامه.
 */
@Composable
fun TransactionCardActionRow(
    editLabel: String,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    editTestTag: String,
    deleteTestTag: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppTextButton(
            text = editLabel,
            onClick = onEdit,
            icon = Icons.Default.Edit,
            modifier = Modifier.testTag(editTestTag)
        )

        Spacer(modifier = Modifier.width(8.dp))

        AppIconActionButton(
            icon = AppIcons.DeleteOutline,
            contentDescription = "حذف تراکنش",
            onClick = onDelete,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(deleteTestTag)
        )
    }
}
