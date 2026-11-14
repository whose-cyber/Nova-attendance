package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.auth.attemptAutoSignIn
import com.example.auth.authStateFlow
import com.example.auth.signOut
import com.example.data.model.Classroom
import com.example.data.model.UserRole
import com.example.data.repository.AttendanceRepository
import com.example.firebase.configureAppCheck
import com.example.ui.screens.AiAssistantScreen
import com.example.ui.screens.AppDestination
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.MarkAttendanceScreen
import com.example.ui.screens.ReportsScreen
import com.example.ui.screens.UploadStudentsScreen
import com.example.ui.screens.WorkspaceSetupScreen
import com.example.ui.theme.NovaAttendTheme
import com.example.ui.viewmodel.AttendanceAiViewModel
import com.example.ui.viewmodel.AttendanceViewModel
import com.example.ui.viewmodel.WorkspaceState
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Configure App Check with launch intent before rendering UI or initializing ViewModels
        configureAppCheck(this, intent)
        enableEdgeToEdge()

        setContent {
            NovaAttendTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation()
                }
            }
        }
    }
}

@Composable
fun AppNavigation() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }
    val currentUser by Firebase.auth.authStateFlow()
        .collectAsStateWithLifecycle(initialValue = Firebase.auth.currentUser)

    LaunchedEffect(Unit) {
        if (Firebase.auth.currentUser == null) {
            attemptAutoSignIn(
                context = context,
                credentialManager = credentialManager,
                onAuthSuccess = {},
                onUnauthenticated = {},
                scope = scope
            )
        }
    }

    val user = currentUser
    if (user == null) {
        AuthScreen(onAuthSuccess = {})
    } else {
        key(user.uid) {
            AuthenticatedShell(
                currentUserId = user.uid,
                currentUserName = user.displayName ?: user.email?.substringBefore("@") ?: "Staff",
                currentUserEmail = user.email.orEmpty(),
                onSignOut = {
                    signOut(credentialManager = credentialManager, scope = scope)
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthenticatedShell(
    currentUserId: String,
    currentUserName: String,
    currentUserEmail: String,
    onSignOut: () -> Unit
) {
    val context = LocalContext.current
    val attendanceViewModel: AttendanceViewModel = viewModel(
        key = currentUserId,
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY])
                val databaseId = app.getString(R.string.firestore_database_id)
                val db = FirebaseFirestore.getInstance(databaseId)
                AttendanceViewModel(
                    repository = AttendanceRepository(db),
                    currentUserId = currentUserId,
                    currentUserName = currentUserName,
                    currentUserEmail = currentUserEmail
                )
            }
        }
    )

    val aiViewModel: AttendanceAiViewModel = viewModel(key = "ai_$currentUserId")

    val workspaceState by attendanceViewModel.workspaceState.collectAsStateWithLifecycle()
    val actionState by attendanceViewModel.actionState.collectAsStateWithLifecycle()
    val students by attendanceViewModel.students.collectAsStateWithLifecycle()
    val sessions by attendanceViewModel.sessions.collectAsStateWithLifecycle()
    val activeSession by attendanceViewModel.activeSession.collectAsStateWithLifecycle()
    val overallStudentReports by attendanceViewModel.overallStudentReports.collectAsStateWithLifecycle()
    val reportsSummary by attendanceViewModel.reportsSummary.collectAsStateWithLifecycle()
    val aiUiState by aiViewModel.uiState.collectAsStateWithLifecycle()

    var currentDestination by remember { mutableStateOf(AppDestination.DASHBOARD) }
    var showAccountMenu by remember { mutableStateOf(false) }
    var showSwitchClassroomDialog by remember { mutableStateOf(false) }

    if (currentDestination != AppDestination.DASHBOARD) {
        BackHandler {
            currentDestination = AppDestination.DASHBOARD
        }
    }

    when (val ws = workspaceState) {
        WorkspaceState.Loading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Connecting to Firestore workspace...", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        is WorkspaceState.Error -> {
            Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (ws.isPermissionDenied) {
                            "School / Classroom Permissions Required"
                        } else {
                            "Workspace Sync Unavailable"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = ws.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row {
                        Button(onClick = attendanceViewModel::retrySync) {
                            Text("Retry Sync")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedButton(onClick = attendanceViewModel::openSchoolSetup) {
                            Text("School Setup")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedButton(onClick = onSignOut) { Text("Sign Out") }
                    }
                }
            }
        }
        is WorkspaceState.NeedsClassroom -> {
            Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { innerPadding ->
                WorkspaceSetupScreen(
                    userName = currentUserName,
                    boundSchoolId = ws.boundSchoolId,
                    isLoading = actionState.isCreatingOrJoiningClassroom,
                    errorMessage = actionState.classroomActionError,
                    onCreateClassroom = attendanceViewModel::createClassroom,
                    onJoinClassroom = attendanceViewModel::joinClassroom,
                    onSignOut = onSignOut,
                    modifier = Modifier.padding(innerPadding),
                    setupNotice = ws.setupNotice,
                    bindSuccessMessage = actionState.schoolBindSuccessMessage,
                    onBindSchoolCode = attendanceViewModel::bindSchoolCode,
                    onRetrySync = attendanceViewModel::retrySync
                )
            }
        }
        is WorkspaceState.Ready -> {
            if (showSwitchClassroomDialog) {
                ClassroomManageDialog(
                    classrooms = ws.classrooms,
                    activeClassroom = ws.activeClassroom,
                    isLoading = actionState.isCreatingOrJoiningClassroom,
                    errorMessage = actionState.classroomActionError,
                    onSelectClassroom = {
                        attendanceViewModel.switchClassroom(it)
                        showSwitchClassroomDialog = false
                    },
                    onCreateClassroom = { name ->
                        attendanceViewModel.createClassroom(ws.activeClassroom.schoolId, name)
                        showSwitchClassroomDialog = false
                    },
                    onJoinClassroom = { code ->
                        attendanceViewModel.joinClassroom(ws.activeClassroom.schoolId, code)
                        showSwitchClassroomDialog = false
                    },
                    onDismiss = {
                        attendanceViewModel.clearClassroomActionError()
                        showSwitchClassroomDialog = false
                    }
                )
            }

            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val useNavRail = maxWidth >= 600.dp

                Scaffold(
                    contentWindowInsets = WindowInsets.safeDrawing,
                    topBar = {
                        TopAppBar(
                            title = {
                                Column {
                                    Text(
                                        text = stringResource(R.string.app_name),
                                        style = MaterialTheme.typography.titleLarge
                                    )
                                    Text(
                                        text = "${ws.activeClassroom.schoolId} · ${ws.activeClassroom.name} · ${ws.effectiveRole.shortLabel}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.background
                            ),
                            actions = {
                                IconButton(
                                    onClick = { showSwitchClassroomDialog = true },
                                    modifier = Modifier.testTag("switch_classroom_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.SwapHoriz,
                                        contentDescription = "Switch or join classroom"
                                    )
                                }
                                Box {
                                    IconButton(
                                        onClick = { showAccountMenu = true },
                                        modifier = Modifier.testTag("account_menu_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                                            contentDescription = "Account options"
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = showAccountMenu,
                                        onDismissRequest = { showAccountMenu = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("$currentUserName\n$currentUserEmail") },
                                            onClick = { showAccountMenu = false }
                                        )
                                        if (ws.isOwnerCoordinator) {
                                            val targetRole = if (ws.effectiveRole == UserRole.COORDINATOR) {
                                                UserRole.REPRESENTATIVE
                                            } else {
                                                UserRole.COORDINATOR
                                            }
                                            DropdownMenuItem(
                                                text = { Text("Preview as ${targetRole.shortLabel}") },
                                                onClick = {
                                                    showAccountMenu = false
                                                    attendanceViewModel.switchRolePreview(targetRole)
                                                }
                                            )
                                        }
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.sign_out)) },
                                            onClick = {
                                                showAccountMenu = false
                                                onSignOut()
                                            }
                                        )
                                    }
                                }
                            }
                        )
                    },
                    bottomBar = {
                        if (!useNavRail) {
                            NavigationBar {
                                NavItems(
                                    currentDestination = currentDestination,
                                    onSelect = { currentDestination = it },
                                    isRail = false
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    Row(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                        if (useNavRail) {
                            NavigationRail {
                                NavItems(
                                    currentDestination = currentDestination,
                                    onSelect = { currentDestination = it },
                                    isRail = true
                                )
                            }
                        }

                        Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                            when (currentDestination) {
                                AppDestination.DASHBOARD -> DashboardScreen(
                                    classroom = ws.activeClassroom,
                                    effectiveRole = ws.effectiveRole,
                                    isOwnerCoordinator = ws.isOwnerCoordinator,
                                    students = students,
                                    sessions = sessions,
                                    reportsSummary = reportsSummary,
                                    onNavigate = { currentDestination = it },
                                    onToggleRolePreview = attendanceViewModel::switchRolePreview
                                )
                                AppDestination.MARK_ATTENDANCE -> {
                                    val sessionsCountBySubject = remember(sessions) {
                                        sessions.groupingBy { it.subject }.eachCount()
                                    }
                                    MarkAttendanceScreen(
                                        classroom = ws.activeClassroom,
                                        effectiveRole = ws.effectiveRole,
                                        students = students,
                                        overallStudentReports = overallStudentReports,
                                        sessionsCountBySubject = sessionsCountBySubject,
                                        activeSession = activeSession,
                                        actionState = actionState,
                                        onSelectSubject = attendanceViewModel::selectSubject,
                                        onSelectTiming = attendanceViewModel::selectTiming,
                                        onSelectDateIso = attendanceViewModel::selectDateIso,
                                        onAddCustomSubject = attendanceViewModel::addCustomSubject,
                                        onRemoveSubject = attendanceViewModel::removeSubject,
                                        onMarkAttendance = attendanceViewModel::markAttendance,
                                        onClearError = attendanceViewModel::clearAttendanceError,
                                        onGoToUpload = { currentDestination = AppDestination.UPLOAD_STUDENTS }
                                    )
                                }
                                AppDestination.UPLOAD_STUDENTS -> UploadStudentsScreen(
                                    effectiveRole = ws.effectiveRole,
                                    students = students,
                                    overallStudentReports = overallStudentReports,
                                    actionState = actionState,
                                    onPickSpreadsheet = { uri ->
                                        attendanceViewModel.parseSpreadsheetFile(context.contentResolver, uri)
                                    },
                                    onCommitPendingUpload = attendanceViewModel::commitPendingUpload,
                                    onDismissPendingUpload = attendanceViewModel::dismissPendingUpload,
                                    onImportSampleRoster = attendanceViewModel::importSampleRoster,
                                    onAddSingleStudent = attendanceViewModel::addSingleStudent,
                                    onDeleteStudent = attendanceViewModel::deleteStudent,
                                    onClearFeedback = attendanceViewModel::clearUploadFeedback
                                )
                                AppDestination.REPORTS -> ReportsScreen(
                                    classroom = ws.activeClassroom,
                                    students = students,
                                    reportsSummary = reportsSummary,
                                    actionState = actionState,
                                    onSelectSubjectFilter = attendanceViewModel::setReportSubjectFilter,
                                    onSelectDateFilter = attendanceViewModel::setReportDateFilter,
                                    onSelectCustomDate = attendanceViewModel::setReportCustomDate,
                                    onClearFilters = attendanceViewModel::clearReportFilters
                                )
                                AppDestination.AI_ASSISTANT -> AiAssistantScreen(
                                    uiState = aiUiState,
                                    liveClassroomContext = attendanceViewModel.buildLiveAttendanceContext(),
                                    onSendChatMessage = aiViewModel::sendChatMessage,
                                    onRetryLastMessage = aiViewModel::retryLastMessage,
                                    onToggleFastLiteModel = aiViewModel::setPreferFastLiteModel,
                                    onResetChat = aiViewModel::resetChat,
                                    onClearChatError = aiViewModel::clearChatError,
                                    onSelectImage = aiViewModel::selectImageForAnalysis,
                                    onAnalyzeImage = aiViewModel::analyzeSelectedImage
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.NavBarRowItems(
    currentDestination: AppDestination,
    onSelect: (AppDestination) -> Unit
) {
    val items = listOf(
        Triple(AppDestination.DASHBOARD, R.string.nav_dashboard, Icons.Filled.Dashboard),
        Triple(AppDestination.MARK_ATTENDANCE, R.string.nav_mark, Icons.AutoMirrored.Filled.FactCheck),
        Triple(AppDestination.UPLOAD_STUDENTS, R.string.nav_upload, Icons.Filled.UploadFile),
        Triple(AppDestination.REPORTS, R.string.nav_reports, Icons.Filled.Assessment),
        Triple(AppDestination.AI_ASSISTANT, R.string.nav_ai, Icons.Filled.AutoAwesome)
    )
    items.forEach { (dest, labelRes, icon) ->
        NavigationBarItem(
            selected = currentDestination == dest,
            onClick = { onSelect(dest) },
            icon = { Icon(icon, contentDescription = stringResource(labelRes)) },
            label = { Text(stringResource(labelRes)) }
        )
    }
}

@Composable
private fun NavItems(
    currentDestination: AppDestination,
    onSelect: (AppDestination) -> Unit,
    isRail: Boolean
) {
    val items = listOf(
        Triple(AppDestination.DASHBOARD, R.string.nav_dashboard, Icons.Filled.Dashboard),
        Triple(AppDestination.MARK_ATTENDANCE, R.string.nav_mark, Icons.AutoMirrored.Filled.FactCheck),
        Triple(AppDestination.UPLOAD_STUDENTS, R.string.nav_upload, Icons.Filled.UploadFile),
        Triple(AppDestination.REPORTS, R.string.nav_reports, Icons.Filled.Assessment),
        Triple(AppDestination.AI_ASSISTANT, R.string.nav_ai, Icons.Filled.AutoAwesome)
    )
    if (isRail) {
        items.forEach { (dest, labelRes, icon) ->
            NavigationRailItem(
                selected = currentDestination == dest,
                onClick = { onSelect(dest) },
                icon = { Icon(icon, contentDescription = stringResource(labelRes)) },
                label = { Text(stringResource(labelRes)) }
            )
        }
    } else {
        Row(modifier = Modifier.fillMaxWidth()) {
            NavBarRowItems(currentDestination = currentDestination, onSelect = onSelect)
        }
    }
}

@Composable
private fun ClassroomManageDialog(
    classrooms: List<Classroom>,
    activeClassroom: Classroom,
    isLoading: Boolean,
    errorMessage: String?,
    onSelectClassroom: (Classroom) -> Unit,
    onCreateClassroom: (String) -> Unit,
    onJoinClassroom: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var newName by remember { mutableStateOf("") }
    var joinCode by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Classrooms & Sync") },
        text = {
            Column {
                if (!errorMessage.isNullOrBlank()) {
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Text(
                    text = "Institution: ${activeClassroom.schoolId}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Classrooms are strictly isolated under this School Code.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text("Your Classrooms", style = MaterialTheme.typography.labelLarge)
                Spacer(modifier = Modifier.height(6.dp))
                classrooms.forEach { cls ->
                    val isSelected = cls.id == activeClassroom.id
                    OutlinedButton(
                        onClick = { onSelectClassroom(cls) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    ) {
                        Text(
                            text = if (isSelected) "✓ ${cls.name} (${cls.joinCode})" else "${cls.name} (${cls.joinCode})"
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = joinCode,
                    onValueChange = { joinCode = it.uppercase() },
                    label = { Text("Join with Class Code") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(6.dp))
                Button(
                    onClick = { onJoinClassroom(joinCode) },
                    enabled = !isLoading && joinCode.trim().length >= 4,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Join Classroom")
                }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Create New Classroom") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedButton(
                    onClick = { onCreateClassroom(newName) },
                    enabled = !isLoading && newName.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Add, null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Create")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}
