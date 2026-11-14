package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.firestore.OperationType
import com.example.data.firestore.handleFirestoreError
import com.example.data.model.AttendanceSession
import com.example.data.model.Classroom
import com.example.data.model.ParsedStudentRow
import com.example.data.model.Student
import com.example.data.model.UserProfile
import com.example.data.model.UserRole
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import java.security.MessageDigest
import java.util.UUID

class AttendanceRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    private val profileMutex = Mutex()

    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    private fun schoolDoc(schoolId: String) =
        db.collection("schools").document(schoolId)

    private fun classroomsCol(schoolId: String) =
        schoolDoc(schoolId).collection("classrooms")

    private fun invitesCol(schoolId: String) =
        schoolDoc(schoolId).collection("invites")

    private fun classroomDoc(schoolId: String, classroomId: String) =
        classroomsCol(schoolId).document(classroomId)

    fun observeUserProfile(uid: String): Flow<UserProfile?> = flow {
        val path = "users/$uid"
        emitAll(
            db.collection("users").document(uid)
                .snapshots()
                .map { snapshot ->
                    if (snapshot.exists()) {
                        snapshot.toObject(
                            UserProfile::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        )
                    } else {
                        null
                    }
                }
                .catch { error ->
                    logStreamErrorIfActive(uid, error, OperationType.GET, path)
                    throw error
                }
        )
    }

    suspend fun ensureUserProfile(
        uid: String,
        displayName: String,
        email: String
    ): Result<UserProfile> = runCatching {
        profileMutex.withLock {
            val docRef = db.collection("users").document(uid)
            val snapshot = try {
                docRef.get().await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.GET, docRef.path)
                throw e
            }

            if (snapshot.exists()) {
                if (!snapshot.contains("schoolId")) {
                    try {
                        docRef.update(
                            mapOf(
                                "schoolId" to "",
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                        ).await()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        handleFirestoreError(e, OperationType.UPDATE, docRef.path)
                        throw e
                    }
                }
                val existing = snapshot.toObject(
                    UserProfile::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
                if (existing != null) return@withLock existing
            }

            val safeName = displayName.trim().ifBlank { "NovaAttend User" }.take(100)
            val safeEmail = email.trim().take(150)
            val payload = mapOf(
                "userId" to uid,
                "displayName" to safeName,
                "email" to safeEmail,
                "schoolId" to "",
                "activeClassroomId" to "",
                "activeRole" to UserRole.COORDINATOR.key,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            try {
                docRef.set(payload).await()
                docRef.get().await().toObject(
                    UserProfile::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                ) ?: UserProfile(
                    id = uid,
                    userId = uid,
                    displayName = safeName,
                    email = safeEmail,
                    schoolId = "",
                    activeClassroomId = "",
                    activeRole = UserRole.COORDINATOR.key
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.CREATE, docRef.path)
                throw e
            }
        }
    }

    suspend fun bindSchoolCode(
        rawSchoolId: String,
        displayName: String = auth.currentUser?.displayName.orEmpty(),
        email: String = auth.currentUser?.email.orEmpty()
    ): Result<UserProfile> = runCatching {
        val uid = requireUserId()
        val schoolId = normalizeSchoolId(rawSchoolId)
        require(schoolId.length in 3..40) {
            "Enter a valid 3–40 character School Code (e.g. DPS_BALLABGARH or GPC_SEC56)."
        }
        ensureAndBindSchoolLocked(uid, schoolId, displayName, email)
    }

    private suspend fun ensureAndBindSchoolLocked(
        uid: String,
        schoolId: String,
        displayName: String,
        email: String
    ): UserProfile = profileMutex.withLock {
        val userRef = db.collection("users").document(uid)
        val userSnap = try {
            userRef.get().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, userRef.path)
            throw e
        }

        val safeName = displayName.trim().ifBlank { "NovaAttend User" }.take(100)
        val safeEmail = email.trim().take(150)

        if (!userSnap.exists()) {
            val payload = mapOf(
                "userId" to uid,
                "displayName" to safeName,
                "email" to safeEmail,
                "schoolId" to schoolId,
                "activeClassroomId" to "",
                "activeRole" to UserRole.COORDINATOR.key,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            try {
                userRef.set(payload).await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.CREATE, userRef.path)
                throw e
            }
        } else {
            val existingSchoolId = userSnap.getString("schoolId")?.trim().orEmpty()
            if (existingSchoolId.isNotEmpty() && existingSchoolId != schoolId) {
                throw IllegalStateException(
                    "Your account is already linked to institution \"$existingSchoolId\". Cross-school access to \"$schoolId\" is not permitted."
                )
            }
            if (existingSchoolId.isEmpty()) {
                try {
                    userRef.update(
                        mapOf(
                            "schoolId" to schoolId,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    handleFirestoreError(e, OperationType.UPDATE, userRef.path)
                    throw e
                }
            }
        }

        userRef.get().await().toObject(
            UserProfile::class.java,
            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
        ) ?: UserProfile(
            id = uid,
            userId = uid,
            displayName = safeName,
            email = safeEmail,
            schoolId = schoolId
        )
    }

    suspend fun updateActiveRoleAndClassroom(
        uid: String,
        classroomId: String,
        role: UserRole
    ): Result<Unit> = runCatching {
        val currentUid = requireUserId()
        require(currentUid == uid) { "Cannot update another user's profile." }
        val docRef = db.collection("users").document(uid)
        try {
            docRef.update(
                mapOf(
                    "activeClassroomId" to classroomId,
                    "activeRole" to role.key,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            throw e
        }
    }

    fun observeMyClassrooms(schoolId: String, uid: String): Flow<List<Classroom>> = flow {
        val cleanSchoolId = normalizeSchoolId(schoolId)
        require(cleanSchoolId.length in 3..40) { "Valid School Code is required." }
        val path = "schools/$cleanSchoolId/classrooms"
        emitAll(
            classroomsCol(cleanSchoolId)
                .whereArrayContains("memberIds", uid)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        Classroom::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { it.createdAt?.seconds ?: 0L }
                }
                .catch { error ->
                    logStreamErrorIfActive(uid, error, OperationType.LIST, path)
                    throw error
                }
        )
    }

    fun observeClassroom(schoolId: String, classroomId: String, uid: String): Flow<Classroom?> = flow {
        val cleanSchoolId = normalizeSchoolId(schoolId)
        val path = "schools/$cleanSchoolId/classrooms/$classroomId"
        emitAll(
            classroomDoc(cleanSchoolId, classroomId)
                .snapshots()
                .map { snapshot ->
                    if (snapshot.exists()) {
                        snapshot.toObject(
                            Classroom::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        )
                    } else {
                        null
                    }
                }
                .catch { error ->
                    logStreamErrorIfActive(uid, error, OperationType.GET, path)
                    throw error
                }
        )
    }

    suspend fun createClassroom(
        rawSchoolId: String,
        name: String,
        customJoinCode: String? = null,
        subjects: List<String> = DEFAULT_SUBJECTS,
        timings: List<String> = DEFAULT_TIMINGS,
        displayName: String = auth.currentUser?.displayName.orEmpty(),
        email: String = auth.currentUser?.email.orEmpty()
    ): Result<Classroom> = runCatching {
        val uid = requireUserId()
        val schoolId = normalizeSchoolId(rawSchoolId)
        require(schoolId.length in 3..40) {
            "Enter a valid 3–40 character School Code (e.g. DPS_BALLABGARH or GPC_SEC56)."
        }
        val trimmedName = name.trim().take(80)
        require(trimmedName.isNotEmpty()) { "Classroom name is required." }

        val userRef = db.collection("users").document(uid)
        ensureAndBindSchoolLocked(uid, schoolId, displayName, email)

        // Ensure /schools/{schoolId} document exists (tolerate concurrent creation by another Coordinator)
        val schoolRef = schoolDoc(schoolId)
        val schoolExists = try {
            schoolRef.get().await().exists()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        if (!schoolExists) {
            val schoolPayload = mapOf(
                "schoolId" to schoolId,
                "name" to schoolId,
                "createdBy" to uid,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            try {
                schoolRef.set(schoolPayload).await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // If another Coordinator in the same school created /schools/{schoolId} concurrently,
                // set() becomes an update and hits allow update: if false; verify it exists before failing.
                val existsNow = runCatching { schoolRef.get().await().exists() }.getOrDefault(false)
                if (!existsNow) {
                    handleFirestoreError(e, OperationType.CREATE, schoolRef.path)
                    throw e
                }
            }
        }

        val classroomId = "cls_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val joinCode = (customJoinCode?.trim()?.uppercase()
            ?.replace(Regex("[^A-Z0-9\\-]"), "")
            ?.take(16)
            ?.takeIf { it.length >= 4 }
            ?: generateJoinCode())

        val classroomRef = classroomDoc(schoolId, classroomId)
        val classroomPayload = mapOf(
            "schoolId" to schoolId,
            "name" to trimmedName,
            "joinCode" to joinCode,
            "ownerId" to uid,
            "coordinatorIds" to listOf(uid),
            "memberIds" to listOf(uid),
            "subjects" to subjects.take(25),
            "timings" to timings.take(25),
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        try {
            classroomRef.set(classroomPayload).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, classroomRef.path)
            throw e
        }

        val inviteRef = invitesCol(schoolId).document(joinCode)
        val invitePayload = mapOf(
            "schoolId" to schoolId,
            "classroomId" to classroomId,
            "classroomName" to trimmedName,
            "ownerId" to uid,
            "createdAt" to FieldValue.serverTimestamp()
        )
        try {
            inviteRef.set(invitePayload).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, inviteRef.path)
            throw e
        }

        try {
            userRef.update(
                mapOf(
                    "activeClassroomId" to classroomId,
                    "activeRole" to UserRole.COORDINATOR.key,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, userRef.path)
        }

        classroomRef.get().await().toObject(
            Classroom::class.java,
            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
        ) ?: Classroom(
            id = classroomId,
            schoolId = schoolId,
            name = trimmedName,
            joinCode = joinCode,
            ownerId = uid,
            coordinatorIds = listOf(uid),
            memberIds = listOf(uid),
            subjects = subjects,
            timings = timings
        )
    }

    suspend fun joinClassroomByCode(
        rawSchoolId: String,
        rawCode: String,
        displayName: String = auth.currentUser?.displayName.orEmpty(),
        email: String = auth.currentUser?.email.orEmpty()
    ): Result<Classroom> = runCatching {
        val uid = requireUserId()
        val schoolId = normalizeSchoolId(rawSchoolId)
        require(schoolId.length in 3..40) {
            "Enter a valid 3–40 character School Code (e.g. DPS_BALLABGARH or GPC_SEC56)."
        }
        val code = rawCode.trim().uppercase().replace(Regex("[^A-Z0-9\\-]"), "")
        require(code.length in 4..16) { "Enter a valid 4–16 character class join code." }

        val userRef = db.collection("users").document(uid)
        val existingSchoolId = profileMutex.withLock {
            val userSnap = try {
                userRef.get().await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.GET, userRef.path)
                throw e
            }
            if (userSnap.exists()) userSnap.getString("schoolId")?.trim().orEmpty() else ""
        }
        if (existingSchoolId.isNotEmpty() && existingSchoolId != schoolId) {
            throw IllegalStateException(
                "Your account belongs to institution \"$existingSchoolId\". Cross-school access to \"$schoolId\" is blocked."
            )
        }

        // Look up school-scoped invite BEFORE locking user's schoolId so typos don't bind wrong school
        val inviteRef = invitesCol(schoolId).document(code)
        val inviteSnap = try {
            inviteRef.get().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, inviteRef.path)
            throw e
        }

        if (!inviteSnap.exists()) {
            throw IllegalArgumentException("No classroom found for code \"$code\" in institution \"$schoolId\".")
        }
        val classroomId = inviteSnap.getString("classroomId")
            ?: throw IllegalStateException("Invite record is missing classroomId.")

        ensureAndBindSchoolLocked(uid, schoolId, displayName, email)

        val classroomRef = classroomDoc(schoolId, classroomId)
        val alreadyMember = try {
            val clsSnap = classroomRef.get().await()
            val members = (clsSnap.get("memberIds") as? List<*>)?.filterIsInstance<String>().orEmpty()
            val coords = (clsSnap.get("coordinatorIds") as? List<*>)?.filterIsInstance<String>().orEmpty()
            members.contains(uid) || coords.contains(uid)
        } catch (_: Exception) {
            false
        }

        if (!alreadyMember) {
            try {
                classroomRef.update(
                    mapOf(
                        "memberIds" to FieldValue.arrayUnion(uid),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.UPDATE, classroomRef.path)
                throw e
            }
        }

        val classroom = try {
            classroomRef.get().await().toObject(
                Classroom::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
            ) ?: throw IllegalStateException("Unable to load classroom after joining.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, classroomRef.path)
            throw e
        }

        val role = if (classroom.isUserCoordinator(uid)) UserRole.COORDINATOR else UserRole.REPRESENTATIVE
        try {
            userRef.update(
                mapOf(
                    "activeClassroomId" to classroomId,
                    "activeRole" to role.key,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, userRef.path)
        }

        classroom
    }

    suspend fun syncMemberIdsToClassroomDocs(classroom: Classroom): Result<Unit> = runCatching {
        val uid = requireUserId()
        if (!classroom.isUserCoordinator(uid) || classroom.schoolId.isBlank()) return@runCatching

        val classRef = classroomDoc(classroom.schoolId, classroom.id)
        val studentsCol = classRef.collection("students")
        val sessionsCol = classRef.collection("sessions")

        val studentDocs = try {
            studentsCol.whereArrayContains("memberIds", uid).get().await().documents
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, studentsCol.path)
            throw e
        }

        val sessionDocs = try {
            sessionsCol.whereArrayContains("memberIds", uid).get().await().documents
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, sessionsCol.path)
            throw e
        }

        val targetRefs = buildList {
            for (doc in studentDocs) {
                val existingMembers = (doc.get("memberIds") as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                if (!existingMembers.containsAll(classroom.memberIds)) {
                    add(doc.reference)
                }
            }
            for (doc in sessionDocs) {
                val existingMembers = (doc.get("memberIds") as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                if (!existingMembers.containsAll(classroom.memberIds)) {
                    add(doc.reference)
                }
            }
        }

        targetRefs.chunked(400).forEach { chunk ->
            val batch = db.batch()
            chunk.forEach { ref ->
                batch.update(
                    ref,
                    mapOf(
                        "memberIds" to classroom.memberIds,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }
            try {
                batch.commit().await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.WRITE, classRef.path)
                throw e
            }
        }
    }

    fun observeStudents(schoolId: String, classroomId: String, uid: String): Flow<List<Student>> = flow {
        val cleanSchoolId = normalizeSchoolId(schoolId)
        val path = "schools/$cleanSchoolId/classrooms/$classroomId/students"
        emitAll(
            classroomDoc(cleanSchoolId, classroomId).collection("students")
                .whereArrayContains("memberIds", uid)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        Student::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedWith(compareBy({ extractNumericRoll(it.rollNumber) }, { it.rollNumber }))
                }
                .catch { error ->
                    logStreamErrorIfActive(uid, error, OperationType.LIST, path)
                    throw error
                }
        )
    }

    suspend fun uploadStudentsBatch(
        schoolId: String,
        classroomId: String,
        rows: List<ParsedStudentRow>,
        memberIds: List<String>
    ): Result<Int> = runCatching {
        val uid = requireUserId()
        val cleanSchoolId = normalizeSchoolId(schoolId)
        require(cleanSchoolId.length in 3..40) { "School ID is required." }
        require(rows.isNotEmpty()) { "No valid student rows to upload." }

        val studentsCol = classroomDoc(cleanSchoolId, classroomId).collection("students")
        val existingIds = try {
            studentsCol.whereArrayContains("memberIds", uid).get().await()
                .documents.map { it.id }.toSet()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, studentsCol.path)
            throw e
        }

        val normalizedMembers = (memberIds + uid).distinct().take(50)
        val deduplicatedRows = rows
            .map {
                ParsedStudentRow(
                    rollNumber = it.rollNumber.trim().take(40),
                    name = it.name.trim().take(100)
                )
            }
            .filter { it.rollNumber.isNotEmpty() && it.name.isNotEmpty() }
            .distinctBy { buildStudentId(it.rollNumber) }

        deduplicatedRows.chunked(400).forEach { chunk ->
            val batch = db.batch()
            chunk.forEach { row ->
                val docId = buildStudentId(row.rollNumber)
                val docRef = studentsCol.document(docId)
                if (existingIds.contains(docId)) {
                    batch.update(
                        docRef,
                        mapOf(
                            "rollNumber" to row.rollNumber,
                            "name" to row.name,
                            "memberIds" to normalizedMembers,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                } else {
                    batch.set(
                        docRef,
                        mapOf(
                            "schoolId" to cleanSchoolId,
                            "classroomId" to classroomId,
                            "rollNumber" to row.rollNumber,
                            "name" to row.name,
                            "createdBy" to uid,
                            "memberIds" to normalizedMembers,
                            "createdAt" to FieldValue.serverTimestamp(),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                }
            }
            try {
                batch.commit().await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.WRITE, studentsCol.path)
                throw e
            }
        }
        deduplicatedRows.size
    }

    suspend fun deleteStudent(
        schoolId: String,
        classroomId: String,
        studentId: String
    ): Result<Unit> = runCatching {
        requireUserId()
        val cleanSchoolId = normalizeSchoolId(schoolId)
        val docRef = classroomDoc(cleanSchoolId, classroomId)
            .collection("students").document(studentId)
        try {
            docRef.delete().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            throw e
        }
    }

    fun observeSessions(
        schoolId: String,
        classroomId: String,
        uid: String
    ): Flow<List<AttendanceSession>> = flow {
        val cleanSchoolId = normalizeSchoolId(schoolId)
        val path = "schools/$cleanSchoolId/classrooms/$classroomId/sessions"
        emitAll(
            classroomDoc(cleanSchoolId, classroomId).collection("sessions")
                .whereArrayContains("memberIds", uid)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        AttendanceSession::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedWith(compareByDescending<AttendanceSession> { it.dateIso }.thenBy { it.timing })
                }
                .catch { error ->
                    logStreamErrorIfActive(uid, error, OperationType.LIST, path)
                    throw error
                }
        )
    }

    suspend fun markStudentAttendance(
        schoolId: String,
        classroomId: String,
        dateIso: String,
        subject: String,
        timing: String,
        studentId: String,
        status: String,
        markerName: String,
        memberIds: List<String>
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        val cleanSchoolId = normalizeSchoolId(schoolId)
        require(cleanSchoolId.length in 3..40) { "School ID is required." }
        require(status == "P" || status == "A") { "Status must be P or A." }
        val sessionId = buildSessionId(dateIso, subject, timing)
        val docRef = classroomDoc(cleanSchoolId, classroomId)
            .collection("sessions").document(sessionId)
        val safeMarkerName = markerName.trim().ifBlank { "Staff" }.take(100)
        val normalizedMembers = (memberIds + uid).distinct().take(50)

        try {
            db.runTransaction { transaction ->
                val snapshot = transaction.get(docRef)
                if (!snapshot.exists()) {
                    transaction.set(
                        docRef,
                        mapOf(
                            "schoolId" to cleanSchoolId,
                            "classroomId" to classroomId,
                            "dateIso" to dateIso,
                            "subject" to subject.trim().take(80),
                            "timing" to timing.trim().take(60),
                            "markedBy" to uid,
                            "markedByName" to safeMarkerName,
                            "records" to mapOf(studentId to status),
                            "memberIds" to normalizedMembers,
                            "createdAt" to FieldValue.serverTimestamp(),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                } else {
                    @Suppress("UNCHECKED_CAST")
                    val existingRecords = (snapshot.get("records") as? Map<String, Any?>)
                        ?.mapValues { it.value?.toString() ?: "" }
                        ?.toMutableMap() ?: mutableMapOf()
                    existingRecords[studentId] = status
                    val existingMembers = (snapshot.get("memberIds") as? List<*>)
                        ?.filterIsInstance<String>()
                        .orEmpty()
                    val mergedMembers = (existingMembers + normalizedMembers).distinct().take(50)
                    transaction.update(
                        docRef,
                        mapOf(
                            "records" to existingRecords,
                            "markedBy" to uid,
                            "markedByName" to safeMarkerName,
                            "memberIds" to mergedMembers,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                }
            }.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            throw e
        }
    }

    suspend fun addSubject(classroom: Classroom, subject: String): Result<Unit> = runCatching {
        val uid = requireUserId()
        require(classroom.isUserCoordinator(uid)) {
            "Only Class Coordinators can add subjects."
        }
        val clean = subject.trim().take(80)
        require(clean.isNotEmpty()) { "Subject name cannot be blank." }
        if (classroom.subjects.any { it.equals(clean, ignoreCase = true) }) {
            return@runCatching
        }
        require(classroom.subjects.size < 25) {
            "Maximum of 25 subjects per classroom reached."
        }
        val updated = (classroom.subjects + clean).distinct().take(25)
        val docRef = classroomDoc(classroom.schoolId, classroom.id)
        try {
            docRef.update(
                mapOf(
                    "subjects" to updated,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            throw e
        }
    }

    suspend fun removeSubject(classroom: Classroom, subject: String): Result<Unit> = runCatching {
        val uid = requireUserId()
        require(classroom.isUserCoordinator(uid)) {
            "Only Class Coordinators can remove subjects."
        }
        val clean = subject.trim()
        require(clean.isNotEmpty()) { "Subject name cannot be blank." }
        val updated = classroom.subjects.filterNot { it == subject || it.trim() == clean }
        val docRef = classroomDoc(classroom.schoolId, classroom.id)
        try {
            docRef.update(
                mapOf(
                    "subjects" to updated,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            throw e
        }
    }

    private fun logStreamErrorIfActive(
        uid: String,
        error: Throwable,
        operationType: OperationType,
        path: String
    ) {
        val firestoreError = generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) return
        val exceptionToLog = firestoreError ?: (error as? Exception)
        if (exceptionToLog != null && auth.currentUser?.uid == uid) {
            handleFirestoreError(exceptionToLog, operationType, path)
        }
    }

    companion object {
        val DEFAULT_SUBJECTS = listOf(
            "Data Structures",
            "Operating Systems",
            "Database Systems",
            "Computer Networks",
            "Software Engineering"
        )

        val DEFAULT_TIMINGS = listOf(
            "09:00 - 10:00",
            "10:15 - 11:15",
            "11:30 - 12:30",
            "14:00 - 15:00",
            "15:15 - 16:15"
        )

        fun normalizeSchoolId(raw: String): String {
            return raw.trim()
                .uppercase()
                .replace(Regex("\\s+"), "_")
                .replace(Regex("[^A-Z0-9_\\-]"), "")
                .take(40)
        }

        fun buildStudentId(rollNumber: String): String {
            val slug = rollNumber.trim()
                .replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
                .take(48)
                .ifBlank { "row" }
            val hash = shortHash(rollNumber.trim().uppercase())
            return "stu_${slug}_$hash"
        }

        fun buildSessionId(dateIso: String, subject: String, timing: String): String {
            val subjSlug = subject.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(24)
            val timeSlug = timing.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(20)
            val hash = shortHash("$dateIso|${subject.trim()}|${timing.trim()}")
            return "sess_${dateIso}_${subjSlug}_${timeSlug}_$hash".take(120)
        }

        private fun shortHash(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
            return digest.take(4).joinToString("") { "%02x".format(it) }
        }

        private fun generateJoinCode(): String {
            val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
            val randomPart = (1..4).map { chars.random() }.joinToString("")
            return "NV$randomPart"
        }

        private fun extractNumericRoll(roll: String): Long {
            val digits = roll.filter { it.isDigit() }
            return digits.toLongOrNull() ?: Long.MAX_VALUE
        }
    }
}
