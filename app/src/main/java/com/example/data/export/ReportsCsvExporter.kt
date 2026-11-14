package com.example.data.export

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.data.model.StudentReportItem
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ReportsCsvExporter {

    const val LOW_ATTENDANCE_THRESHOLD = 75f

    /**
     * Compiles each student's Roll Number, Name, Present Count, Absent Count, Total Classes,
     * Attendance Percentage, and Warning Status into an Excel-compatible UTF-8 CSV string.
     */
    fun buildCsvContent(
        schoolId: String = "",
        classroomName: String,
        subjectFilterLabel: String,
        dateFilterLabel: String,
        studentReports: List<StudentReportItem>,
        includeUtf8Bom: Boolean = true
    ): String {
        val sb = StringBuilder()
        if (includeUtf8Bom) {
            sb.append('\uFEFF')
        }
        // Summary metadata header rows for clear Excel/Sheets context
        if (schoolId.isNotBlank()) {
            sb.appendLine(csvRow("School Code", schoolId))
        }
        sb.appendLine(csvRow("Classroom", classroomName))
        sb.appendLine(csvRow("Subject Filter", subjectFilterLabel))
        sb.appendLine(csvRow("Date Filter", dateFilterLabel))
        sb.appendLine(
            csvRow(
                "Generated At",
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
            )
        )
        sb.appendLine()

        // Column headers
        sb.appendLine(
            csvRow(
                "Roll Number",
                "Name",
                "Present Count",
                "Absent Count",
                "Total Classes",
                "Attendance Percentage",
                "Status"
            )
        )

        studentReports.forEach { item ->
            val hasSessions = item.totalSessionsMarked > 0
            val percentageText = if (hasSessions) {
                String.format(Locale.US, "%.1f%%", item.attendancePercentage)
            } else {
                "N/A"
            }
            val statusText = when {
                !hasSessions -> "No Sessions"
                item.attendancePercentage < LOW_ATTENDANCE_THRESHOLD -> "! Low Attendance (<75%)"
                else -> "Good Standing"
            }

            sb.appendLine(
                csvRow(
                    item.student.rollNumber,
                    item.student.name,
                    item.presentCount.toString(),
                    item.absentCount.toString(),
                    item.totalSessionsMarked.toString(),
                    percentageText,
                    statusText
                )
            )
        }

        return sb.toString()
    }

    fun buildReportFileName(
        schoolId: String = "",
        classroomName: String,
        subjectFilter: String?,
        dateFilterLabel: String
    ): String {
        val schoolSlug = schoolId.trim()
            .replace(Regex("[^a-zA-Z0-9_\\-]+"), "_")
            .trim('_')
            .take(20)
        val batchSlug = classroomName.trim()
            .replace(Regex("[^a-zA-Z0-9]+"), "_")
            .trim('_')
            .take(24)
            .ifBlank { "Classroom" }
        val subjectSlug = (subjectFilter ?: "AllSubjects")
            .trim()
            .replace(Regex("[^a-zA-Z0-9]+"), "_")
            .trim('_')
            .take(20)
        val dateSlug = dateFilterLabel.trim()
            .replace(Regex("[^a-zA-Z0-9\\-]+"), "_")
            .trim('_')
            .take(20)
        val prefix = if (schoolSlug.isNotBlank()) "NovaAttend_${schoolSlug}" else "NovaAttend"
        return "${prefix}_${batchSlug}_${subjectSlug}_${dateSlug}.csv"
    }

    fun writeCsvToDocumentUri(
        contentResolver: ContentResolver,
        uri: Uri,
        csvContent: String
    ) {
        contentResolver.openOutputStream(uri)?.use { stream ->
            stream.write(csvContent.toByteArray(Charsets.UTF_8))
            stream.flush()
        } ?: throw IllegalStateException("Unable to open destination file for writing.")
    }

    fun writeCsvToShareUri(
        context: Context,
        fileName: String,
        csvContent: String
    ): Uri {
        val exportsDir = File(context.cacheDir, "exports").apply { mkdirs() }
        // Clean old temp exports to keep cache minimal
        exportsDir.listFiles()?.forEach { oldFile ->
            if (oldFile.name != fileName) {
                oldFile.delete()
            }
        }
        val outFile = File(exportsDir, fileName)
        outFile.writeBytes(csvContent.toByteArray(Charsets.UTF_8))
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            outFile
        )
    }

    private fun csvRow(vararg values: String): String {
        return values.joinToString(",") { escapeCsvField(it) }
    }

    private fun escapeCsvField(value: String): String {
        val needsQuotes = value.contains(',') ||
            value.contains('"') ||
            value.contains('\n') ||
            value.contains('\r')
        return if (needsQuotes) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
    }
}
