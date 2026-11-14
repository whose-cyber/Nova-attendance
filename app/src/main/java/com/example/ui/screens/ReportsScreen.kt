package com.example.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FilterAltOff
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.export.ReportsCsvExporter
import com.example.data.model.AttendanceSession
import com.example.data.model.Classroom
import com.example.data.model.Student
import com.example.data.model.StudentReportItem
import com.example.ui.components.LowAttendanceWarningBadge
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.viewmodel.AttendanceActionState
import com.example.ui.viewmodel.ReportDateFilter
import com.example.ui.viewmodel.ReportsSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReportsScreen(
    classroom: Classroom,
    students: List<Student>,
    reportsSummary: ReportsSummary,
    actionState: AttendanceActionState,
    onSelectSubjectFilter: (String?) -> Unit,
    onSelectDateFilter: (ReportDateFilter) -> Unit,
    onSelectCustomDate: (String) -> Unit,
    onClearFilters: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Student %, 1 = Session Logs
    var showDatePicker by remember { mutableStateOf(false) }
    var exportStatusMessage by remember { mutableStateOf<String?>(null) }

    val activeSubjectLabel = actionState.reportSubjectFilter ?: "All Subjects"
    val activeDateLabel = when (actionState.reportDateFilter) {
        ReportDateFilter.ALL -> "All Dates"
        ReportDateFilter.TODAY -> "Today"
        ReportDateFilter.LAST_7_DAYS -> "Last 7 Days"
        ReportDateFilter.CUSTOM_DATE -> actionState.reportCustomDateIso
    }

    val createCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            runCatching {
                val csv = ReportsCsvExporter.buildCsvContent(
                    schoolId = classroom.schoolId,
                    classroomName = classroom.name,
                    subjectFilterLabel = activeSubjectLabel,
                    dateFilterLabel = activeDateLabel,
                    studentReports = reportsSummary.studentReports
                )
                ReportsCsvExporter.writeCsvToDocumentUri(context.contentResolver, uri, csv)
            }.onSuccess {
                val msg = "Exported ${reportsSummary.studentReports.size} student records to CSV."
                exportStatusMessage = msg
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }.onFailure { err ->
                val msg = err.localizedMessage ?: "Failed to save CSV report."
                exportStatusMessage = msg
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    if (showDatePicker) {
        val initialMillis = remember(actionState.reportCustomDateIso) {
            runCatching {
                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                sdf.parse(actionState.reportCustomDateIso)?.time
            }.getOrNull()
        }
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val selectedMillis = datePickerState.selectedDateMillis
                        if (selectedMillis != null) {
                            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                                timeZone = TimeZone.getTimeZone("UTC")
                            }
                            onSelectCustomDate(sdf.format(Date(selectedMillis)))
                        }
                        showDatePicker = false
                    }
                ) { Text("Apply Date") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    val studentNameById = remember(students) {
        students.associate { it.id to "${it.rollNumber} · ${it.name}" }
    }
    val hasActiveFilter = actionState.reportSubjectFilter != null ||
        actionState.reportDateFilter != ReportDateFilter.ALL

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth()) {
                Text(
                    text = "Attendance Reports",
                    style = MaterialTheme.typography.headlineMedium
                )
                Text(
                    text = "Review consolidated percentages, filter session logs, and export CSV reports.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Export Report Actions (Download CSV + Share CSV)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            val fileName = ReportsCsvExporter.buildReportFileName(
                                schoolId = classroom.schoolId,
                                classroomName = classroom.name,
                                subjectFilter = actionState.reportSubjectFilter,
                                dateFilterLabel = activeDateLabel
                            )
                            createCsvLauncher.launch(fileName)
                        },
                        enabled = reportsSummary.studentReports.isNotEmpty(),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("export_csv_button")
                    ) {
                        Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.export_csv_cta))
                    }

                    OutlinedButton(
                        onClick = {
                            runCatching {
                                val fileName = ReportsCsvExporter.buildReportFileName(
                                    schoolId = classroom.schoolId,
                                    classroomName = classroom.name,
                                    subjectFilter = actionState.reportSubjectFilter,
                                    dateFilterLabel = activeDateLabel
                                )
                                val csv = ReportsCsvExporter.buildCsvContent(
                                    schoolId = classroom.schoolId,
                                    classroomName = classroom.name,
                                    subjectFilterLabel = activeSubjectLabel,
                                    dateFilterLabel = activeDateLabel,
                                    studentReports = reportsSummary.studentReports
                                )
                                val shareUri = ReportsCsvExporter.writeCsvToShareUri(
                                    context = context,
                                    fileName = fileName,
                                    csvContent = csv
                                )
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/csv"
                                    putExtra(Intent.EXTRA_STREAM, shareUri)
                                    putExtra(
                                        Intent.EXTRA_SUBJECT,
                                        "Attendance Report - ${classroom.name} ($activeSubjectLabel · $activeDateLabel)"
                                    )
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(
                                    Intent.createChooser(shareIntent, "Share Attendance CSV Report")
                                )
                            }.onSuccess {
                                exportStatusMessage = "Prepared CSV report for sharing."
                            }.onFailure { err ->
                                val msg = err.localizedMessage ?: "Unable to share CSV report."
                                exportStatusMessage = msg
                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            }
                        },
                        enabled = reportsSummary.studentReports.isNotEmpty(),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("share_csv_button")
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.share_csv_cta))
                    }
                }
            }
        }

        if (!exportStatusMessage.isNullOrBlank()) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = exportStatusMessage!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { exportStatusMessage = null }) { Text("OK") }
                    }
                }
            }
        }

        // Date-Wise & Subject Filter Card
        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Filter by Subject", style = MaterialTheme.typography.labelLarge)
                        if (hasActiveFilter) {
                            TextButton(
                                onClick = onClearFilters,
                                modifier = Modifier.testTag("clear_report_filters_button")
                            ) {
                                Icon(Icons.Filled.FilterAltOff, null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Reset")
                            }
                        }
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            FilterChip(
                                selected = actionState.reportSubjectFilter == null,
                                onClick = { onSelectSubjectFilter(null) },
                                label = { Text("All Subjects") },
                                modifier = Modifier.testTag("filter_subject_all")
                            )
                        }
                        val subjectChoices = reportsSummary.availableSubjects.ifEmpty { classroom.subjects }
                        items(subjectChoices) { subject ->
                            FilterChip(
                                selected = actionState.reportSubjectFilter == subject,
                                onClick = { onSelectSubjectFilter(subject) },
                                label = { Text(subject) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text("Filter by Date", style = MaterialTheme.typography.labelLarge)
                    Spacer(modifier = Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        FilterChip(
                            selected = actionState.reportDateFilter == ReportDateFilter.TODAY,
                            onClick = { onSelectDateFilter(ReportDateFilter.TODAY) },
                            label = { Text(ReportDateFilter.TODAY.label) },
                            modifier = Modifier.testTag("filter_date_today")
                        )
                        FilterChip(
                            selected = actionState.reportDateFilter == ReportDateFilter.LAST_7_DAYS,
                            onClick = { onSelectDateFilter(ReportDateFilter.LAST_7_DAYS) },
                            label = { Text(ReportDateFilter.LAST_7_DAYS.label) },
                            modifier = Modifier.testTag("filter_date_7_days")
                        )
                        FilterChip(
                            selected = actionState.reportDateFilter == ReportDateFilter.ALL,
                            onClick = { onSelectDateFilter(ReportDateFilter.ALL) },
                            label = { Text(ReportDateFilter.ALL.label) },
                            modifier = Modifier.testTag("filter_date_all")
                        )
                        FilterChip(
                            selected = actionState.reportDateFilter == ReportDateFilter.CUSTOM_DATE,
                            onClick = { showDatePicker = true },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.CalendarToday,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            label = {
                                Text(
                                    if (actionState.reportDateFilter == ReportDateFilter.CUSTOM_DATE) {
                                        actionState.reportCustomDateIso
                                    } else {
                                        "Pick Date"
                                    }
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer
                            ),
                            modifier = Modifier.testTag("filter_date_custom")
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "${classroom.schoolId} · Showing ${reportsSummary.filteredSessions.size} session(s) · $activeSubjectLabel · $activeDateLabel",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Low Attendance Warning Banner (Report Summary Highlight)
        if (reportsSummary.atRiskCount > 0) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f)),
                    modifier = Modifier
                        .widthIn(max = 680.dp)
                        .fillMaxWidth()
                        .testTag("low_attendance_summary_banner")
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Warning,
                            contentDescription = "Low attendance alert",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "! Low Attendance Alert (${reportsSummary.atRiskCount} Student${if (reportsSummary.atRiskCount == 1) "" else "s"})",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = "Students below 75% in the selected view are highlighted with a warning badge.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }
        }

        // KPI Summary Row
        item {
            Row(
                modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ReportKpiCard(
                    label = "Class Avg",
                    value = if (reportsSummary.filteredSessions.isEmpty()) "—" else "${reportsSummary.averageAttendancePercent.toInt()}%",
                    modifier = Modifier.weight(1f)
                )
                ReportKpiCard(
                    label = "Sessions",
                    value = reportsSummary.filteredSessions.size.toString(),
                    modifier = Modifier.weight(1f)
                )
                ReportKpiCard(
                    label = "! Below 75%",
                    value = reportsSummary.atRiskCount.toString(),
                    isAlert = reportsSummary.atRiskCount > 0,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // View Toggle
        item {
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth()
            ) {
                SegmentedButton(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                ) {
                    Text("Student Percentages")
                }
                SegmentedButton(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                ) {
                    Text("Session History (${reportsSummary.filteredSessions.size})")
                }
            }
        }

        if (selectedTab == 0) {
            if (reportsSummary.studentReports.isEmpty()) {
                item {
                    EmptyReportCard("No enrolled students to report yet.")
                }
            } else {
                items(reportsSummary.studentReports, key = { it.student.id }) { item ->
                    StudentReportRow(
                        report = item,
                        modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth()
                    )
                }
            }
        } else {
            if (reportsSummary.filteredSessions.isEmpty()) {
                item {
                    EmptyReportCard("No lecture sessions match \"$activeSubjectLabel\" for \"$activeDateLabel\".")
                }
            } else {
                items(reportsSummary.filteredSessions, key = { it.id }) { session ->
                    SessionLogCard(
                        session = session,
                        studentNameById = studentNameById,
                        modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth()
                    )
                }
            }
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

@Composable
private fun ReportKpiCard(
    label: String,
    value: String,
    isAlert: Boolean = false,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isAlert) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface
        ),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall.copy(fontFamily = JetBrainsMonoFamily),
                color = if (isAlert) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = if (isAlert) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StudentReportRow(
    report: StudentReportItem,
    modifier: Modifier = Modifier
) {
    val hasSessions = report.totalSessionsMarked > 0
    val isLowAttendance = hasSessions && report.attendancePercentage < 75f
    val statusColor = if (!isLowAttendance) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isLowAttendance) {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = if (isLowAttendance) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f))
        } else {
            null
        },
        modifier = modifier.testTag("report_student_${report.student.rollNumber}")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = report.student.rollNumber,
                        style = MaterialTheme.typography.labelLarge.copy(fontFamily = JetBrainsMonoFamily),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = report.student.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (hasSessions) {
                            "${report.presentCount} Present · ${report.absentCount} Absent (${report.totalSessionsMarked} lectures)"
                        } else {
                            "No attendance sessions recorded yet"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (isLowAttendance) {
                    LowAttendanceWarningBadge(
                        percentage = report.attendancePercentage,
                        modifier = Modifier.testTag("low_attendance_badge_${report.student.rollNumber}")
                    )
                } else {
                    Surface(
                        color = statusColor.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(50)
                    ) {
                        Text(
                            text = if (hasSessions) "${report.attendancePercentage.toInt()}%" else "N/A",
                            style = MaterialTheme.typography.labelLarge.copy(fontFamily = JetBrainsMonoFamily),
                            color = statusColor,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
            }
            if (hasSessions) {
                Spacer(modifier = Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { (report.attendancePercentage / 100f).coerceIn(0f, 1f) },
                    color = statusColor,
                    modifier = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(50))
                )
            }
        }
    }
}

@Composable
private fun SessionLogCard(
    session: AttendanceSession,
    studentNameById: Map<String, String>,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val present = session.records.values.count { it == "P" }
    val absent = session.records.values.count { it == "A" }
    val total = present + absent
    val pct = if (total > 0) (present * 100) / total else 0

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = session.subject,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = session.dateIso,
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFamily),
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse session details" else "Expand session details",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${session.timing} · Marked by ${session.markedByName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Present: $present",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.tertiary
                )
                Text(
                    text = "Absent: $absent",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error
                )
                if (total > 0) {
                    Text(
                        text = "$pct%",
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFamily),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            AnimatedVisibility(visible = expanded && session.records.isNotEmpty()) {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                    Spacer(modifier = Modifier.height(8.dp))
                    val sortedEntries = session.records.entries.sortedBy { studentNameById[it.key] ?: it.key }
                    sortedEntries.forEach { (studentId, status) ->
                        val displayName = studentNameById[studentId] ?: studentId
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = displayName,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = if (status == "P") "Present" else "Absent",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (status == "P") {
                                    MaterialTheme.colorScheme.tertiary
                                } else {
                                    MaterialTheme.colorScheme.error
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyReportCard(message: String) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth()
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(24.dp)
        )
    }
}
