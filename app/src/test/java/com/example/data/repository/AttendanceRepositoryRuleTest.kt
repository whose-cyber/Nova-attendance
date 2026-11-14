package com.example.data.repository

import com.example.base.FirestoreEmulatorTestBase
import com.example.data.model.ParsedStudentRow
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class AttendanceRepositoryRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun multiSchoolCoordinatorAndRep_syncsAttendanceSubjectsAndIsolatesTenants() = runBlocking {
        val dpsSchool = "DPS_BALLABGARH"
        val gpcSchool = "GPC_SEC56"

        val coordEmail = "coord_${UUID.randomUUID()}@dps.edu"
        val coordUid = signInTestUser(coordEmail)
        val coordRepo = AttendanceRepository(firestore, auth)

        val profileRes = withTimeout(DEFAULT_TIMEOUT_MS) {
            coordRepo.ensureUserProfile(coordUid, "Alice Coord", coordEmail)
        }
        assertTrue(profileRes.isSuccess)

        val code = "DPS${(1000..9999).random()}"
        val classroom = withTimeout(DEFAULT_TIMEOUT_MS) {
            coordRepo.createClassroom(dpsSchool, "CS-301 Batch A", customJoinCode = code).getOrThrow()
        }
        assertEquals(dpsSchool, classroom.schoolId)

        // Same-school Representative joins via schoolId + code
        val repEmail = "rep_${UUID.randomUUID()}@dps.edu"
        val repUid = signInTestUser(repEmail)
        val repRepo = AttendanceRepository(firestore, auth)
        withTimeout(DEFAULT_TIMEOUT_MS) {
            repRepo.ensureUserProfile(repUid, "Bob Rep", repEmail).getOrThrow()
            repRepo.joinClassroomByCode(dpsSchool, code).getOrThrow()
        }

        // Cross-school Coordinator from GPC_SEC56 CANNOT join DPS classroom even with exact join code
        val gpcEmail = "gpc_${UUID.randomUUID()}@gpc.edu"
        val gpcUid = signInTestUser(gpcEmail)
        val gpcRepo = AttendanceRepository(firestore, auth)
        withTimeout(DEFAULT_TIMEOUT_MS) {
            gpcRepo.ensureUserProfile(gpcUid, "Charlie GPC", gpcEmail).getOrThrow()
            gpcRepo.createClassroom(gpcSchool, "ECE-201 Batch B").getOrThrow()
        }
        val crossSchoolJoinAttempt = withTimeout(DEFAULT_TIMEOUT_MS) {
            gpcRepo.joinClassroomByCode(dpsSchool, code)
        }
        assertTrue(crossSchoolJoinAttempt.isFailure)

        // DPS Coordinator uploads students and removes an unused subject
        signInTestUser(coordEmail)
        val refreshedClassroom = withTimeout(FLOW_TIMEOUT_MS) {
            coordRepo.observeClassroom(dpsSchool, classroom.id, coordUid)
                .first { it != null && it.memberIds.contains(repUid) }!!
        }
        val uploadedCount = withTimeout(DEFAULT_TIMEOUT_MS) {
            coordRepo.uploadStudentsBatch(
                schoolId = dpsSchool,
                classroomId = classroom.id,
                rows = listOf(
                    ParsedStudentRow("101", "Aarav Sharma"),
                    ParsedStudentRow("102", "Diya Patel")
                ),
                memberIds = refreshedClassroom.memberIds
            ).getOrThrow()
        }
        assertEquals(2, uploadedCount)

        withTimeout(DEFAULT_TIMEOUT_MS) {
            coordRepo.removeSubject(refreshedClassroom, "Software Engineering").getOrThrow()
        }
        val updatedSubjectsClassroom = withTimeout(FLOW_TIMEOUT_MS) {
            coordRepo.observeClassroom(dpsSchool, classroom.id, coordUid)
                .first { it != null && !it.subjects.contains("Software Engineering") }!!
        }
        assertFalse(updatedSubjectsClassroom.subjects.contains("Software Engineering"))

        // Representative observes students in real time and marks attendance
        signInTestUser(repEmail)
        val repStudents = withTimeout(FLOW_TIMEOUT_MS) {
            repRepo.observeStudents(dpsSchool, classroom.id, repUid).first { it.size == 2 }
        }
        assertEquals(2, repStudents.size)

        // Representative CANNOT remove a subject or upload students (RBAC enforced in repository + Firestore rules)
        val repRemoveSubjectAttempt = withTimeout(DEFAULT_TIMEOUT_MS) {
            repRepo.removeSubject(updatedSubjectsClassroom, "Data Structures")
        }
        assertTrue(repRemoveSubjectAttempt.isFailure)

        val repUploadAttempt = withTimeout(DEFAULT_TIMEOUT_MS) {
            repRepo.uploadStudentsBatch(
                schoolId = dpsSchool,
                classroomId = classroom.id,
                rows = listOf(ParsedStudentRow("103", "Unauthorized Student")),
                memberIds = refreshedClassroom.memberIds
            )
        }
        assertTrue(repUploadAttempt.isFailure)

        // Representative CAN mark attendance
        val studentId = repStudents.first().id
        val markResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repRepo.markStudentAttendance(
                schoolId = dpsSchool,
                classroomId = classroom.id,
                dateIso = "2026-11-12",
                subject = "Data Structures",
                timing = "09:00 - 10:00",
                studentId = studentId,
                status = "P",
                markerName = "Bob Rep",
                memberIds = refreshedClassroom.memberIds
            )
        }
        assertTrue("markResult failed: ${markResult.exceptionOrNull()?.stackTraceToString()}", markResult.isSuccess)

        // Coordinator receives real-time session update
        signInTestUser(coordEmail)
        val coordSessions = withTimeout(FLOW_TIMEOUT_MS) {
            coordRepo.observeSessions(dpsSchool, classroom.id, coordUid)
                .first { sessions -> sessions.any { it.records[studentId] == "P" } }
        }
        assertEquals("P", coordSessions.first().records[studentId])

        // GPC_SEC56 Coordinator cannot observe any DPS_BALLABGARH students or sessions
        signInTestUser(gpcEmail)
        val gpcReadDpsStudents = withTimeout(FLOW_TIMEOUT_MS) {
            gpcRepo.observeStudents(dpsSchool, classroom.id, gpcUid).first()
        }
        assertTrue(gpcReadDpsStudents.isEmpty())
    }

    @Test
    fun observeStudents_unauthenticatedUser_throwsPermissionDenied() = runBlocking {
        auth.signOut()
        val repo = AttendanceRepository(firestore, auth)

        var caught: Throwable? = null
        try {
            withTimeout(FLOW_TIMEOUT_MS) {
                repo.observeStudents("DPS_BALLABGARH", "cls_missing", "nobody").first()
            }
        } catch (t: Throwable) {
            caught = t
        }
        val fsEx = caught?.unwrapFirestoreException()
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsEx?.code)
    }

    @Test
    fun bindSchoolCode_secondCoordinatorAndLateRepOnExistingSession_succeedWithoutPermissionDenied() = runBlocking {
        val schoolId = "DPS_BALLABGARH"

        // Coord 1 creates classroom + starts today's session BEFORE Rep joins
        val c1Email = "c1_${UUID.randomUUID()}@dps.edu"
        val c1Uid = signInTestUser(c1Email)
        val c1Repo = AttendanceRepository(firestore, auth)
        withTimeout(DEFAULT_TIMEOUT_MS) {
            c1Repo.bindSchoolCode(schoolId, "Coord One", c1Email).getOrThrow()
        }
        val code = "DP${(1000..9999).random()}"
        val cls1 = withTimeout(DEFAULT_TIMEOUT_MS) {
            c1Repo.createClassroom(schoolId, "CS-401", customJoinCode = code, displayName = "Coord One", email = c1Email).getOrThrow()
        }
        withTimeout(DEFAULT_TIMEOUT_MS) {
            c1Repo.uploadStudentsBatch(
                schoolId = schoolId,
                classroomId = cls1.id,
                rows = listOf(ParsedStudentRow("201", "Rohan"), ParsedStudentRow("202", "Neha")),
                memberIds = cls1.memberIds
            ).getOrThrow()
            c1Repo.markStudentAttendance(
                schoolId = schoolId,
                classroomId = cls1.id,
                dateIso = "2026-11-13",
                subject = "Data Structures",
                timing = "09:00 - 10:00",
                studentId = AttendanceRepository.buildStudentId("201"),
                status = "P",
                markerName = "Coord One",
                memberIds = cls1.memberIds
            ).getOrThrow()
        }

        // Coord 2 in the SAME existing school creates another classroom without /schools/{schoolId} conflict
        val c2Email = "c2_${UUID.randomUUID()}@dps.edu"
        signInTestUser(c2Email)
        val c2Repo = AttendanceRepository(firestore, auth)
        val cls2 = withTimeout(DEFAULT_TIMEOUT_MS) {
            c2Repo.createClassroom(schoolId, "CS-402", displayName = "Coord Two", email = c2Email).getOrThrow()
        }
        assertEquals(schoolId, cls2.schoolId)

        // Rep joins cls1 AFTER session was created and immediately marks student 202 on same session
        val repEmail = "rep2_${UUID.randomUUID()}@dps.edu"
        val repUid = signInTestUser(repEmail)
        val repRepo = AttendanceRepository(firestore, auth)
        val joined = withTimeout(DEFAULT_TIMEOUT_MS) {
            repRepo.joinClassroomByCode(schoolId, code, displayName = "Late Rep", email = repEmail).getOrThrow()
        }
        val repMarkRes = withTimeout(DEFAULT_TIMEOUT_MS) {
            repRepo.markStudentAttendance(
                schoolId = schoolId,
                classroomId = joined.id,
                dateIso = "2026-11-13",
                subject = "Data Structures",
                timing = "09:00 - 10:00",
                studentId = AttendanceRepository.buildStudentId("202"),
                status = "A",
                markerName = "Late Rep",
                memberIds = joined.memberIds
            )
        }
        assertTrue("Rep mark on pre-existing session failed: ${repMarkRes.exceptionOrNull()?.stackTraceToString()}", repMarkRes.isSuccess)

        val repSessions = withTimeout(FLOW_TIMEOUT_MS) {
            repRepo.observeSessions(schoolId, joined.id, repUid)
                .first { list -> list.any { it.records[AttendanceRepository.buildStudentId("202")] == "A" } }
        }
        assertEquals("P", repSessions.first().records[AttendanceRepository.buildStudentId("201")])
        assertEquals("A", repSessions.first().records[AttendanceRepository.buildStudentId("202")])
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 5000L
        const val FLOW_TIMEOUT_MS = 4000L
    }
}
