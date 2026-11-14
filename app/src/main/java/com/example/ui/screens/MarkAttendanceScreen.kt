package com.example.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.AttendanceSession
import com.example.data.model.Classroom
import com.example.data.model.Student
import com.example.data.model.StudentReportItem
import com.example.data.model.UserRole
import com.example.ui.components.LowAttendanceWarningBadge
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.viewmodel.AttendanceActionState
import com.example.ui.viewmodel.AttendanceViewModel

@Composable
fun MarkAttendanceScreen(
    classroom: Classroom,
    effectiveRole: UserRole,
    students: List<Student>,
    overallStudentReports: Map<String, StudentReportItem>,
    sessionsCountBySubject: Map<String, Int>,
    activeSession: AttendanceSession?,
    actionState: AttendanceActionState,
    onSelectSubject: (String) -> Unit,
    onSelectTiming: (String) -> Unit,
    onSelectDateIso: (String) -> Unit,
    onAddCustomSubject: (String) -> Unit,
    onRemoveSubject: (String) -> Unit,
    onMarkAttendance: (String, String) -> Unit,
    onClearError: () -> Unit,
    onGoToUpload: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showAddSubjectDialog by remember { mutableStateOf(false) }
    var customSubjectInput by remember { mutableStateOf("") }
    var subjectPendingDeletion by remember { mutableStateOf<String?>(null) }

    val records = activeSession?.records.orEmpty()
    val presentCount = students.count { records[it.id] == "P" }
    val absentCount = students.count { records[it.id] == "A" }
    val markedCount = presentCount + absentCount
    val progress = if (students.isNotEmpty()) markedCount.toFloat() / students.size.toFloat() else 0f

    val dateOptions = remember {
        listOf(
            "Today" to AttendanceViewModel.currentDateIso(),
            "Yesterday" to AttendanceViewModel.dateDaysAgoIso(1)
        )
    }

    if (showAddSubjectDialog) {
        AlertDialog(
            onDismissRequest = { showAddSubjectDialog = false },
            title = { Text("Add Subject") },
            text = {
                OutlinedTextField(
                    value = customSubjectInput,
                    onValueChange = { customSubjectInput = it },
                    label = { Text("Subject Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (customSubjectInput.isNotBlank()) {
                            onAddCustomSubject(customSubjectInput)
                            customSubjectInput = ""
                            showAddSubjectDialog = false
                        }
                    }
                ) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { showAddSubjectDialog = false }) { Text("Cancel") }
            }
        )
    }

    subjectPendingDeletion?.let { targetSubject ->
        val sessionCount = sessionsCountBySubject[targetSubject] ?: 0
        val isLastSubject = classroom.subjects.size <= 1
        AlertDialog(
            onDismissRequest = { subjectPendingDeletion = null },
            title = { Text(stringResource(R.string.remove_subject_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Remove \"$targetSubject\" from ${classroom.name}'s subject list?",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = if (sessionCount > 0) {
                            "$sessionCount recorded attendance session(s) exist for this subject. Historical attendance logs and reports will be preserved."
                        } else {
                            "No attendance sessions have been recorded for this subject yet."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (isLastSubject) {
                        Text(
                            text = "This is the last subject currently configured. You can add another subject at any time.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRemoveSubject(targetSubject)
                        subjectPendingDeletion = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier.testTag("confirm_delete_subject_button")
                ) {
                    Text(stringResource(R.string.remove_subject_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { subjectPendingDeletion = null },
                    modifier = Modifier.testTag("cancel_delete_subject_button")
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Column(modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth()) {
                Text(
                    text = "Mark Today's Attendance",
                    style = MaterialTheme.typography.headlineMedium
                )
                Text(
                    text = "Select Subject & Lecture Timing. Every P / A tap writes immediately to Firestore.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (!actionState.attendanceError.isNullOrBlank()) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = actionState.attendanceError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onClearError) { Text("Dismiss") }
                    }
                }
            }
        }

        // Session Selector Card (Date, Subject, Timing)
        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Date Selector
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.CalendarToday,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Date", style = MaterialTheme.typography.labelLarge)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        dateOptions.forEach { (label, iso) ->
                            FilterChip(
                                selected = actionState.selectedDateIso == iso,
                                onClick = { onSelectDateIso(iso) },
                                label = { Text("$label ($iso)") }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Subject Selector
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.AutoMirrored.Filled.MenuBook,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Subject", style = MaterialTheme.typography.labelLarge)
                        }
                        if (effectiveRole == UserRole.COORDINATOR) {
                            TextButton(onClick = { showAddSubjectDialog = true }) {
                                Icon(Icons.Filled.Add, null, Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Add Subject")
                            }
                        }
                    }
                    if (classroom.subjects.isEmpty()) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = if (effectiveRole == UserRole.COORDINATOR) {
                                    "No subjects added yet. Tap \"+ Add Subject\" above to create one."
                                } else {
                                    "No subjects configured. Ask your Class Coordinator to add a subject."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                            )
                        }
                    } else {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(classroom.subjects, key = { it }) { subject ->
                                val isSelected = actionState.selectedSubject == subject
                                val isBeingDeleted = actionState.deletingSubject == subject
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { onSelectSubject(subject) },
                                    enabled = !isBeingDeleted,
                                    label = { Text(subject) },
                                    trailingIcon = if (effectiveRole == UserRole.COORDINATOR) {
                                        {
                                            if (isBeingDeleted) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(14.dp),
                                                    strokeWidth = 2.dp
                                                )
                                            } else {
                                                Surface(
                                                    color = if (isSelected) {
                                                        MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.14f)
                                                    } else {
                                                        MaterialTheme.colorScheme.surfaceVariant
                                                    },
                                                    shape = CircleShape,
                                                    modifier = Modifier
                                                        .size(24.dp)
                                                        .clip(CircleShape)
                                                        .clickable(
                                                            enabled = actionState.deletingSubject == null,
                                                            onClickLabel = "Remove subject $subject"
                                                        ) {
                                                            subjectPendingDeletion = subject
                                                        }
                                                        .testTag("delete_subject_$subject")
                                                ) {
                                                    Box(contentAlignment = Alignment.Center) {
                                                        Icon(
                                                            imageVector = Icons.Filled.Close,
                                                            contentDescription = "Delete subject $subject",
                                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            modifier = Modifier.size(14.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        null
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Timing Selector
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Schedule,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Lecture Timing", style = MaterialTheme.typography.labelLarge)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(classroom.timings) { timing ->
                            FilterChip(
                                selected = actionState.selectedTiming == timing,
                                onClick = { onSelectTiming(timing) },
                                label = {
                                    Text(
                                        text = timing,
                                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFamily)
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer
                                )
                            )
                        }
                    }
                }
            }
        }

        // Real-Time Summary Bar
        item {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)),
                modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            StatusCountPill("Present", presentCount, MaterialTheme.colorScheme.tertiary)
                            StatusCountPill("Absent", absentCount, MaterialTheme.colorScheme.error)
                            StatusCountPill(
                                "Pending",
                                (students.size - markedCount).coerceAtLeast(0),
                                MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = "$markedCount/${students.size}",
                            style = MaterialTheme.typography.labelLarge.copy(fontFamily = JetBrainsMonoFamily)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(50))
                    )
                    if (activeSession != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Sync,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Last synced by ${activeSession.markedByName}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        if (students.isEmpty()) {
            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "No students in roster yet",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (effectiveRole == UserRole.COORDINATOR) {
                                "Upload an Excel/CSV file or import the sample roster to begin marking attendance."
                            } else {
                                "Ask your Class Coordinator to upload the student roster. It will appear here automatically."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (effectiveRole == UserRole.COORDINATOR) {
                            Spacer(modifier = Modifier.height(14.dp))
                            Button(onClick = onGoToUpload) {
                                Text("Go to Upload Roster")
                            }
                        }
                    }
                }
            }
        } else {
            items(students, key = { it.id }) { student ->
                val currentStatus = records[student.id]
                val isSaving = actionState.savingStudentIds.contains(student.id)
                val overallReport = overallStudentReports[student.id]

                StudentAttendanceRow(
                    student = student,
                    overallReport = overallReport,
                    currentStatus = currentStatus,
                    isSaving = isSaving,
                    canMark = actionState.selectedSubject.isNotBlank(),
                    onMarkPresent = { onMarkAttendance(student.id, "P") },
                    onMarkAbsent = { onMarkAttendance(student.id, "A") },
                    modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth()
                )
            }
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

@Composable
private fun StatusCountPill(label: String, count: Int, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = "$label: $count",
            style = MaterialTheme.typography.labelMedium,
            color = color
        )
    }
}

@Composable
private fun StudentAttendanceRow(
    student: Student,
    overallReport: StudentReportItem?,
    currentStatus: String?,
    isSaving: Boolean,
    canMark: Boolean,
    onMarkPresent: () -> Unit,
    onMarkAbsent: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isPresent = currentStatus == "P"
    val isAbsent = currentStatus == "A"
    val isLowAttendance = overallReport != null &&
        overallReport.totalSessionsMarked > 0 &&
        overallReport.attendancePercentage < 75f

    val presentContainer by animateColorAsState(
        targetValue = if (isPresent) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.surfaceVariant,
        label = "presentColor"
    )
    val presentContent by animateColorAsState(
        targetValue = if (isPresent) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "presentTextColor"
    )
    val absentContainer by animateColorAsState(
        targetValue = if (isAbsent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.surfaceVariant,
        label = "absentColor"
    )
    val absentContent by animateColorAsState(
        targetValue = if (isAbsent) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "absentTextColor"
    )

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = if (isLowAttendance) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f))
        } else {
            null
        },
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    text = student.rollNumber,
                    style = MaterialTheme.typography.labelLarge.copy(fontFamily = JetBrainsMonoFamily),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = student.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = when (currentStatus) {
                        "P" -> "Marked Present"
                        "A" -> "Marked Absent"
                        else -> "Not marked yet"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = when (currentStatus) {
                        "P" -> MaterialTheme.colorScheme.tertiary
                        "A" -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                if (isLowAttendance && overallReport != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    LowAttendanceWarningBadge(
                        percentage = overallReport.attendancePercentage,
                        compact = true,
                        modifier = Modifier.testTag("mark_low_badge_${student.rollNumber}")
                    )
                }
            }

            if (isSaving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp).padding(end = 6.dp),
                    strokeWidth = 2.5.dp
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onMarkPresent,
                    enabled = !isSaving && canMark,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = presentContainer,
                        contentColor = presentContent
                    ),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("mark_present_${student.rollNumber}")
                ) {
                    if (isPresent) {
                        Icon(Icons.Filled.Check, null, Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text("P", fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = onMarkAbsent,
                    enabled = !isSaving && canMark,
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = absentContainer,
                        contentColor = absentContent
                    ),
                    border = null,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("mark_absent_${student.rollNumber}")
                ) {
                    if (isAbsent) {
                        Icon(Icons.Filled.Close, null, Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text("A", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
