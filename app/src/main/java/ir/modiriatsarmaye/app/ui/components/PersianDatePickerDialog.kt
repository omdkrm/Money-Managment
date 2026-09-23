package ir.modiriatsarmaye.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ir.modiriatsarmaye.app.util.JalaliDate
import ir.modiriatsarmaye.app.util.PersianUtils

/**
 * مؤلفه تقویم بصری و انتخاب‌گر تاریخ شمسی (Visual Persian / Jalali Date Picker)
 * بدون هرگونه لغزش زمانی یا وابستگی به ساعت، با رعایت کامل اصول دسترس‌پذیری و RTL
 */
@Composable
fun PersianDatePickerDialog(
    initialDate: JalaliDate = JalaliDate.now(),
    onDismissRequest: () -> Unit,
    onDateSelected: (JalaliDate) -> Unit
) {
    val today = remember { JalaliDate.now() }
    var displayedYear by remember { mutableIntStateOf(initialDate.year) }
    var displayedMonth by remember { mutableIntStateOf(initialDate.month) }
    var selectedDate by remember { mutableStateOf(initialDate) }

    var showYearMonthPicker by remember { mutableStateOf(false) }

    val daysInMonth = remember(displayedYear, displayedMonth) {
        JalaliDate.getDaysInMonth(displayedYear, displayedMonth)
    }

    val firstDayOfWeekIndex = remember(displayedYear, displayedMonth) {
        JalaliDate(displayedYear, displayedMonth, 1).dayOfWeekIndex
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 420.dp)
                .wrapContentHeight()
                .testTag("persian_date_picker_dialog"),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // هدر بالای دیالوگ: عنوان و تاریخ انتخاب‌شده
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "انتخاب تاریخ شمسی",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    TextButton(
                        onClick = {
                            displayedYear = today.year
                            displayedMonth = today.month
                            selectedDate = today
                        },
                        modifier = Modifier.testTag("date_picker_today_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.CalendarToday,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("امروز", style = MaterialTheme.typography.labelMedium)
                    }
                }

                // جعبه نمایش تاریخ کامل انتخاب شده
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(
                            text = "${selectedDate.dayOfWeekNameFa}، ${selectedDate.longDisplayString}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = selectedDate.displayString,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // نوار جابجایی ماه و سال
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            if (displayedMonth == 12) {
                                displayedYear++
                                displayedMonth = 1
                            } else {
                                displayedMonth++
                            }
                        },
                        modifier = Modifier.testTag("date_picker_next_month")
                    ) {
                        // در چیدمان RTL جهت رو به جلو به سمت راست است
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "ماه بعد"
                        )
                    }

                    // کلید باز کردن انتخاب سریع ماه و سال
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { showYearMonthPicker = !showYearMonthPicker }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        color = if (showYearMonthPicker) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
                    ) {
                        val monthName = PersianUtils.persianMonths.getOrElse(displayedMonth - 1) { "" }
                        Text(
                            text = "$monthName ${PersianUtils.toPersianDigits(displayedYear.toString())}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    IconButton(
                        onClick = {
                            if (displayedMonth == 1) {
                                displayedYear--
                                displayedMonth = 12
                            } else {
                                displayedMonth--
                            }
                        },
                        modifier = Modifier.testTag("date_picker_prev_month")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "ماه قبل"
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                if (showYearMonthPicker) {
                    // نمای انتخاب سریع ماه و سال
                    YearMonthSelector(
                        selectedYear = displayedYear,
                        selectedMonth = displayedMonth,
                        onSelect = { y, m ->
                            displayedYear = y
                            displayedMonth = m
                            showYearMonthPicker = false
                        }
                    )
                } else {
                    // سطر عناوین روزهای هفته فارسی (شنبه تا جمعه)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        JalaliDate.PERSIAN_WEEK_DAYS_SHORT.forEachIndexed { index, name ->
                            val isFriday = index == 6
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = name,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isFriday) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)

                    // شبکه روزهای ماه
                    val totalCells = firstDayOfWeekIndex + daysInMonth
                    val rows = (totalCells + 6) / 7

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        for (r in 0 until rows) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceAround
                            ) {
                                for (c in 0 until 7) {
                                    val cellIndex = r * 7 + c
                                    val dayNumber = cellIndex - firstDayOfWeekIndex + 1

                                    if (dayNumber in 1..daysInMonth) {
                                        val isSelected = selectedDate.year == displayedYear &&
                                                selectedDate.month == displayedMonth &&
                                                selectedDate.day == dayNumber
                                        val isToday = today.year == displayedYear &&
                                                today.month == displayedMonth &&
                                                today.day == dayNumber
                                        val isFriday = c == 6

                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .aspectRatio(1f)
                                                .padding(2.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    when {
                                                        isSelected -> MaterialTheme.colorScheme.primary
                                                        isToday -> MaterialTheme.colorScheme.surfaceVariant
                                                        else -> Color.Transparent
                                                    }
                                                )
                                                .then(
                                                    if (isToday && !isSelected) {
                                                        Modifier.border(1.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                                    } else Modifier
                                                )
                                                .clickable {
                                                    selectedDate = JalaliDate(displayedYear, displayedMonth, dayNumber)
                                                }
                                                .testTag("date_picker_day_$dayNumber"),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = PersianUtils.toPersianDigits(dayNumber.toString()),
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = if (isSelected || isToday) FontWeight.Bold else FontWeight.Normal,
                                                color = when {
                                                    isSelected -> MaterialTheme.colorScheme.onPrimary
                                                    isFriday -> MaterialTheme.colorScheme.error
                                                    else -> MaterialTheme.colorScheme.onSurface
                                                }
                                            )
                                        }
                                    } else {
                                        // سلول خالی برای آفست شروع ماه یا انتهای ماه
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .aspectRatio(1f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // دکمه‌های تأیید و انصراف با دسترسی‌پذیری و حداقل ۴۸ دی‌پی
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onDismissRequest,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("date_picker_cancel_button")
                    ) {
                        Text("انصراف", style = MaterialTheme.typography.labelLarge)
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = {
                            onDateSelected(selectedDate)
                            onDismissRequest()
                        },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("date_picker_confirm_button"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("تأیید تاریخ", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/**
 * نمای انتخاب سریع ماه و سال
 */
@Composable
private fun YearMonthSelector(
    selectedYear: Int,
    selectedMonth: Int,
    onSelect: (year: Int, month: Int) -> Unit
) {
    var tempYear by remember { mutableIntStateOf(selectedYear) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 280.dp)
    ) {
        // تغییر سال با دکمه‌های افزایشی و کاهشی
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { tempYear++ }) {
                Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "سال بعد")
            }
            Text(
                text = PersianUtils.toPersianDigits(tempYear.toString()),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            IconButton(onClick = { tempYear-- }) {
                Icon(imageVector = Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "سال قبل")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // شبکه ۱۲ ماه سال
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(12) { index ->
                val month = index + 1
                val isSelected = (month == selectedMonth)
                val monthName = PersianUtils.persianMonths[index]

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onSelect(tempYear, month) },
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = monthName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}
