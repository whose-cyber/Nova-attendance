const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const DATABASE_ID = "ai-studio-android-novaatte-0b53866d-61dc-4977-9a5f-5f5e8ed12f59";
const COORDINATOR_UID = "coord_alice";
const REP_UID = "rep_bob";
const OTHER_SCHOOL_UID = "coord_gpc_charlie";
const SCHOOL_DPS = "DPS_BALLABGARH";
const SCHOOL_GPC = "GPC_SEC56";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function dbFor(uid) {
  return uid
    ? testEnv.authenticatedContext(uid).firestore(DATABASE_ID)
    : testEnv.unauthenticatedContext().firestore(DATABASE_ID);
}

function nowPastDate() {
  return new Date(Date.now() - 1000);
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated caller cannot read or write any multi-school collection", async () => {
  const unauthDb = dbFor(null);
  await assertFails(unauthDb.collection("users").doc(COORDINATOR_UID).get());
  await assertFails(
    unauthDb
      .collection("schools")
      .doc(SCHOOL_DPS)
      .collection("classrooms")
      .where("memberIds", "array-contains", COORDINATOR_UID)
      .get()
  );
  await assertFails(
    unauthDb.collection("schools").doc(SCHOOL_DPS).collection("invites").doc("NOVA26").get()
  );
});

test("User profile: binds schoolId once from empty and prevents cross-school hijacking", async () => {
  const aliceDb = dbFor(COORDINATOR_UID);
  const eveDb = dbFor(OTHER_SCHOOL_UID);
  const ts = nowPastDate();

  await assertSucceeds(
    aliceDb.collection("users").doc(COORDINATOR_UID).set({
      userId: COORDINATOR_UID,
      displayName: "Alice Coordinator",
      email: "alice@dps.edu",
      schoolId: "",
      activeClassroomId: "",
      activeRole: "coordinator",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Outsider cannot read Alice's profile (PII isolation)
  await assertFails(eveDb.collection("users").doc(COORDINATOR_UID).get());

  // Bind schoolId once from '' -> DPS_BALLABGARH
  await assertSucceeds(
    aliceDb.collection("users").doc(COORDINATOR_UID).update({
      schoolId: SCHOOL_DPS,
      updatedAt: nowPastDate(),
    })
  );

  // Cannot switch bound schoolId to another school (GPC_SEC56)
  await assertFails(
    aliceDb.collection("users").doc(COORDINATOR_UID).update({
      schoolId: SCHOOL_GPC,
      updatedAt: nowPastDate(),
    })
  );

  // Shadow update with ghost field must fail
  await assertFails(
    aliceDb.collection("users").doc(COORDINATOR_UID).update({
      activeRole: "representative",
      isAdmin: true,
      updatedAt: nowPastDate(),
    })
  );
});

test("Multi-school RBAC & tenant isolation across DPS_BALLABGARH and GPC_SEC56", async () => {
  const dpsCoordDb = dbFor(COORDINATOR_UID);
  const dpsRepDb = dbFor(REP_UID);
  const gpcCoordDb = dbFor(OTHER_SCHOOL_UID);
  const ts = nowPastDate();

  // Seed user profiles
  await assertSucceeds(
    dpsCoordDb.collection("users").doc(COORDINATOR_UID).set({
      userId: COORDINATOR_UID,
      displayName: "Alice DPS",
      email: "alice@dps.edu",
      schoolId: SCHOOL_DPS,
      activeClassroomId: "",
      activeRole: "coordinator",
      createdAt: ts,
      updatedAt: ts,
    })
  );
  await assertSucceeds(
    dpsRepDb.collection("users").doc(REP_UID).set({
      userId: REP_UID,
      displayName: "Bob DPS Rep",
      email: "bob@dps.edu",
      schoolId: "",
      activeClassroomId: "",
      activeRole: "coordinator",
      createdAt: ts,
      updatedAt: ts,
    })
  );
  await assertSucceeds(
    gpcCoordDb.collection("users").doc(OTHER_SCHOOL_UID).set({
      userId: OTHER_SCHOOL_UID,
      displayName: "Charlie GPC",
      email: "charlie@gpc.edu",
      schoolId: SCHOOL_GPC,
      activeClassroomId: "",
      activeRole: "coordinator",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // 1. DPS Coordinator initializes school + classroom + invite under schools/DPS_BALLABGARH
  await assertSucceeds(
    dpsCoordDb.collection("schools").doc(SCHOOL_DPS).set({
      schoolId: SCHOOL_DPS,
      name: SCHOOL_DPS,
      createdBy: COORDINATOR_UID,
      createdAt: ts,
      updatedAt: ts,
    })
  );
  await assertSucceeds(
    dpsCoordDb.collection("schools").doc(SCHOOL_DPS).collection("classrooms").doc("class_1").set({
      schoolId: SCHOOL_DPS,
      name: "CS-301 Batch A",
      joinCode: "NOVA26",
      ownerId: COORDINATOR_UID,
      coordinatorIds: [COORDINATOR_UID],
      memberIds: [COORDINATOR_UID],
      subjects: ["Data Structures", "Operating Systems"],
      timings: ["09:00 - 10:00"],
      createdAt: ts,
      updatedAt: ts,
    })
  );
  await assertSucceeds(
    dpsCoordDb.collection("schools").doc(SCHOOL_DPS).collection("invites").doc("NOVA26").set({
      schoolId: SCHOOL_DPS,
      classroomId: "class_1",
      classroomName: "CS-301 Batch A",
      ownerId: COORDINATOR_UID,
      createdAt: ts,
    })
  );

  // Cross-school attack: GPC coordinator CANNOT create a classroom in DPS_BALLABGARH
  await assertFails(
    gpcCoordDb.collection("schools").doc(SCHOOL_DPS).collection("classrooms").doc("hijack_cls").set({
      schoolId: SCHOOL_DPS,
      name: "Hijack Class",
      joinCode: "HACK99",
      ownerId: OTHER_SCHOOL_UID,
      coordinatorIds: [OTHER_SCHOOL_UID],
      memberIds: [OTHER_SCHOOL_UID],
      subjects: ["OS"],
      timings: ["09:00 - 10:00"],
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Cross-school attack: GPC coordinator CANNOT look up DPS invite even with joinCode
  await assertFails(
    gpcCoordDb.collection("schools").doc(SCHOOL_DPS).collection("invites").doc("NOVA26").get()
  );

  // 2. Unbound Representative looks up DPS invite, binds to DPS_BALLABGARH, and joins classroom
  const inviteSnap = await assertSucceeds(
    dpsRepDb.collection("schools").doc(SCHOOL_DPS).collection("invites").doc("NOVA26").get()
  );
  const classroomId = inviteSnap.data().classroomId;

  await assertSucceeds(
    dpsRepDb.collection("users").doc(REP_UID).update({
      schoolId: SCHOOL_DPS,
      updatedAt: nowPastDate(),
    })
  );

  await assertSucceeds(
    dpsRepDb.collection("schools").doc(SCHOOL_DPS).collection("classrooms").doc(classroomId).update({
      memberIds: [COORDINATOR_UID, REP_UID],
      updatedAt: nowPastDate(),
    })
  );

  // Cross-school join attack fails even if GPC user guesses classroomId directly
  await assertFails(
    gpcCoordDb.collection("schools").doc(SCHOOL_DPS).collection("classrooms").doc(classroomId).update({
      memberIds: [COORDINATOR_UID, REP_UID, OTHER_SCHOOL_UID],
      updatedAt: nowPastDate(),
    })
  );

  // Representative cannot modify subjects (Coordinator only)
  await assertFails(
    dpsRepDb.collection("schools").doc(SCHOOL_DPS).collection("classrooms").doc(classroomId).update({
      subjects: ["Data Structures"],
      updatedAt: nowPastDate(),
    })
  );

  // Coordinator CAN remove a subject
  await assertSucceeds(
    dpsCoordDb.collection("schools").doc(SCHOOL_DPS).collection("classrooms").doc(classroomId).update({
      subjects: ["Data Structures"],
      updatedAt: nowPastDate(),
    })
  );

  // 3. Coordinator uploads student; Representative cannot create or delete students
  const dpsClassRef = dpsCoordDb.collection("schools").doc(SCHOOL_DPS).collection("classrooms").doc(classroomId);
  await assertSucceeds(
    dpsClassRef.collection("students").doc("stu_101").set({
      schoolId: SCHOOL_DPS,
      classroomId,
      rollNumber: "101",
      name: "Aarav Sharma",
      createdBy: COORDINATOR_UID,
      memberIds: [COORDINATOR_UID, REP_UID],
      createdAt: ts,
      updatedAt: ts,
    })
  );

  await assertFails(
    dpsRepDb.collection("schools").doc(SCHOOL_DPS).collection("classrooms").doc(classroomId).collection("students").doc("stu_102").set({
      schoolId: SCHOOL_DPS,
      classroomId,
      rollNumber: "102",
      name: "Unauthorized",
      createdBy: REP_UID,
      memberIds: [COORDINATOR_UID, REP_UID],
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Representative queries students with matching memberIds filter and marks attendance
  await assertSucceeds(
    dpsRepDb.collection("schools").doc(SCHOOL_DPS).collection("classrooms").doc(classroomId).collection("students")
      .where("memberIds", "array-contains", REP_UID)
      .get()
  );

  await assertSucceeds(
    dpsRepDb.collection("schools").doc(SCHOOL_DPS).collection("classrooms").doc(classroomId).collection("sessions").doc("sess_1").set({
      schoolId: SCHOOL_DPS,
      classroomId,
      dateIso: "2026-11-12",
      subject: "Data Structures",
      timing: "09:00 - 10:00",
      markedBy: REP_UID,
      markedByName: "Bob DPS Rep",
      records: { stu_101: "P" },
      memberIds: [COORDINATOR_UID, REP_UID],
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Cross-school read/list isolation: GPC coordinator gets denied / zero access to DPS students & sessions
  await assertFails(
    gpcCoordDb.collection("schools").doc(SCHOOL_DPS).collection("classrooms").doc(classroomId).collection("sessions").doc("sess_1").get()
  );
  await assertFails(
    gpcCoordDb.collection("schools").doc(SCHOOL_DPS).collection("classrooms").doc(classroomId).collection("students").doc("stu_101").get()
  );
});

test("Legacy profile missing schoolId can bind once, and Rep can read/mark pre-existing sessions", async () => {
  const ts = nowPastDate();
  const legacyUid = "legacy_coord_1";
  const lateRepUid = "late_rep_2";
  const legacyDb = dbFor(legacyUid);
  const lateRepDb = dbFor(lateRepUid);

  // Seed legacy user profile without schoolId via admin rules bypass
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const adminDb = ctx.firestore(DATABASE_ID);
    await adminDb.collection("users").doc(legacyUid).set({
      userId: legacyUid,
      displayName: "Legacy Coord",
      email: "legacy@dps.edu",
      activeClassroomId: "",
      activeRole: "coordinator",
      createdAt: ts,
      updatedAt: ts,
    });
  });

  // Legacy user binds schoolId cleanly without PERMISSION_DENIED
  await assertSucceeds(
    legacyDb.collection("users").doc(legacyUid).update({
      schoolId: SCHOOL_DPS,
      updatedAt: nowPastDate(),
    })
  );

  // Coordinator creates school + classroom + invite + pre-existing session before Rep joins
  await assertSucceeds(
    legacyDb.collection("schools").doc(SCHOOL_DPS).set({
      schoolId: SCHOOL_DPS,
      name: SCHOOL_DPS,
      createdBy: legacyUid,
      createdAt: ts,
      updatedAt: ts,
    })
  );
  await assertSucceeds(
    legacyDb.collection("schools").doc(SCHOOL_DPS).collection("classrooms").doc("cls_legacy").set({
      schoolId: SCHOOL_DPS,
      name: "IT-101",
      joinCode: "IT2026",
      ownerId: legacyUid,
      coordinatorIds: [legacyUid],
      memberIds: [legacyUid],
      subjects: ["Data Structures"],
      timings: ["09:00 - 10:00"],
      createdAt: ts,
      updatedAt: ts,
    })
  );
  await assertSucceeds(
    legacyDb.collection("schools").doc(SCHOOL_DPS).collection("invites").doc("IT2026").set({
      schoolId: SCHOOL_DPS,
      classroomId: "cls_legacy",
      classroomName: "IT-101",
      ownerId: legacyUid,
      createdAt: ts,
    })
  );
  await assertSucceeds(
    legacyDb.collection("schools").doc(SCHOOL_DPS).collection("classrooms").doc("cls_legacy")
      .collection("sessions").doc("sess_pre").set({
        schoolId: SCHOOL_DPS,
        classroomId: "cls_legacy",
        dateIso: "2026-11-13",
        subject: "Data Structures",
        timing: "09:00 - 10:00",
        markedBy: legacyUid,
        markedByName: "Legacy Coord",
        records: { stu_1: "P" },
        memberIds: [legacyUid],
        createdAt: ts,
        updatedAt: ts,
      })
  );

  // Rep binds school & joins classroom AFTER sess_pre was created
  await assertSucceeds(
    lateRepDb.collection("users").doc(lateRepUid).set({
      userId: lateRepUid,
      displayName: "Late Rep",
      email: "laterep@dps.edu",
      schoolId: SCHOOL_DPS,
      activeClassroomId: "",
      activeRole: "representative",
      createdAt: ts,
      updatedAt: ts,
    })
  );
  await assertSucceeds(
    lateRepDb.collection("schools").doc(SCHOOL_DPS).collection("classrooms").doc("cls_legacy").update({
      memberIds: [legacyUid, lateRepUid],
      updatedAt: nowPastDate(),
    })
  );

  // Rep can get() and update() pre-existing session and self-sync memberIds
  await assertSucceeds(
    lateRepDb.collection("schools").doc(SCHOOL_DPS).collection("classrooms").doc("cls_legacy")
      .collection("sessions").doc("sess_pre").get()
  );
  await assertSucceeds(
    lateRepDb.collection("schools").doc(SCHOOL_DPS).collection("classrooms").doc("cls_legacy")
      .collection("sessions").doc("sess_pre").update({
        records: { stu_1: "P", stu_2: "A" },
        markedBy: lateRepUid,
        markedByName: "Late Rep",
        memberIds: [legacyUid, lateRepUid],
        updatedAt: nowPastDate(),
      })
  );
});
