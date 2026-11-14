package com.example.ui.viewmodel

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.AttendanceSession
import com.example.data.model.Classroom
import com.example.data.model.ParsedStudentRow
import com.example.data.model.SpreadsheetParseResult
import com.example.data.model.Student
import com.example.data.model.StudentReportItem
import com.example.data.model.UserProfile
import com.example.data.model.UserRole
import com.example.data.parser.SpreadsheetParser
import com.example.data.repository.AttendanceRepository
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class ReportDateFilter(val label: String) {
    ALL("All Dates"),
    TODAY("Today"),
    LAST_7_DAYS("Last 7 Days"),
    CUSTOM_DATE("Specific Date")
}

sealed interface WorkspaceState {
    data object Loading : WorkspaceState
    data class NeedsClassroom(
        val boundSchoolId: String = "",
        val setupNotice: String? = null
    ) : WorkspaceState
    data class Ready(
        val profile: UserProfile?,
        val classrooms: List<Classroom>,
        val activeClassroom: Classroom,
        val effectiveRole: UserRole,
        val isOwnerCoordinator: Boolean
    ) : WorkspaceState
    data class Error(
        val message: String,
        val isPermissionDenied: Boolean = false,
        val boundSchoolId: String = ""
    ) : WorkspaceState
}

private sealed interface ProfileLoadState {
    data object Loading : ProfileLoadState
    data class Loaded(val profile: UserProfile?) : ProfileLoadState
    data class Error(val message: String, val isPermissionDenied: Boolean) : ProfileLoadState
}

private sealed interface ClassroomLoadState {
    data object Loading : ClassroomLoadState
    data class Loaded(val classrooms: List<Classroom>) : ClassroomLoadState
    data class Error(val message: String, val isPermissionDenied: Boolean) : ClassroomLoadState
}

data class AttendanceActionState(
    val selectedDateIso: String = AttendanceViewModel.currentDateIso(),
    val selectedSubject: String = AttendanceRepository.DEFAULT_SUBJECTS.first(),
    val selectedTiming: String = AttendanceRepository.DEFAULT_TIMINGS.first(),
    val savingStudentIds: Set<String> = emptySet(),
    val attendanceError: String? = null,
    val isCreatingOrJoiningClassroom: Boolean = false,
    val classroomActionError: String? = null,
    val schoolBindSuccessMessage: String? = null,
    val pendingParseResult: SpreadsheetParseResult? = null,
    val isParsingFile: Boolean = false,
    val isUploadingStudents: Boolean = false,
    val uploadSuccessMessage: String? = null,
    val uploadErrorMessage: String? = null,
    val reportSubjectFilter: String? = null,
    val reportDateFilter: ReportDateFilter = ReportDateFilter.ALL,
    val reportCustomDateIso: String = AttendanceViewModel.currentDateIso(),
    val deletingSubject: String? = null
)

data class ReportsSummary(
    val filteredSessions: List<AttendanceSession> = emptyList(),
    val studentReports: List<StudentReportItem> = emptyList(),
    val availableSubjects: List<String> = emptyList(),
    val averageAttendancePercent: Float = 0f,
    val atRiskCount: Int = 0
)

@OptIn(ExperimentalCoroutinesApi::class)
class AttendanceViewModel(
    private val repository: AttendanceRepository,
    private val currentUserId: String,
    private val currentUserName: String,
    private val currentUserEmail: String
) : ViewModel() {

    private val _actionState = MutableStateFlow(AttendanceActionState())
    val actionState: StateFlow<AttendanceActionState> = _actionState.asStateFlow()

    private val retryTrigger = MutableStateFlow(0)
    private val forceSetupMode = MutableStateFlow(false)
    private var lastSyncedMemberHash: String? = null

    init {
        viewModelScope.launch {
            repository.ensureUserProfile(currentUserId, currentUserName, currentUserEmail)
                .onFailure { Log.w(TAG, "Failed to ensure user profile", it) }
        }
    }

    private val profileLoadFlow: StateFlow<ProfileLoadState> = retryTrigger
        .flatMapLatest {
            repository.observeUserProfile(currentUserId)
                .map<UserProfile?, ProfileLoadState> { ProfileLoadState.Loaded(it) }
                .onStart { emit(ProfileLoadState.Loading) }
                .catch { error ->
                    if (isRealCancellation(error)) throw error
                    val fsErr = unwrapFirestore(error)
                    Log.w(TAG, "Profile stream error", fsErr ?: error)
                    if (fsErr?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                        emit(ProfileLoadState.Loaded(null))
                    } else {
                        emit(
                            ProfileLoadState.Error(
                                message = formatFirestoreErrorMessage(
                                    fsErr ?: error,
                                    "Could not load your user profile."
                                ),
                                isPermissionDenied = false
                            )
                        )
                    }
                }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ProfileLoadState.Loading)

    private val classroomsLoadFlow: StateFlow<ClassroomLoadState> = combine(
        profileLoadFlow,
        retryTrigger
    ) { loadState, retryNonce ->
        when (loadState) {
            ProfileLoadState.Loading -> null to retryNonce
            is ProfileLoadState.Error -> "" to retryNonce
            is ProfileLoadState.Loaded -> {
                val normalized = AttendanceRepository.normalizeSchoolId(loadState.profile?.schoolId.orEmpty())
                normalized to retryNonce
            }
        }
    }
        .distinctUntilChanged()
        .flatMapLatest { (schoolId, _) ->
            when {
                schoolId == null -> flowOf(ClassroomLoadState.Loading)
                schoolId.length < 3 -> flowOf(ClassroomLoadState.Loaded(emptyList()))
                else -> repository.observeMyClassrooms(schoolId, currentUserId)
                    .map<List<Classroom>, ClassroomLoadState> { ClassroomLoadState.Loaded(it) }
                    .onStart { emit(ClassroomLoadState.Loading) }
                    .catch { error ->
                        if (isRealCancellation(error)) throw error
                        val fsErr = unwrapFirestore(error)
                        val isPerm = fsErr?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED
                        Log.w(TAG, "Classrooms stream error for school=$schoolId", fsErr ?: error)
                        emit(
                            ClassroomLoadState.Error(
                                message = formatFirestoreErrorMessage(
                                    fsErr ?: error,
                                    "Failed to load classrooms."
                                ),
                                isPermissionDenied = isPerm
                            )
                        )
                    }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ClassroomLoadState.Loading)

    val workspaceState: StateFlow<WorkspaceState> = combine(
        profileLoadFlow,
        classroomsLoadFlow,
        forceSetupMode
    ) { profileLoad, classroomLoad, openSetup ->
        when (profileLoad) {
            ProfileLoadState.Loading -> WorkspaceState.Loading
            is ProfileLoadState.Error -> WorkspaceState.Error(
                message = profileLoad.message,
                isPermissionDenied = profileLoad.isPermissionDenied
            )
            is ProfileLoadState.Loaded -> {
                val profile = profileLoad.profile
                val boundSchoolId = AttendanceRepository.normalizeSchoolId(profile?.schoolId.orEmpty())
                if (openSetup || boundSchoolId.length < 3) {
                    WorkspaceState.NeedsClassroom(boundSchoolId = boundSchoolId)
                } else {
                    when (classroomLoad) {
                        ClassroomLoadState.Loading -> WorkspaceState.Loading
                        is ClassroomLoadState.Error -> {
                            if (classroomLoad.isPermissionDenied) {
                                WorkspaceState.NeedsClassroom(
                                    boundSchoolId = boundSchoolId,
                                    setupNotice = classroomLoad.message
                                )
                            } else {
                                WorkspaceState.Error(
                                    message = classroomLoad.message,
                                    isPermissionDenied = false,
                                    boundSchoolId = boundSchoolId
                                )
                            }
                        }
                        is ClassroomLoadState.Loaded -> {
                            val classrooms = classroomLoad.classrooms
                            if (classrooms.isEmpty()) {
                                WorkspaceState.NeedsClassroom(boundSchoolId = boundSchoolId)
                            } else {
                                val active = classrooms.firstOrNull { it.id == profile?.activeClassroomId }
                                    ?: classrooms.first()
                                val isOwnerCoord = active.isUserCoordinator(currentUserId)
                                val role = if (isOwnerCoord) {
                                    UserRole.fromKey(profile?.activeRole)
                                } else {
                                    UserRole.REPRESENTATIVE
                                }
                                syncClassroomSelectionsIfNeeded(active)
                                syncNewMembersIfCoordinator(active)
                                WorkspaceState.Ready(
                                    profile = profile,
                                    classrooms = classrooms,
                                    activeClassroom = active,
                                    effectiveRole = role,
                                    isOwnerCoordinator = isOwnerCoord
                                )
                            }
                        }
                    }
                }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), WorkspaceState.Loading)

    private val activeClassroomKeyFlow = combine(workspaceState, retryTrigger) { ws, retryNonce ->
        val cls = (ws as? WorkspaceState.Ready)?.activeClassroom
        if (cls == null) null else Triple(cls.schoolId, cls.id, retryNonce)
    }.distinctUntilChanged()

    val students: StateFlow<List<Student>> = activeClassroomKeyFlow
        .flatMapLatest { key ->
            if (key == null || key.first.isBlank() || key.second.isBlank()) {
                flowOf(emptyList())
            } else {
                repository.observeStudents(key.first, key.second, currentUserId)
                    .catch { error ->
                        if (isRealCancellation(error)) throw error
                        val fsErr = unwrapFirestore(error)
                        Log.w(TAG, "Students stream error", fsErr ?: error)
                        if (fsErr?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                            _actionState.update {
                                it.copy(
                                    attendanceError = formatFirestoreErrorMessage(
                                        fsErr,
                                        "Missing permission to read students for ${key.first}."
                                    )
                                )
                            }
                        }
                        emit(emptyList())
                    }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val sessions: StateFlow<List<AttendanceSession>> = activeClassroomKeyFlow
        .flatMapLatest { key ->
            if (key == null || key.first.isBlank() || key.second.isBlank()) {
                flowOf(emptyList())
            } else {
                repository.observeSessions(key.first, key.second, currentUserId)
                    .catch { error ->
                        if (isRealCancellation(error)) throw error
                        val fsErr = unwrapFirestore(error)
                        Log.w(TAG, "Sessions stream error", fsErr ?: error)
                        if (fsErr?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                            _actionState.update {
                                it.copy(
                                    attendanceError = formatFirestoreErrorMessage(
                                        fsErr,
                                        "Missing permission to read attendance sessions for ${key.first}."
                                    )
                                )
                            }
                        }
                        emit(emptyList())
                    }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val activeSession: StateFlow<AttendanceSession?> = combine(
        sessions,
        _actionState
    ) { allSessions, action ->
        allSessions.firstOrNull {
            it.dateIso == action.selectedDateIso &&
                it.subject == action.selectedSubject &&
                it.timing == action.selectedTiming
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /**
     * Unfiltered overall attendance stats per student ID, used to highlight students below 75%
     * across student lists regardless of active report filters.
     */
    val overallStudentReports: StateFlow<Map<String, StudentReportItem>> = combine(
        students,
        sessions
    ) { studentList, sessionList ->
        computeStudentReports(studentList, sessionList).associateBy { it.student.id }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyMap())

    val reportsSummary: StateFlow<ReportsSummary> = combine(
        students,
        sessions,
        _actionState,
        workspaceState
    ) { studentList, sessionList, action, ws ->
        val today = currentDateIso()
        val weekStart = dateDaysAgoIso(6)

        val filtered = sessionList.filter { session ->
            val matchesSubject = action.reportSubjectFilter == null ||
                session.subject == action.reportSubjectFilter
            val matchesDate = when (action.reportDateFilter) {
                ReportDateFilter.ALL -> true
                ReportDateFilter.TODAY -> session.dateIso == today
                ReportDateFilter.LAST_7_DAYS -> session.dateIso in weekStart..today
                ReportDateFilter.CUSTOM_DATE -> session.dateIso == action.reportCustomDateIso
            }
            matchesSubject && matchesDate
        }

        val reports = computeStudentReports(studentList, filtered)
        val classroomSubjects = (ws as? WorkspaceState.Ready)?.activeClassroom?.subjects.orEmpty()
        val allSubjects = (classroomSubjects + sessionList.map { it.subject })
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        val activeReports = reports.filter { it.totalSessionsMarked > 0 }
        val avg = if (activeReports.isNotEmpty()) {
            activeReports.map { it.attendancePercentage }.average().toFloat()
        } else {
            0f
        }
        val atRisk = activeReports.count { it.attendancePercentage < 75f }

        ReportsSummary(
            filteredSessions = filtered,
            studentReports = reports,
            availableSubjects = allSubjects,
            averageAttendancePercent = avg,
            atRiskCount = atRisk
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ReportsSummary())

    private fun computeStudentReports(
        studentList: List<Student>,
        sessionList: List<AttendanceSession>
    ): List<StudentReportItem> {
        return studentList.map { student ->
            var present = 0
            var absent = 0
            sessionList.forEach { sess ->
                when (sess.records[student.id]) {
                    "P" -> present++
                    "A" -> absent++
                }
            }
            val total = present + absent
            val pct = if (total > 0) (present * 100f) / total else 0f
            StudentReportItem(
                student = student,
                presentCount = present,
                absentCount = absent,
                totalSessionsMarked = total,
                attendancePercentage = pct
            )
        }
    }

    private fun syncClassroomSelectionsIfNeeded(classroom: Classroom) {
        _actionState.update { state ->
            val validSubject = if (classroom.subjects.contains(state.selectedSubject)) {
                state.selectedSubject
            } else {
                classroom.subjects.firstOrNull() ?: ""
            }
            val validTiming = if (classroom.timings.contains(state.selectedTiming)) {
                state.selectedTiming
            } else {
                classroom.timings.firstOrNull() ?: state.selectedTiming
            }
            state.copy(selectedSubject = validSubject, selectedTiming = validTiming)
        }
    }

    private fun syncNewMembersIfCoordinator(classroom: Classroom) {
        if (!classroom.isUserCoordinator(currentUserId)) return
        val signature = "${classroom.schoolId}:${classroom.id}:${classroom.memberIds.sorted().joinToString(",")}"
        if (lastSyncedMemberHash == signature) return
        lastSyncedMemberHash = signature
        viewModelScope.launch {
            repository.syncMemberIdsToClassroomDocs(classroom)
                .onFailure { Log.w(TAG, "Member sync failed", it) }
        }
    }

    fun bindSchoolCode(rawSchoolId: String) {
        if (_actionState.value.isCreatingOrJoiningClassroom) return
        viewModelScope.launch {
            _actionState.update {
                it.copy(
                    isCreatingOrJoiningClassroom = true,
                    classroomActionError = null,
                    schoolBindSuccessMessage = null
                )
            }
            repository.bindSchoolCode(rawSchoolId, currentUserName, currentUserEmail)
                .onSuccess { profile ->
                    _actionState.update {
                        it.copy(
                            schoolBindSuccessMessage = "School Code \"${profile.schoolId}\" linked to your profile. You can now create or join classrooms."
                        )
                    }
                }
                .onFailure { err ->
                    _actionState.update {
                        it.copy(
                            classroomActionError = formatFirestoreErrorMessage(
                                err,
                                "Failed to bind School Code."
                            )
                        )
                    }
                }
            _actionState.update { it.copy(isCreatingOrJoiningClassroom = false) }
        }
    }

    fun retrySync() {
        forceSetupMode.value = false
        viewModelScope.launch {
            repository.ensureUserProfile(currentUserId, currentUserName, currentUserEmail)
        }
        retryTrigger.update { it + 1 }
    }

    fun openSchoolSetup() {
        forceSetupMode.value = true
    }

    fun closeSchoolSetup() {
        forceSetupMode.value = false
    }

    fun createClassroom(schoolId: String, name: String) {
        if (_actionState.value.isCreatingOrJoiningClassroom) return
        viewModelScope.launch {
            _actionState.update {
                it.copy(
                    isCreatingOrJoiningClassroom = true,
                    classroomActionError = null,
                    schoolBindSuccessMessage = null
                )
            }
            repository.createClassroom(
                rawSchoolId = schoolId,
                name = name,
                displayName = currentUserName,
                email = currentUserEmail
            )
                .onSuccess {
                    forceSetupMode.value = false
                }
                .onFailure { err ->
                    _actionState.update {
                        it.copy(
                            classroomActionError = formatFirestoreErrorMessage(
                                err,
                                "Failed to create classroom."
                            )
                        )
                    }
                }
            _actionState.update { it.copy(isCreatingOrJoiningClassroom = false) }
        }
    }

    fun joinClassroom(schoolId: String, code: String) {
        if (_actionState.value.isCreatingOrJoiningClassroom) return
        viewModelScope.launch {
            _actionState.update {
                it.copy(
                    isCreatingOrJoiningClassroom = true,
                    classroomActionError = null,
                    schoolBindSuccessMessage = null
                )
            }
            repository.joinClassroomByCode(
                rawSchoolId = schoolId,
                rawCode = code,
                displayName = currentUserName,
                email = currentUserEmail
            )
                .onSuccess {
                    forceSetupMode.value = false
                }
                .onFailure { err ->
                    _actionState.update {
                        it.copy(
                            classroomActionError = formatFirestoreErrorMessage(
                                err,
                                "Could not join classroom."
                            )
                        )
                    }
                }
            _actionState.update { it.copy(isCreatingOrJoiningClassroom = false) }
        }
    }

    fun switchClassroom(classroom: Classroom) {
        viewModelScope.launch {
            val role = if (classroom.isUserCoordinator(currentUserId)) {
                UserRole.COORDINATOR
            } else {
                UserRole.REPRESENTATIVE
            }
            repository.updateActiveRoleAndClassroom(currentUserId, classroom.id, role)
        }
    }

    fun switchRolePreview(role: UserRole) {
        val ready = workspaceState.value as? WorkspaceState.Ready ?: return
        if (!ready.isOwnerCoordinator) return
        viewModelScope.launch {
            repository.updateActiveRoleAndClassroom(currentUserId, ready.activeClassroom.id, role)
        }
    }

    fun clearClassroomActionError() {
        _actionState.update {
            it.copy(classroomActionError = null, schoolBindSuccessMessage = null)
        }
    }

    fun selectSubject(subject: String) {
        _actionState.update { it.copy(selectedSubject = subject) }
    }

    fun selectTiming(timing: String) {
        _actionState.update { it.copy(selectedTiming = timing) }
    }

    fun selectDateIso(dateIso: String) {
        _actionState.update { it.copy(selectedDateIso = dateIso) }
    }

    fun addCustomSubject(subject: String) {
        val ready = workspaceState.value as? WorkspaceState.Ready ?: return
        if (ready.effectiveRole != UserRole.COORDINATOR) return
        val clean = subject.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            repository.addSubject(ready.activeClassroom, clean)
                .onSuccess {
                    _actionState.update { it.copy(selectedSubject = clean, attendanceError = null) }
                }
                .onFailure { err ->
                    _actionState.update {
                        it.copy(attendanceError = err.localizedMessage ?: "Failed to add subject.")
                    }
                }
        }
    }

    fun removeSubject(subject: String) {
        val ready = workspaceState.value as? WorkspaceState.Ready ?: return
        if (ready.effectiveRole != UserRole.COORDINATOR) return
        val clean = subject.trim()
        if (clean.isEmpty() || _actionState.value.deletingSubject != null) return

        val nextSubject = ready.activeClassroom.subjects.firstOrNull {
            it != subject && it.trim() != clean
        } ?: ""
        val hasRecordedSessionsForSubject = sessions.value.any { it.subject == subject }

        _actionState.update { state ->
            state.copy(
                deletingSubject = subject,
                attendanceError = null,
                selectedSubject = if (state.selectedSubject == subject) nextSubject else state.selectedSubject,
                reportSubjectFilter = if (state.reportSubjectFilter == subject && !hasRecordedSessionsForSubject) {
                    null
                } else {
                    state.reportSubjectFilter
                }
            )
        }

        viewModelScope.launch {
            repository.removeSubject(ready.activeClassroom, subject)
                .onFailure { err ->
                    _actionState.update {
                        it.copy(attendanceError = err.localizedMessage ?: "Failed to remove subject \"$subject\".")
                    }
                }
            _actionState.update { it.copy(deletingSubject = null) }
        }
    }

    fun markAttendance(studentId: String, status: String) {
        val ready = workspaceState.value as? WorkspaceState.Ready ?: return
        val currentSaving = _actionState.value.savingStudentIds
        if (currentSaving.contains(studentId)) return

        val action = _actionState.value
        if (action.selectedSubject.isBlank()) {
            _actionState.update {
                it.copy(attendanceError = "Please add and select a subject before marking attendance.")
            }
            return
        }
        _actionState.update {
            it.copy(
                savingStudentIds = it.savingStudentIds + studentId,
                attendanceError = null
            )
        }

        viewModelScope.launch {
            repository.markStudentAttendance(
                schoolId = ready.activeClassroom.schoolId,
                classroomId = ready.activeClassroom.id,
                dateIso = action.selectedDateIso,
                subject = action.selectedSubject,
                timing = action.selectedTiming,
                studentId = studentId,
                status = status,
                markerName = currentUserName,
                memberIds = ready.activeClassroom.memberIds
            ).onFailure { err ->
                _actionState.update {
                    it.copy(
                        attendanceError = formatFirestoreErrorMessage(
                            err,
                            "Failed to save attendance mark."
                        )
                    )
                }
            }
            _actionState.update {
                it.copy(savingStudentIds = it.savingStudentIds - studentId)
            }
        }
    }

    fun parseSpreadsheetFile(contentResolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            _actionState.update {
                it.copy(
                    isParsingFile = true,
                    uploadErrorMessage = null,
                    uploadSuccessMessage = null,
                    pendingParseResult = null
                )
            }
            val result = withContext(Dispatchers.IO) {
                runCatching { SpreadsheetParser.parseFromUri(contentResolver, uri) }
            }
            result.fold(
                onSuccess = { parsed ->
                    if (parsed.students.isEmpty()) {
                        _actionState.update {
                            it.copy(
                                isParsingFile = false,
                                uploadErrorMessage = "No valid student rows found in '${parsed.fileName}'. Ensure columns include Roll Number and Name."
                            )
                        }
                    } else {
                        _actionState.update {
                            it.copy(
                                isParsingFile = false,
                                pendingParseResult = parsed
                            )
                        }
                    }
                },
                onFailure = { err ->
                    _actionState.update {
                        it.copy(
                            isParsingFile = false,
                            uploadErrorMessage = err.localizedMessage ?: "Failed to parse file."
                        )
                    }
                }
            )
        }
    }

    fun commitPendingUpload() {
        val ready = workspaceState.value as? WorkspaceState.Ready ?: return
        val pending = _actionState.value.pendingParseResult ?: return
        if (_actionState.value.isUploadingStudents) return

        viewModelScope.launch {
            _actionState.update {
                it.copy(
                    isUploadingStudents = true,
                    uploadErrorMessage = null,
                    uploadSuccessMessage = null
                )
            }
            repository.uploadStudentsBatch(
                schoolId = ready.activeClassroom.schoolId,
                classroomId = ready.activeClassroom.id,
                rows = pending.students,
                memberIds = ready.activeClassroom.memberIds
            ).fold(
                onSuccess = { committedCount ->
                    _actionState.update {
                        it.copy(
                            isUploadingStudents = false,
                            pendingParseResult = null,
                            uploadSuccessMessage = "Committed $committedCount students from '${pending.fileName}' to ${ready.activeClassroom.schoolId}."
                        )
                    }
                },
                onFailure = { err ->
                    _actionState.update {
                        it.copy(
                            isUploadingStudents = false,
                            uploadErrorMessage = formatFirestoreErrorMessage(err, "Database commit failed.")
                        )
                    }
                }
            )
        }
    }

    fun dismissPendingUpload() {
        _actionState.update { it.copy(pendingParseResult = null) }
    }

    fun importSampleRoster() {
        val ready = workspaceState.value as? WorkspaceState.Ready ?: return
        if (_actionState.value.isUploadingStudents) return
        viewModelScope.launch {
            _actionState.update {
                it.copy(
                    isUploadingStudents = true,
                    uploadErrorMessage = null,
                    uploadSuccessMessage = null
                )
            }
            repository.uploadStudentsBatch(
                schoolId = ready.activeClassroom.schoolId,
                classroomId = ready.activeClassroom.id,
                rows = SAMPLE_STUDENTS,
                memberIds = ready.activeClassroom.memberIds
            ).fold(
                onSuccess = { count ->
                    _actionState.update {
                        it.copy(
                            isUploadingStudents = false,
                            uploadSuccessMessage = "Imported $count sample students into ${ready.activeClassroom.schoolId}."
                        )
                    }
                },
                onFailure = { err ->
                    _actionState.update {
                        it.copy(
                            isUploadingStudents = false,
                            uploadErrorMessage = formatFirestoreErrorMessage(
                                err,
                                "Failed to import sample roster."
                            )
                        )
                    }
                }
            )
        }
    }

    fun addSingleStudent(rollNumber: String, name: String) {
        val ready = workspaceState.value as? WorkspaceState.Ready ?: return
        viewModelScope.launch {
            _actionState.update { it.copy(isUploadingStudents = true, uploadErrorMessage = null, uploadSuccessMessage = null) }
            repository.uploadStudentsBatch(
                schoolId = ready.activeClassroom.schoolId,
                classroomId = ready.activeClassroom.id,
                rows = listOf(ParsedStudentRow(rollNumber, name)),
                memberIds = ready.activeClassroom.memberIds
            ).fold(
                onSuccess = {
                    _actionState.update {
                        it.copy(
                            isUploadingStudents = false,
                            uploadSuccessMessage = "Added student ${rollNumber.trim()} · ${name.trim()}."
                        )
                    }
                },
                onFailure = { err ->
                    _actionState.update {
                        it.copy(
                            isUploadingStudents = false,
                            uploadErrorMessage = formatFirestoreErrorMessage(err, "Failed to add student.")
                        )
                    }
                }
            )
        }
    }

    fun deleteStudent(studentId: String) {
        val ready = workspaceState.value as? WorkspaceState.Ready ?: return
        viewModelScope.launch {
            repository.deleteStudent(
                schoolId = ready.activeClassroom.schoolId,
                classroomId = ready.activeClassroom.id,
                studentId = studentId
            ).onFailure { err ->
                _actionState.update {
                    it.copy(uploadErrorMessage = formatFirestoreErrorMessage(err, "Failed to remove student."))
                }
            }
        }
    }

    fun clearUploadFeedback() {
        _actionState.update { it.copy(uploadSuccessMessage = null, uploadErrorMessage = null) }
    }

    fun clearAttendanceError() {
        _actionState.update { it.copy(attendanceError = null) }
    }

    fun setReportSubjectFilter(subject: String?) {
        _actionState.update { it.copy(reportSubjectFilter = subject) }
    }

    fun setReportDateFilter(filter: ReportDateFilter) {
        _actionState.update { it.copy(reportDateFilter = filter) }
    }

    fun setReportCustomDate(dateIso: String) {
        _actionState.update {
            it.copy(
                reportCustomDateIso = dateIso,
                reportDateFilter = ReportDateFilter.CUSTOM_DATE
            )
        }
    }

    fun clearReportFilters() {
        _actionState.update {
            it.copy(
                reportSubjectFilter = null,
                reportDateFilter = ReportDateFilter.ALL
            )
        }
    }

    fun buildLiveAttendanceContext(): String {
        val ready = workspaceState.value as? WorkspaceState.Ready ?: return ""
        val summary = reportsSummary.value
        val studentList = students.value
        val atRisk = summary.studentReports
            .filter { it.totalSessionsMarked > 0 && it.attendancePercentage < 75f }
            .joinToString("; ") {
                "${it.student.rollNumber} ${it.student.name} (${it.attendancePercentage.toInt()}%)"
            }
        return buildString {
            appendLine("School / Institution Code: ${ready.activeClassroom.schoolId}")
            appendLine("Classroom: ${ready.activeClassroom.name} (Join Code: ${ready.activeClassroom.joinCode})")
            appendLine("Total Enrolled Students: ${studentList.size}")
            appendLine("Total Recorded Lecture Sessions: ${sessions.value.size}")
            appendLine("Average Attendance: ${"%.1f".format(summary.averageAttendancePercent)}%")
            appendLine("Students Below 75%: ${if (atRisk.isBlank()) "None" else atRisk}")
        }
    }

    private fun unwrapFirestore(error: Throwable): FirebaseFirestoreException? {
        return generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
    }

    private fun isRealCancellation(error: Throwable): Boolean {
        return unwrapFirestore(error) == null && error is CancellationException
    }

    private fun formatFirestoreErrorMessage(error: Throwable, fallback: String): String {
        val fsErr = unwrapFirestore(error)
        return when {
            fsErr?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED -> {
                "Access requires a verified School Code bound to your account and membership in this classroom. Check your School Code or ask your Class Coordinator to confirm your access."
            }
            fsErr?.code == FirebaseFirestoreException.Code.UNAVAILABLE -> {
                "Unable to reach Firestore right now. Check your network connection and tap Retry."
            }
            else -> error.localizedMessage?.takeIf { it.isNotBlank() } ?: fallback
        }
    }

    companion object {
        private const val TAG = "AttendanceVM"
        private const val STOP_TIMEOUT_MS = 5000L

        fun currentDateIso(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

        fun dateDaysAgoIso(days: Int): String {
            val cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_YEAR, -days)
            return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
        }

        val SAMPLE_STUDENTS = listOf(
            ParsedStudentRow("CS101", "Aarav Sharma"),
            ParsedStudentRow("CS102", "Diya Patel"),
            ParsedStudentRow("CS103", "Rohan Verma"),
            ParsedStudentRow("CS104", "Ananya Iyer"),
            ParsedStudentRow("CS105", "Kabir Joshi"),
            ParsedStudentRow("CS106", "Meera Nair"),
            ParsedStudentRow("CS107", "Siddharth Rao"),
            ParsedStudentRow("CS108", "Priya Kulkarni"),
            ParsedStudentRow("CS109", "Arjun Malhotra"),
            ParsedStudentRow("CS110", "Neha Deshmukh"),
            ParsedStudentRow("CS111", "Vikram Singhania"),
            ParsedStudentRow("CS112", "Ishita Banerjee"),
            ParsedStudentRow("CS113", "Aditya Menon"),
            ParsedStudentRow("CS114", "Sneha Reddy"),
            ParsedStudentRow("CS115", "Karan Kapoor")
        )
    }
}
