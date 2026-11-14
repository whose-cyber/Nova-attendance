package com.example.data.export

import com.example.data.model.Student
import com.example.data.model.StudentReportItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportsCsvExporterTest {

    @Test
    fun buildCsvContent_includesHeadersEscapingSchoolCodeAndLowAttendanceFlag() {
        val reports = listOf(
            StudentReportItem(
                student = Student(
                    id = "s1",
                    schoolId = "DPS_BALLABGARH",
                    rollNumber = "CS101",
                    name = "Sharma, Aarav"
                ),
                presentCount = 9,
                absentCount = 1,
                totalSessionsMarked = 10,
                attendancePercentage = 90.0f
            ),
            StudentReportItem(
                student = Student(
                    id = "s2",
                    schoolId = "DPS_BALLABGARH",
                    rollNumber = "CS102",
                    name = "Diya \"D\" Patel"
                ),
                presentCount = 5,
                absentCount = 5,
                totalSessionsMarked = 10,
                attendancePercentage = 50.0f
            ),
            StudentReportItem(
                student = Student(
                    id = "s3",
                    schoolId = "DPS_BALLABGARH",
                    rollNumber = "CS103",
                    name = "Rohan Verma"
                ),
                presentCount = 0,
                absentCount = 0,
                totalSessionsMarked = 0,
                attendancePercentage = 0f
            )
        )

        val csv = ReportsCsvExporter.buildCsvContent(
            schoolId = "DPS_BALLABGARH",
            classroomName = "CS-A Batch",
            subjectFilterLabel = "Data Structures",
            dateFilterLabel = "Last 7 Days",
            studentReports = reports,
            includeUtf8Bom = true
        )

        assertTrue(csv.startsWith("\uFEFF"))
        assertTrue(csv.contains("School Code,DPS_BALLABGARH"))
        assertTrue(csv.contains("Classroom,CS-A Batch"))
        assertTrue(csv.contains("Subject Filter,Data Structures"))
        assertTrue(csv.contains("Date Filter,Last 7 Days"))
        assertTrue(
            csv.contains(
                "Roll Number,Name,Present Count,Absent Count,Total Classes,Attendance Percentage,Status"
            )
        )
        assertTrue(csv.contains("CS101,\"Sharma, Aarav\",9,1,10,90.0%,Good Standing"))
        assertTrue(csv.contains("CS102,\"Diya \"\"D\"\" Patel\",5,5,10,50.0%,! Low Attendance (<75%)"))
        assertTrue(csv.contains("CS103,Rohan Verma,0,0,0,N/A,No Sessions"))
    }

    @Test
    fun buildReportFileName_sanitizesSpecialCharactersAndIncludesSchoolCode() {
        val fileName = ReportsCsvExporter.buildReportFileName(
            schoolId = "DPS_BALLABGARH",
            classroomName = "CS / Batch 2026!",
            subjectFilter = "OS & Systems",
            dateFilterLabel = "2026-11-13"
        )

        assertEquals("NovaAttend_DPS_BALLABGARH_CS_Batch_2026_OS_Systems_2026-11-13.csv", fileName)
        assertFalse(fileName.contains(" "))
    }
}
