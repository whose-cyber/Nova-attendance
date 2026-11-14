package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

enum class UserRole(val key: String, val displayName: String, val shortLabel: String) {
    COORDINATOR("coordinator", "Class Coordinator", "Coordinator"),
    REPRESENTATIVE("representative", "Class Representative", "Representative");

    companion object {
        fun fromKey(key: String?): UserRole {
            return entries.firstOrNull { it.key == key } ?: COORDINATOR
        }
    }
}

@IgnoreExtraProperties
data class UserProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "",
    val email: String = "",
    val schoolId: String = "",
    val activeClassroomId: String = "",
    val activeRole: String = UserRole.COORDINATOR.key,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class Classroom(
    @DocumentId val id: String = "",
    val schoolId: String = "",
    val name: String = "",
    val joinCode: String = "",
    val ownerId: String = "",
    val coordinatorIds: List<String> = emptyList(),
    val memberIds: List<String> = emptyList(),
    val subjects: List<String> = emptyList(),
    val timings: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun isUserCoordinator(uid: String): Boolean = coordinatorIds.contains(uid)
}

@IgnoreExtraProperties
data class Student(
    @DocumentId val id: String = "",
    val schoolId: String = "",
    val classroomId: String = "",
    val rollNumber: String = "",
    val name: String = "",
    val createdBy: String = "",
    val memberIds: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class AttendanceSession(
    @DocumentId val id: String = "",
    val schoolId: String = "",
    val classroomId: String = "",
    val dateIso: String = "",
    val subject: String = "",
    val timing: String = "",
    val markedBy: String = "",
    val markedByName: String = "",
    val records: Map<String, String> = emptyMap(),
    val memberIds: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

data class ParsedStudentRow(
    val rollNumber: String,
    val name: String
)

data class SpreadsheetParseResult(
    val fileName: String,
    val students: List<ParsedStudentRow>,
    val skippedRows: Int,
    val warnings: List<String>
)

data class StudentReportItem(
    val student: Student,
    val presentCount: Int,
    val absentCount: Int,
    val totalSessionsMarked: Int,
    val attendancePercentage: Float
)

data class GeminiChatMessage(
    val id: String,
    val isUser: Boolean,
    val text: String,
    val isStreaming: Boolean = false
)
