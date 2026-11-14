# NovaAttend — Real-Time Attendance & Academic Insights

A native Android (**Kotlin + Jetpack Compose**) attendance management application built with **Firebase Authentication (Google Sign-In)**, **Cloud Firestore (real-time `addSnapshotListener` sync)**, **Firebase App Check**, and **Firebase AI Logic (Gemini)**.

---

## Key Features

1. **Multi-School Tenant Isolation & Role-Based Access Control (RBAC)**
   - **School / Institution Code Isolation**: Classrooms, invites, rosters, attendance sessions, and reports are strictly scoped under `/schools/{schoolId}/classrooms/{classroomId}/...` (e.g., `DPS_BALLABGARH`, `GPC_SEC56`). Users bound to one School Code cannot view, join, or mutate classrooms or records from another institution.
   - **Google Sign-In (`CredentialManager`)**: Passwordless, secure authentication with silent auto-sign-in and interactive fallback.
   - **Class Coordinator**: Full access — initialize school classrooms, share Class Join Codes, upload student rosters from `.xlsx` / `.csv`, add/remove subjects, mark attendance, view/export CSV reports, and use Gemini AI tools.
   - **Class Representative**: Join a classroom within their School Code using its Class Join Code, mark today's attendance in real time, view consolidated reports, and use Gemini AI tools. Roster and subject mutations are blocked both in the UI and in `firestore.rules`.

2. **Upload Student Data (`.xlsx` & `.csv`)**
   - Uses Android's zero-permission Storage Access Framework (`OpenDocument`) to pick Excel (`.xlsx`) or `.csv` files.
   - Pure-Kotlin `SpreadsheetParser` parses OpenXML `.xlsx` (`xl/sharedStrings.xml` + `xl/worksheets/sheet1.xml`) and RFC-4180 `.csv` files, detects `Roll Number` and `Name` columns, reports skipped/duplicate rows, previews parsed students, and commits via batched Firestore writes before confirming.
   - Includes a one-tap **Import Sample Roster (15 Students)** and **Export Sample CSV** helper for rapid testing.

3. **Mark Today's Attendance (Real-Time Cross-User Sync)**
   - Select **Subject**, **Lecture Timing**, and **Date**.
   - Scrollable student list with Roll Number, Name, and **`P` (Present)** / **`A` (Absent)** buttons.
   - Every tap executes an atomic Firestore transaction with a per-row saving indicator to prevent double-taps.
   - Powered by Firestore `snapshots()` listeners so changes made by the Coordinator appear immediately on the Representative's device (and vice versa).

4. **Consolidated Attendance Reports & CSV Export**
   - Filter sessions by **Subject** and **Date** (`All Dates`, `Today`, `Last 7 Days`, or custom **Pick Date** calendar).
   - View class average attendance, total sessions held, students below the **75% threshold** with a clear **`!` warning badge** (shown in both reports and student lists), individual student attendance percentages, and expandable session-by-session logs.
   - **Download CSV** (Storage Access Framework `CreateDocument`) or **Share CSV** (`FileProvider`) compiling Roll Number, Name, Present Count, Absent Count, Total Classes, Attendance Percentage, and Status.

5. **Gemini AI Assistant & Attendance Sheet Photo Analyzer**
   - **Multi-turn Gemini Chatbot (`gemini-3.8-flash` with `gemini-3.5-flash-lite` fallback)**: Streaming assistant with live classroom attendance context, `Fast Lite` mode toggle, automatic high-demand/timeout fallback, and inline Retry support.
   - **Attendance Sheet Photo Analyzer**: Pick a photo via Android Photo Picker (`PickVisualMedia`) and analyze handwritten/printed attendance sheets or rosters with automatic model fallback.

---

## Tech Stack

- **Language & UI**: Kotlin, Jetpack Compose, Material Design 3, Edge-to-Edge WindowInsets
- **Architecture**: MVVM + Repository Pattern + Kotlin Coroutines & StateFlow
- **Backend & Sync**: Firebase Authentication (Google Sign-In via Jetpack Credential Manager), Cloud Firestore (`snapshots()`), Firebase App Check
- **AI**: Firebase AI Logic (`Firebase.ai` with `gemini-3.8-flash` and `gemini-3.5-flash-lite` fallback)
- **Fonts**: Plus Jakarta Sans & JetBrains Mono (bundled in `res/font/`)

---

## Sample Excel / CSV Structure

Create a `.xlsx` or `.csv` file with the following columns (header order is auto-detected):

| Roll Number | Name |
| :--- | :--- |
| CS101 | Aarav Sharma |
| CS102 | Diya Patel |
| CS103 | Rohan Verma |
| CS104 | Ananya Iyer |
| CS105 | Kabir Joshi |

---

## Testing Roles & Credentials

- Sign in using **Sign in with Google** (no sign-up or forgot-password flows).
- **Testing Class Coordinator**: Choose **Create Classroom as Coordinator** on the Workspace Setup card. Share the generated 6-character **Class Join Code** (e.g., `NV4821`).
- **Testing Class Representative**: Sign in with a second Google account (or use the Role Preview toggle on the top bar for single-device evaluation) and enter the **Class Join Code** to join the same classroom and observe live `onSnapshot` attendance sync.

---

## Known Limitations

- Requires network connectivity for initial Google Sign-In and Firebase AI Logic calls (Firestore caches recent snapshots locally while offline).
- `.xlsx` parsing reads the first worksheet (`sheet1.xml`) and expects tabular `Roll Number` and `Name` columns.
- Legacy `.xls` (BIFF8 binary) files are not supported; save as `.xlsx` or `.csv`.
