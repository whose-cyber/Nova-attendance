package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.AttendanceSession
import com.example.data.model.Classroom
import com.example.data.model.Student
import com.example.data.model.UserRole
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.viewmodel.AttendanceViewModel
import com.example.ui.viewmodel.ReportsSummary

enum class AppDestination {
    DASHBOARD,
    MARK_ATTENDANCE,
    UPLOAD_STUDENTS,
    REPORTS,
    AI_ASSISTANT
}

@Composable
fun DashboardScreen(
    classroom: Classroom,
    effectiveRole: UserRole,
    isOwnerCoordinator: Boolean,
    students: List<Student>,
    sessions: List<AttendanceSession>,
    reportsSummary: ReportsSummary,
    onNavigate: (AppDestination) -> Unit,
    onToggleRolePreview: (UserRole) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val todayIso = AttendanceViewModel.currentDateIso()
    val todaySessionsCount = sessions.count { it.dateIso == todayIso }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth()) {
            // Hero Classroom & Live Sync Card
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.secondary
                                )
                            )
                        )
                        .padding(20.dp)
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                color = Color.White.copy(alpha = 0.18f),
                                shape = RoundedCornerShape(50)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF34D399))
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Live Firestore Sync",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = Color.White
                                    )
                                }
                            }

                            Surface(
                                color = Color.White.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(50),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .clickable(enabled = isOwnerCoordinator) {
                                        val nextRole = if (effectiveRole == UserRole.COORDINATOR) {
                                            UserRole.REPRESENTATIVE
                                        } else {
                                            UserRole.COORDINATOR
                                        }
                                        onToggleRolePreview(nextRole)
                                    }
                                    .testTag("role_badge_chip")
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = effectiveRole.displayName,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = Color.White,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    if (isOwnerCoordinator) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Icon(
                                            imageVector = Icons.Filled.SwapHoriz,
                                            contentDescription = "Switch active role preview",
                                            tint = Color.White,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        if (classroom.schoolId.isNotBlank()) {
                            Surface(
                                color = Color.White.copy(alpha = 0.16f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = "INSTITUTION: ${classroom.schoolId}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        Text(
                            text = classroom.name,
                            style = MaterialTheme.typography.headlineMedium,
                            color = Color.White
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Join Code: ",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.85f)
                            )
                            Text(
                                text = classroom.joinCode,
                                style = MaterialTheme.typography.titleSmall.copy(fontFamily = JetBrainsMonoFamily),
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                            IconButton(
                                onClick = {
                                    val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cb.setPrimaryClip(ClipData.newPlainText("Class Code", classroom.joinCode))
                                    Toast.makeText(context, "Copied code ${classroom.joinCode}", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.ContentCopy,
                                    contentDescription = "Copy classroom join code",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        if (isOwnerCoordinator && effectiveRole == UserRole.REPRESENTATIVE) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Previewing Class Representative permissions (tap role badge to switch back).",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.85f)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // KPI Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MetricTile(
                    title = "Total Students",
                    value = students.size.toString(),
                    icon = Icons.Filled.Groups,
                    containerColor = MaterialTheme.colorScheme.surface,
                    accentColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                MetricTile(
                    title = "Today Lectures",
                    value = todaySessionsCount.toString(),
                    icon = Icons.AutoMirrored.Filled.FactCheck,
                    containerColor = MaterialTheme.colorScheme.surface,
                    accentColor = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.weight(1f)
                )
                MetricTile(
                    title = "Avg Attendance",
                    value = if (sessions.isEmpty()) "—" else "${reportsSummary.averageAttendancePercent.toInt()}%",
                    icon = Icons.Filled.Assessment,
                    containerColor = MaterialTheme.colorScheme.surface,
                    accentColor = if (reportsSummary.atRiskCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(22.dp))

            Text(
                text = "Quick Actions",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Action 1: Mark Today's Attendance
            QuickActionCard(
                title = "Mark Today's Attendance",
                subtitle = if (students.isEmpty()) {
                    "Add or import students first to mark P / A"
                } else {
                    "Instant P / A marking across ${students.size} students · Live sync"
                },
                icon = Icons.AutoMirrored.Filled.FactCheck,
                accentColor = MaterialTheme.colorScheme.tertiary,
                badgeText = "Coordinator & Rep",
                onClick = { onNavigate(AppDestination.MARK_ATTENDANCE) },
                modifier = Modifier.testTag("card_mark_attendance")
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Action 2: Upload Student Data (Coordinator Only)
            val isCoordinator = effectiveRole == UserRole.COORDINATOR
            QuickActionCard(
                title = "Upload Student Data (.xlsx / .csv)",
                subtitle = if (isCoordinator) {
                    "Pick an Excel or CSV file, preview rows, and sync to Firestore"
                } else {
                    "Restricted to Class Coordinators (view-only in Representative mode)"
                },
                icon = if (isCoordinator) Icons.Filled.UploadFile else Icons.Filled.Lock,
                accentColor = if (isCoordinator) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                badgeText = if (isCoordinator) "Coordinator Only" else "Locked for Rep",
                onClick = { onNavigate(AppDestination.UPLOAD_STUDENTS) },
                modifier = Modifier.testTag("card_upload_students")
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Action 3: View Attendance Reports
            QuickActionCard(
                title = "View Attendance Reports",
                subtitle = if (reportsSummary.atRiskCount > 0) {
                    "${reportsSummary.atRiskCount} student(s) currently below 75% threshold"
                } else {
                    "Filter sessions by subject/date, export CSV, and track percentages"
                },
                icon = if (reportsSummary.atRiskCount > 0) Icons.Filled.Warning else Icons.Filled.Assessment,
                accentColor = if (reportsSummary.atRiskCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                badgeText = if (reportsSummary.atRiskCount > 0) {
                    "! ${reportsSummary.atRiskCount} Below 75%"
                } else {
                    "${sessions.size} Sessions · CSV"
                },
                onClick = { onNavigate(AppDestination.REPORTS) },
                modifier = Modifier.testTag("card_view_reports")
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Action 4: Gemini AI Assistant & Sheet Photo Analyzer
            QuickActionCard(
                title = "Gemini AI Assistant & Sheet Scanner",
                subtitle = "Multi-turn attendance chatbot + photo analysis of register sheets",
                icon = Icons.Filled.DocumentScanner,
                accentColor = MaterialTheme.colorScheme.primary,
                badgeText = "Gemini AI",
                trailingIcon = Icons.Filled.AutoAwesome,
                onClick = { onNavigate(AppDestination.AI_ASSISTANT) },
                modifier = Modifier.testTag("card_ai_assistant")
            )

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
private fun MetricTile(
    title: String,
    value: String,
    icon: ImageVector,
    containerColor: Color,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium.copy(fontFamily = JetBrainsMonoFamily),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun QuickActionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: Color,
    badgeText: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailingIcon: ImageVector? = null
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(accentColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    color = accentColor.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(50)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        if (trailingIcon != null) {
                            Icon(
                                imageVector = trailingIcon,
                                contentDescription = null,
                                tint = accentColor,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Text(
                            text = badgeText,
                            style = MaterialTheme.typography.labelSmall,
                            color = accentColor
                        )
                    }
                }
            }
        }
    }
}
