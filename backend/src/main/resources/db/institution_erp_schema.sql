-- ════════════════════════════════════════════════════════════════════════════
-- Institution ERP System — Full Database Schema (PostgreSQL)
-- Multi-tenant: ALL tables scoped by institution_id
-- Roles: inst_admin, facilitator, teacher, student
-- ════════════════════════════════════════════════════════════════════════════

-- ─── 1. Institution Profile (enhanced) ──────────────────────────────────────
ALTER TABLE inst_profile ADD COLUMN IF NOT EXISTS academic_year VARCHAR(20);
ALTER TABLE inst_profile ADD COLUMN IF NOT EXISTS board VARCHAR(100);
ALTER TABLE inst_profile ADD COLUMN IF NOT EXISTS principal_name VARCHAR(255);
ALTER TABLE inst_profile ADD COLUMN IF NOT EXISTS principal_phone VARCHAR(20);
ALTER TABLE inst_profile ADD COLUMN IF NOT EXISTS type VARCHAR(50) DEFAULT 'school';
ALTER TABLE inst_profile ADD COLUMN IF NOT EXISTS affiliation_no VARCHAR(100);

-- ─── 2. Branches ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS inst_branches (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    branch_name      VARCHAR(255) NOT NULL,
    address          TEXT,
    city             VARCHAR(100),
    state            VARCHAR(100),
    pincode          VARCHAR(20),
    phone            VARCHAR(20),
    head_name        VARCHAR(255),
    status           VARCHAR(20) DEFAULT 'active',
    created_at       VARCHAR(30),
    created_by       BIGINT
);
CREATE INDEX IF NOT EXISTS idx_inst_branches_iid ON inst_branches(institution_id);

-- ─── 3. Departments ─────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS inst_departments (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    branch_id        BIGINT,
    dept_name        VARCHAR(255) NOT NULL,
    dept_code        VARCHAR(50),
    head_faculty_id  BIGINT,
    status           VARCHAR(20) DEFAULT 'active',
    created_at       VARCHAR(30),
    created_by       BIGINT
);
CREATE INDEX IF NOT EXISTS idx_inst_dept_iid ON inst_departments(institution_id);

-- ─── 4. Institution Users (role mapping) ────────────────────────────────────
CREATE TABLE IF NOT EXISTS inst_users (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    user_id          BIGINT NOT NULL,
    inst_role        VARCHAR(30) NOT NULL DEFAULT 'student',
    linked_id        BIGINT,
    branch_id        BIGINT,
    status           VARCHAR(20) DEFAULT 'active',
    last_login       VARCHAR(30),
    device_info      TEXT,
    login_count      INT DEFAULT 0,
    created_at       VARCHAR(30),
    created_by       BIGINT,
    UNIQUE(institution_id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_inst_users_iid ON inst_users(institution_id);
CREATE INDEX IF NOT EXISTS idx_inst_users_uid ON inst_users(user_id);
CREATE INDEX IF NOT EXISTS idx_inst_users_role ON inst_users(inst_role);

-- ─── 5. Classes ─────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS inst_classes (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    class_name       VARCHAR(100) NOT NULL,
    class_code       VARCHAR(20),
    grade_level      INT,
    branch_id        BIGINT,
    status           VARCHAR(20) DEFAULT 'active',
    created_at       VARCHAR(30),
    created_by       BIGINT
);
CREATE INDEX IF NOT EXISTS idx_inst_classes_iid ON inst_classes(institution_id);

-- ─── 6. Sections ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS inst_sections (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    class_id         BIGINT NOT NULL,
    section_name     VARCHAR(50) NOT NULL,
    class_teacher_id BIGINT,
    max_students     INT DEFAULT 40,
    room_number      VARCHAR(50),
    status           VARCHAR(20) DEFAULT 'active',
    created_at       VARCHAR(30),
    created_by       BIGINT
);
CREATE INDEX IF NOT EXISTS idx_inst_sections_iid ON inst_sections(institution_id);
CREATE INDEX IF NOT EXISTS idx_inst_sections_class ON inst_sections(class_id);

-- ─── 7. Subjects ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS inst_subjects (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    subject_name     VARCHAR(255) NOT NULL,
    subject_code     VARCHAR(50),
    class_id         BIGINT,
    department_id    BIGINT,
    subject_type     VARCHAR(30) DEFAULT 'theory',
    credits          INT DEFAULT 0,
    status           VARCHAR(20) DEFAULT 'active',
    created_at       VARCHAR(30),
    created_by       BIGINT
);
CREATE INDEX IF NOT EXISTS idx_inst_subjects_iid ON inst_subjects(institution_id);

-- ─── 8. Teacher-Subject-Class Allocation ────────────────────────────────────
CREATE TABLE IF NOT EXISTS inst_teacher_allocations (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    faculty_id       BIGINT NOT NULL,
    class_id         BIGINT NOT NULL,
    section_id       BIGINT,
    subject_id       BIGINT,
    academic_year    VARCHAR(20),
    status           VARCHAR(20) DEFAULT 'active',
    created_at       VARCHAR(30),
    created_by       BIGINT,
    UNIQUE(institution_id, faculty_id, class_id, section_id, subject_id)
);
CREATE INDEX IF NOT EXISTS idx_inst_talloc_iid ON inst_teacher_allocations(institution_id);
CREATE INDEX IF NOT EXISTS idx_inst_talloc_fid ON inst_teacher_allocations(faculty_id);

-- ─── 9. Facilitator Assignments ─────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS inst_facilitator_assignments (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    facilitator_id   BIGINT NOT NULL,
    class_id         BIGINT,
    section_id       BIGINT,
    branch_id        BIGINT,
    academic_year    VARCHAR(20),
    status           VARCHAR(20) DEFAULT 'active',
    created_at       VARCHAR(30),
    created_by       BIGINT
);
CREATE INDEX IF NOT EXISTS idx_inst_fassign_iid ON inst_facilitator_assignments(institution_id);
CREATE INDEX IF NOT EXISTS idx_inst_fassign_fid ON inst_facilitator_assignments(facilitator_id);

-- ─── 10. Student Class Enrollment ───────────────────────────────────────────
ALTER TABLE inst_students ADD COLUMN IF NOT EXISTS class_id BIGINT;
ALTER TABLE inst_students ADD COLUMN IF NOT EXISTS section_id BIGINT;
ALTER TABLE inst_students ADD COLUMN IF NOT EXISTS roll_number VARCHAR(20);
ALTER TABLE inst_students ADD COLUMN IF NOT EXISTS blood_group VARCHAR(10);
ALTER TABLE inst_students ADD COLUMN IF NOT EXISTS medical_info TEXT;
ALTER TABLE inst_students ADD COLUMN IF NOT EXISTS father_name VARCHAR(255);
ALTER TABLE inst_students ADD COLUMN IF NOT EXISTS mother_name VARCHAR(255);
ALTER TABLE inst_students ADD COLUMN IF NOT EXISTS parent_email VARCHAR(255);
ALTER TABLE inst_students ADD COLUMN IF NOT EXISTS photo_url VARCHAR(500);
ALTER TABLE inst_students ADD COLUMN IF NOT EXISTS user_id BIGINT;
CREATE INDEX IF NOT EXISTS idx_inst_students_class ON inst_students(class_id);
CREATE INDEX IF NOT EXISTS idx_inst_students_section ON inst_students(section_id);
CREATE INDEX IF NOT EXISTS idx_inst_students_uid ON inst_students(user_id);

-- ─── 11. Faculty enhancements ───────────────────────────────────────────────
ALTER TABLE inst_faculty ADD COLUMN IF NOT EXISTS department_id BIGINT;
ALTER TABLE inst_faculty ADD COLUMN IF NOT EXISTS designation VARCHAR(100);
ALTER TABLE inst_faculty ADD COLUMN IF NOT EXISTS employee_id VARCHAR(50);
ALTER TABLE inst_faculty ADD COLUMN IF NOT EXISTS photo_url VARCHAR(500);
ALTER TABLE inst_faculty ADD COLUMN IF NOT EXISTS user_id BIGINT;
ALTER TABLE inst_faculty ADD COLUMN IF NOT EXISTS gender VARCHAR(10);
ALTER TABLE inst_faculty ADD COLUMN IF NOT EXISTS dob VARCHAR(20);
ALTER TABLE inst_faculty ADD COLUMN IF NOT EXISTS address TEXT;
ALTER TABLE inst_faculty ADD COLUMN IF NOT EXISTS blood_group VARCHAR(10);
CREATE INDEX IF NOT EXISTS idx_inst_faculty_uid ON inst_faculty(user_id);
CREATE INDEX IF NOT EXISTS idx_inst_faculty_dept ON inst_faculty(department_id);

-- ─── 12. Assignments / Homework ─────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS inst_assignments (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    title            VARCHAR(255) NOT NULL,
    description      TEXT,
    assignment_type  VARCHAR(30) DEFAULT 'homework',
    class_id         BIGINT,
    section_id       BIGINT,
    subject_id       BIGINT,
    faculty_id       BIGINT NOT NULL,
    due_date         VARCHAR(20),
    max_marks        INT DEFAULT 0,
    file_url         VARCHAR(500),
    status           VARCHAR(20) DEFAULT 'active',
    created_at       VARCHAR(30),
    created_by       BIGINT
);
CREATE INDEX IF NOT EXISTS idx_inst_assign_iid ON inst_assignments(institution_id);
CREATE INDEX IF NOT EXISTS idx_inst_assign_class ON inst_assignments(class_id);
CREATE INDEX IF NOT EXISTS idx_inst_assign_faculty ON inst_assignments(faculty_id);

-- ─── 13. Assignment Submissions ─────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS inst_assignment_submissions (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    assignment_id    BIGINT NOT NULL,
    student_id       BIGINT NOT NULL,
    file_url         VARCHAR(500),
    remarks          TEXT,
    marks_obtained   NUMERIC(8,2),
    grade            VARCHAR(10),
    submitted_at     VARCHAR(30),
    graded_by        BIGINT,
    graded_at        VARCHAR(30),
    status           VARCHAR(20) DEFAULT 'submitted',
    UNIQUE(assignment_id, student_id)
);
CREATE INDEX IF NOT EXISTS idx_inst_asub_iid ON inst_assignment_submissions(institution_id);

-- ─── 14. Leave Requests ─────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS inst_leave_requests (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    requester_type   VARCHAR(20) NOT NULL,
    requester_id     BIGINT NOT NULL,
    leave_type       VARCHAR(50),
    from_date        VARCHAR(20) NOT NULL,
    to_date          VARCHAR(20) NOT NULL,
    days             INT DEFAULT 1,
    reason           TEXT,
    status           VARCHAR(20) DEFAULT 'pending',
    approved_by      BIGINT,
    approved_at      VARCHAR(30),
    remarks          TEXT,
    created_at       VARCHAR(30)
);
CREATE INDEX IF NOT EXISTS idx_inst_leave_iid ON inst_leave_requests(institution_id);
CREATE INDEX IF NOT EXISTS idx_inst_leave_req ON inst_leave_requests(requester_type, requester_id);

-- ─── 15. Student Behavior / Remarks ─────────────────────────────────────────
CREATE TABLE IF NOT EXISTS inst_student_remarks (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    student_id       BIGINT NOT NULL,
    remark_type      VARCHAR(30) DEFAULT 'general',
    remark           TEXT NOT NULL,
    added_by         BIGINT,
    created_at       VARCHAR(30)
);
CREATE INDEX IF NOT EXISTS idx_inst_remarks_iid ON inst_student_remarks(institution_id);
CREATE INDEX IF NOT EXISTS idx_inst_remarks_sid ON inst_student_remarks(student_id);

-- ─── 16. Daily Notes (Teacher) ──────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS inst_daily_notes (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    faculty_id       BIGINT NOT NULL,
    class_id         BIGINT,
    section_id       BIGINT,
    subject_id       BIGINT,
    note_date        VARCHAR(20) NOT NULL,
    topic_covered    VARCHAR(500),
    notes            TEXT,
    created_at       VARCHAR(30)
);
CREATE INDEX IF NOT EXISTS idx_inst_dnotes_iid ON inst_daily_notes(institution_id);
CREATE INDEX IF NOT EXISTS idx_inst_dnotes_fid ON inst_daily_notes(faculty_id);

-- ─── 17. Notifications ──────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS inst_notifications (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    title            VARCHAR(255) NOT NULL,
    message          TEXT,
    target_role      VARCHAR(30),
    target_class_id  BIGINT,
    target_user_id   BIGINT,
    priority         VARCHAR(20) DEFAULT 'normal',
    is_read          BOOLEAN DEFAULT FALSE,
    created_by       BIGINT,
    created_at       VARCHAR(30)
);
CREATE INDEX IF NOT EXISTS idx_inst_notif_iid ON inst_notifications(institution_id);
CREATE INDEX IF NOT EXISTS idx_inst_notif_target ON inst_notifications(target_role, target_user_id);

-- ─── 18. Holidays ───────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS inst_holidays (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    holiday_name     VARCHAR(255) NOT NULL,
    holiday_date     VARCHAR(20) NOT NULL,
    holiday_type     VARCHAR(30) DEFAULT 'general',
    description      TEXT,
    created_at       VARCHAR(30),
    created_by       BIGINT
);
CREATE INDEX IF NOT EXISTS idx_inst_holidays_iid ON inst_holidays(institution_id);

-- ─── 19. Academic Calendar ──────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS inst_calendar_events (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    event_name       VARCHAR(255) NOT NULL,
    event_type       VARCHAR(50),
    event_date       VARCHAR(20) NOT NULL,
    end_date         VARCHAR(20),
    description      TEXT,
    target_role      VARCHAR(30),
    created_at       VARCHAR(30),
    created_by       BIGINT
);
CREATE INDEX IF NOT EXISTS idx_inst_calev_iid ON inst_calendar_events(institution_id);

-- ─── 20. Audit Log ──────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS inst_audit_log (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    user_id          BIGINT,
    action           VARCHAR(50) NOT NULL,
    entity_type      VARCHAR(100),
    entity_id        BIGINT,
    old_value        TEXT,
    new_value        TEXT,
    ip_address       VARCHAR(50),
    created_at       VARCHAR(30)
);
CREATE INDEX IF NOT EXISTS idx_inst_audit_iid ON inst_audit_log(institution_id);
CREATE INDEX IF NOT EXISTS idx_inst_audit_uid ON inst_audit_log(user_id);

-- ─── 21. Messages / Communication ───────────────────────────────────────────
CREATE TABLE IF NOT EXISTS inst_messages (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    sender_id        BIGINT NOT NULL,
    receiver_id      BIGINT NOT NULL,
    subject          VARCHAR(255),
    message          TEXT NOT NULL,
    is_read          BOOLEAN DEFAULT FALSE,
    created_at       VARCHAR(30)
);
CREATE INDEX IF NOT EXISTS idx_inst_msg_iid ON inst_messages(institution_id);
CREATE INDEX IF NOT EXISTS idx_inst_msg_recv ON inst_messages(receiver_id);

-- ─── 22. Student Documents ──────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS inst_student_documents (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    student_id       BIGINT NOT NULL,
    doc_type         VARCHAR(50),
    doc_name         VARCHAR(255),
    file_url         VARCHAR(500),
    uploaded_by      BIGINT,
    created_at       VARCHAR(30)
);
CREATE INDEX IF NOT EXISTS idx_inst_sdocs_iid ON inst_student_documents(institution_id);
CREATE INDEX IF NOT EXISTS idx_inst_sdocs_sid ON inst_student_documents(student_id);

-- ─── 23. Certificates ───────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS inst_certificates (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    student_id       BIGINT NOT NULL,
    cert_type        VARCHAR(100),
    cert_number      VARCHAR(100),
    issue_date       VARCHAR(20),
    file_url         VARCHAR(500),
    issued_by        BIGINT,
    created_at       VARCHAR(30)
);
CREATE INDEX IF NOT EXISTS idx_inst_certs_iid ON inst_certificates(institution_id);
CREATE INDEX IF NOT EXISTS idx_inst_certs_sid ON inst_certificates(student_id);

-- ─── 24. Queries / Feedback ─────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS inst_queries (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    raised_by        BIGINT NOT NULL,
    query_type       VARCHAR(50),
    subject          VARCHAR(255),
    message          TEXT,
    status           VARCHAR(20) DEFAULT 'open',
    response         TEXT,
    responded_by     BIGINT,
    responded_at     VARCHAR(30),
    created_at       VARCHAR(30)
);
CREATE INDEX IF NOT EXISTS idx_inst_queries_iid ON inst_queries(institution_id);

-- ─── 25. Permissions (RBAC granular) ────────────────────────────────────────
CREATE TABLE IF NOT EXISTS inst_permissions (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    inst_role        VARCHAR(30) NOT NULL,
    module           VARCHAR(100) NOT NULL,
    can_view         BOOLEAN DEFAULT FALSE,
    can_create       BOOLEAN DEFAULT FALSE,
    can_edit         BOOLEAN DEFAULT FALSE,
    can_delete       BOOLEAN DEFAULT FALSE,
    can_approve      BOOLEAN DEFAULT FALSE,
    created_at       VARCHAR(30),
    UNIQUE(institution_id, inst_role, module)
);
CREATE INDEX IF NOT EXISTS idx_inst_perms_iid ON inst_permissions(institution_id);
