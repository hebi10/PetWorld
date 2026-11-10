package com.haebi.petworld.ui.screens

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.haebi.petworld.ui.components.PetSecondaryButton
import com.haebi.petworld.ui.theme.PetSpacing
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val observationDateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val observationTimeFormat = DateTimeFormatter.ofPattern("HH:mm")

@Composable
internal fun CareObservationDateTime(
    occurredAt: Long,
    zone: ZoneId,
    onOccurredAtChange: (Long) -> Unit,
) {
    val context = LocalContext.current
    val local = Instant.ofEpochMilli(occurredAt).atZone(zone)
    val dateLabel = local.format(observationDateFormat)
    val timeLabel = local.format(observationTimeFormat)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(PetSpacing.small),
    ) {
        Text("관찰 시각", style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(PetSpacing.small),
        ) {
            PetSecondaryButton(
                label = dateLabel,
                modifier = Modifier.weight(1.65f).semantics {
                    contentDescription = "관찰 날짜 $dateLabel 선택"
                },
                onClick = {
                    val day = local.toLocalDate()
                    DatePickerDialog(
                        context,
                        { _, year, month, dayOfMonth ->
                            val datetime = LocalDateTime.of(
                                LocalDate.of(year, month + 1, dayOfMonth),
                                local.toLocalTime(),
                            )
                            onOccurredAtChange(datetime.atZone(zone).toInstant().toEpochMilli())
                        },
                        day.year, day.monthValue - 1, day.dayOfMonth,
                    ).apply { datePicker.maxDate = System.currentTimeMillis() }.show()
                },
            )
            PetSecondaryButton(
                label = timeLabel,
                modifier = Modifier.weight(1f).semantics {
                    contentDescription = "관찰 시간 $timeLabel 선택"
                },
                onClick = {
                    TimePickerDialog(
                        context,
                        { _, hour, minute ->
                            val datetime = local.toLocalDate().atTime(hour, minute)
                            onOccurredAtChange(datetime.atZone(zone).toInstant().toEpochMilli())
                        },
                        local.hour, local.minute, true,
                    ).show()
                },
            )
        }
    }
}
