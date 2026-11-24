/*
Story ID: N/A (pasted directly)
Title: IB_datavault 2.sql
Language: SQL — the provided requirement is itself a T-SQL schema script, and no separate user story link was supplied.
What this code does: Creates an International Baccalaureate (IB) Data Vault 2.0 schema with hubs, links, satellites, a reference table, and a business-vault attendance summary table.
Assumptions: The pasted requirement content is the complete desired deliverable. Targets Microsoft SQL Server because it uses sys.schemas, datetime2, binary(32), and GO batch separators.
Usage: Execute this script in SQL Server Management Studio, Azure Data Studio, or sqlcmd against the target database.
*/

/* =====================================================================
   SAMPLE DATA VAULT 2.0 - INTERNATIONAL BACCALAUREATE (IB) SCHOOL
   Domain : Student, Course, Section, Enrollment, Attendance, Assessment
   Schema : Ib
   Hash   : binary(32) = SHA2_256 of UPPER/TRIM business key(s) joined by '||'
   Privacy: student PII is split into its own satellite (SatStudentPersonal)
            so it can be secured, masked or purged (GDPR / FERPA) separately.
   ===================================================================== */

IF NOT EXISTS (SELECT 1 FROM sys.schemas WHERE name = 'Ib')
    EXEC('CREATE SCHEMA [Ib]');
go

/* =====================================================================
   1. HUBS
   ===================================================================== */
CREATE TABLE [Ib].[HubStudent]
(
    [StudentHk]      binary(32)    NOT NULL ,
    [StudentBk]      varchar(50)   NOT NULL ,   -- school student ID
    [LoadDts]        datetime2(7)  NOT NULL ,
    [RecordSource]   varchar(100)  NOT NULL ,
    CONSTRAINT [PK_HubStudent] PRIMARY KEY NONCLUSTERED ([StudentHk])
)
go
CREATE UNIQUE CLUSTERED INDEX [UX_HubStudent_Bk] ON [Ib].[HubStudent] ([StudentBk])
go

CREATE TABLE [Ib].[HubTeacher]
(
    [TeacherHk]      binary(32)    NOT NULL ,
    [TeacherBk]      varchar(50)   NOT NULL ,   -- staff ID
    [LoadDts]        datetime2(7)  NOT NULL ,
    [RecordSource]   varchar(100)  NOT NULL ,
    CONSTRAINT [PK_HubTeacher] PRIMARY KEY NONCLUSTERED ([TeacherHk])
)
go
CREATE UNIQUE CLUSTERED INDEX [UX_HubTeacher_Bk] ON [Ib].[HubTeacher] ([TeacherBk])
go

CREATE TABLE [Ib].[HubCourse]
(
    [CourseHk]       binary(32)    NOT NULL ,
    [CourseBk]       varchar(50)   NOT NULL ,   -- e.g. MATH-AA-HL
    [LoadDts]        datetime2(7)  NOT NULL ,
    [RecordSource]   varchar(100)  NOT NULL ,
    CONSTRAINT [PK_HubCourse] PRIMARY KEY NONCLUSTERED ([CourseHk])
)
go
CREATE UNIQUE CLUSTERED INDEX [UX_HubCourse_Bk] ON [Ib].[HubCourse] ([CourseBk])
go

CREATE TABLE [Ib].[HubCourseSection]
(
    [CourseSectionHk] binary(32)   NOT NULL ,
    [CourseSectionBk] varchar(120) NOT NULL ,   -- SchoolCode||TermCode||CourseCode||SectionCode
    [LoadDts]        datetime2(7)  NOT NULL ,
    [RecordSource]   varchar(100)  NOT NULL ,
    CONSTRAINT [PK_HubCourseSection] PRIMARY KEY NONCLUSTERED ([CourseSectionHk])
)
go
CREATE UNIQUE CLUSTERED INDEX [UX_HubCourseSection_Bk] ON [Ib].[HubCourseSection] ([CourseSectionBk])
go

CREATE TABLE [Ib].[HubSchool]
(
    [SchoolHk]       binary(32)    NOT NULL ,
    [SchoolBk]       varchar(20)   NOT NULL ,   -- IB school code
    [LoadDts]        datetime2(7)  NOT NULL ,
    [RecordSource]   varchar(100)  NOT NULL ,
    CONSTRAINT [PK_HubSchool] PRIMARY KEY NONCLUSTERED ([SchoolHk])
)
go
CREATE UNIQUE CLUSTERED INDEX [UX_HubSchool_Bk] ON [Ib].[HubSchool] ([SchoolBk])
go

CREATE TABLE [Ib].[HubAcademicTerm]
(
    [AcademicTermHk] binary(32)    NOT NULL ,
    [AcademicTermBk] varchar(30)   NOT NULL ,   -- e.g. 2025-FALL
    [LoadDts]        datetime2(7)  NOT NULL ,
    [RecordSource]   varchar(100)  NOT NULL ,
    CONSTRAINT [PK_HubAcademicTerm] PRIMARY KEY NONCLUSTERED ([AcademicTermHk])
)
go
CREATE UNIQUE CLUSTERED INDEX [UX_HubAcademicTerm_Bk] ON [Ib].[HubAcademicTerm] ([AcademicTermBk])
go

CREATE TABLE [Ib].[HubProgramme]
(
    [ProgrammeHk]    binary(32)    NOT NULL ,
    [ProgrammeBk]    varchar(10)   NOT NULL ,   -- PYP, MYP, DP, CP
    [LoadDts]        datetime2(7)  NOT NULL ,
    [RecordSource]   varchar(100)  NOT NULL ,
    CONSTRAINT [PK_HubProgramme] PRIMARY KEY NONCLUSTERED ([ProgrammeHk])
)
go
CREATE UNIQUE CLUSTERED INDEX [UX_HubProgramme_Bk] ON [Ib].[HubProgramme] ([ProgrammeBk])
go

CREATE TABLE [Ib].[HubAssessment]
(
    [AssessmentHk]   binary(32)    NOT NULL ,
    [AssessmentBk]   varchar(60)   NOT NULL ,   -- e.g. MATH-AA-HL-IA, EE, TOK-ESSAY, CAS
    [LoadDts]        datetime2(7)  NOT NULL ,
    [RecordSource]   varchar(100)  NOT NULL ,
    CONSTRAINT [PK_HubAssessment] PRIMARY KEY NONCLUSTERED ([AssessmentHk])
)
go
CREATE UNIQUE CLUSTERED INDEX [UX_HubAssessment_Bk] ON [Ib].[HubAssessment] ([AssessmentBk])
go


/* =====================================================================
   2. LINKS
   ===================================================================== */
/* A section is one course offered in one term at one school */
CREATE TABLE [Ib].[LnkSectionOffering]
(
    [SectionOfferingHk] binary(32) NOT NULL ,
    [CourseSectionHk]   binary(32) NOT NULL ,
    [CourseHk]          binary(32) NOT NULL ,
    [AcademicTermHk]    binary(32) NOT NULL ,
    [SchoolHk]          binary(32) NOT NULL ,
    [LoadDts]           datetime2(7)  NOT NULL ,
    [RecordSource]      varchar(100)  NOT NULL ,
    CONSTRAINT [PK_LnkSectionOffering] PRIMARY KEY NONCLUSTERED ([SectionOfferingHk]),
    CONSTRAINT [FK_LnkSectionOffering_Section] FOREIGN KEY ([CourseSectionHk]) REFERENCES [Ib].[HubCourseSection] ([CourseSectionHk]),
    CONSTRAINT [FK_LnkSectionOffering_Course]  FOREIGN KEY ([CourseHk])        REFERENCES [Ib].[HubCourse]        ([CourseHk]),
    CONSTRAINT [FK_LnkSectionOffering_Term]    FOREIGN KEY ([AcademicTermHk])  REFERENCES [Ib].[HubAcademicTerm]  ([AcademicTermHk]),
    CONSTRAINT [FK_LnkSectionOffering_School]  FOREIGN KEY ([SchoolHk])        REFERENCES [Ib].[HubSchool]        ([SchoolHk])
)
go
CREATE CLUSTERED INDEX [CX_LnkSectionOffering] ON [Ib].[LnkSectionOffering] ([CourseSectionHk])
go
CREATE NONCLUSTERED INDEX [IX_LnkSectionOffering_Course] ON [Ib].[LnkSectionOffering] ([CourseHk])
go
CREATE NONCLUSTERED INDEX [IX_LnkSectionOffering_Term]   ON [Ib].[LnkSectionOffering] ([AcademicTermHk])
go

/* Teacher assigned to a section, by role (PRIMARY, CO-TEACHER, SUBSTITUTE) */
CREATE TABLE [Ib].[LnkSectionTeacher]
(
    [SectionTeacherHk]  binary(32) NOT NULL ,
    [CourseSectionHk]   binary(32) NOT NULL ,
    [TeacherHk]         binary(32) NOT NULL ,
    [TeacherRole]       varchar(20) NOT NULL ,
    [LoadDts]           datetime2(7)  NOT NULL ,
    [RecordSource]      varchar(100)  NOT NULL ,
    CONSTRAINT [PK_LnkSectionTeacher] PRIMARY KEY NONCLUSTERED ([SectionTeacherHk]),
    CONSTRAINT [FK_LnkSectionTeacher_Section] FOREIGN KEY ([CourseSectionHk]) REFERENCES [Ib].[HubCourseSection] ([CourseSectionHk]),
    CONSTRAINT [FK_LnkSectionTeacher_Teacher] FOREIGN KEY ([TeacherHk])       REFERENCES [Ib].[HubTeacher]       ([TeacherHk])
)
go
CREATE CLUSTERED INDEX [CX_LnkSectionTeacher] ON [Ib].[LnkSectionTeacher] ([CourseSectionHk])
go
CREATE NONCLUSTERED INDEX [IX_LnkSectionTeacher_Teacher] ON [Ib].[LnkSectionTeacher] ([TeacherHk])
go

/* Student in an IB programme at a school */
CREATE TABLE [Ib].[LnkStudentProgramme]
(
    [StudentProgrammeHk] binary(32) NOT NULL ,
    [StudentHk]          binary(32) NOT NULL ,
    [ProgrammeHk]        binary(32) NOT NULL ,
    [SchoolHk]           binary(32) NOT NULL ,
    [LoadDts]            datetime2(7)  NOT NULL ,
    [RecordSource]       varchar(100)  NOT NULL ,
    CONSTRAINT [PK_LnkStudentProgramme] PRIMARY KEY NONCLUSTERED ([StudentProgrammeHk]),
    CONSTRAINT [FK_LnkStudentProgramme_Student]   FOREIGN KEY ([StudentHk])   REFERENCES [Ib].[HubStudent]   ([StudentHk]),
    CONSTRAINT [FK_LnkStudentProgramme_Programme] FOREIGN KEY ([ProgrammeHk]) REFERENCES [Ib].[HubProgramme] ([ProgrammeHk]),
    CONSTRAINT [FK_LnkStudentProgramme_School]    FOREIGN KEY ([SchoolHk])    REFERENCES [Ib].[HubSchool]    ([SchoolHk])
)
go
CREATE CLUSTERED INDEX [CX_LnkStudentProgramme] ON [Ib].[LnkStudentProgramme] ([StudentHk])
go
CREATE NONCLUSTERED INDEX [IX_LnkStudentProgramme_Programme] ON [Ib].[LnkStudentProgramme] ([ProgrammeHk])
go

/* Student enrolled in a section */
CREATE TABLE [Ib].[LnkEnrollment]
(
    [EnrollmentHk]      binary(32) NOT NULL ,
    [StudentHk]         binary(32) NOT NULL ,
    [CourseSectionHk]   binary(32) NOT NULL ,
    [LoadDts]           datetime2(7)  NOT NULL ,
    [RecordSource]      varchar(100)  NOT NULL ,
    CONSTRAINT [PK_LnkEnrollment] PRIMARY KEY NONCLUSTERED ([EnrollmentHk]),
    CONSTRAINT [FK_LnkEnrollment_Student] FOREIGN KEY ([StudentHk])       REFERENCES [Ib].[HubStudent]       ([StudentHk]),
    CONSTRAINT [FK_LnkEnrollment_Section] FOREIGN KEY ([CourseSectionHk]) REFERENCES [Ib].[HubCourseSection] ([CourseSectionHk])
)
go
CREATE CLUSTERED INDEX [CX_LnkEnrollment] ON [Ib].[LnkEnrollment] ([StudentHk])
go
CREATE NONCLUSTERED INDEX [IX_LnkEnrollment_Section] ON [Ib].[LnkEnrollment] ([CourseSectionHk])
go

/* Attendance event: student + section + date + period (period is a dependent child key) */
CREATE TABLE [Ib].[LnkAttendance]
(
    [AttendanceHk]      binary(32) NOT NULL ,
    [StudentHk]         binary(32) NOT NULL ,
    [CourseSectionHk]   binary(32) NOT NULL ,
    [AttendanceDate]    date       NOT NULL ,
    [PeriodNumber]      tinyint    NOT NULL ,
    [LoadDts]           datetime2(7)  NOT NULL ,
    [RecordSource]      varchar(100)  NOT NULL ,
    CONSTRAINT [PK_LnkAttendance] PRIMARY KEY NONCLUSTERED ([AttendanceHk]),
    CONSTRAINT [FK_LnkAttendance_Student] FOREIGN KEY ([StudentHk])       REFERENCES [Ib].[HubStudent]       ([StudentHk]),
    CONSTRAINT [FK_LnkAttendance_Section] FOREIGN KEY ([CourseSectionHk]) REFERENCES [Ib].[HubCourseSection] ([CourseSectionHk])
)
go
CREATE CLUSTERED INDEX [CX_LnkAttendance] ON [Ib].[LnkAttendance] ([AttendanceDate], [StudentHk])
go
CREATE NONCLUSTERED INDEX [IX_LnkAttendance_Student_Section] ON [Ib].[LnkAttendance] ([StudentHk], [CourseSectionHk])
go

/* Student result for an assessment in a section */
CREATE TABLE [Ib].[LnkAssessmentResult]
(
    [AssessmentResultHk] binary(32) NOT NULL ,
    [StudentHk]          binary(32) NOT NULL ,
    [AssessmentHk]       binary(32) NOT NULL ,
    [CourseSectionHk]    binary(32) NOT NULL ,
    [LoadDts]            datetime2(7)  NOT NULL ,
    [RecordSource]       varchar(100)  NOT NULL ,
    CONSTRAINT [PK_LnkAssessmentResult] PRIMARY KEY NONCLUSTERED ([AssessmentResultHk]),
    CONSTRAINT [FK_LnkAssessmentResult_Student]    FOREIGN KEY ([StudentHk])       REFERENCES [Ib].[HubStudent]       ([StudentHk]),
    CONSTRAINT [FK_LnkAssessmentResult_Assessment] FOREIGN KEY ([AssessmentHk])    REFERENCES [Ib].[HubAssessment]    ([AssessmentHk]),
    CONSTRAINT [FK_LnkAssessmentResult_Section]    FOREIGN KEY ([CourseSectionHk]) REFERENCES [Ib].[HubCourseSection] ([CourseSectionHk])
)
go
CREATE CLUSTERED INDEX [CX_LnkAssessmentResult] ON [Ib].[LnkAssessmentResult] ([StudentHk])
go
CREATE NONCLUSTERED INDEX [IX_LnkAssessmentResult_Assessment] ON [Ib].[LnkAssessmentResult] ([AssessmentHk])
go


/* =====================================================================
   3. SATELLITES
   ===================================================================== */
/* ---- Student: PII split from academic attributes ---- */
CREATE TABLE [Ib].[SatStudentPersonal]
(
    [StudentHk]        binary(32)    NOT NULL ,
    [LoadDts]          datetime2(7)  NOT NULL ,
    [LoadEndDts]       datetime2(7)  NULL ,
    [HashDiff]         binary(32)    NOT NULL ,
    [FirstName]        varchar(100)  NULL ,
    [MiddleName]       varchar(100)  NULL ,
    [LastName]         varchar(100)  NULL ,
    [PreferredName]    varchar(100)  NULL ,
    [BirthDate]        date          NULL ,
    [Gender]           char(1)       NULL ,
    [Nationality]      varchar(2)    NULL ,       -- ISO 3166-1 alpha-2
    [NativeLanguage]   varchar(50)   NULL ,
    [Email]            varchar(255)  NULL ,
    [RecordSource]     varchar(100)  NOT NULL ,
    CONSTRAINT [PK_SatStudentPersonal] PRIMARY KEY CLUSTERED ([StudentHk], [LoadDts]),
    CONSTRAINT [FK_SatStudentPersonal_Hub] FOREIGN KEY ([StudentHk]) REFERENCES [Ib].[HubStudent] ([StudentHk])
)
go

CREATE TABLE [Ib].[SatStudentAcademic]
(
    [StudentHk]        binary(32)    NOT NULL ,
    [LoadDts]          datetime2(7)  NOT NULL ,
    [LoadEndDts]       datetime2(7)  NULL ,
    [HashDiff]         binary(32)    NOT NULL ,
    [GradeLevel]       varchar(10)   NULL ,       -- e.g. Grade 11 / Year 12
    [IbCandidateCode]  varchar(20)   NULL ,       -- IB candidate (personal) code
    [EnrollmentStatus] varchar(20)   NULL ,       -- Active, Withdrawn, Graduated
    [AdmissionDate]    date          NULL ,
    [HouseOrHomeroom]  varchar(30)   NULL ,
    [RecordSource]     varchar(100)  NOT NULL ,
    CONSTRAINT [PK_SatStudentAcademic] PRIMARY KEY CLUSTERED ([StudentHk], [LoadDts]),
    CONSTRAINT [FK_SatStudentAcademic_Hub] FOREIGN KEY ([StudentHk]) REFERENCES [Ib].[HubStudent] ([StudentHk])
)
go

CREATE TABLE [Ib].[SatTeacherDetails]
(
    [TeacherHk]        binary(32)    NOT NULL ,
    [LoadDts]          datetime2(7)  NOT NULL ,
    [LoadEndDts]       datetime2(7)  NULL ,
    [HashDiff]         binary(32)    NOT NULL ,
    [FirstName]        varchar(100)  NULL ,
    [LastName]         varchar(100)  NULL ,
    [Email]            varchar(255)  NULL ,
    [Department]       varchar(100)  NULL ,
    [IbWorkshopCertified] bit        NULL ,
    [IsActive]         bit           NULL ,
    [RecordSource]     varchar(100)  NOT NULL ,
    CONSTRAINT [PK_SatTeacherDetails] PRIMARY KEY CLUSTERED ([TeacherHk], [LoadDts]),
    CONSTRAINT [FK_SatTeacherDetails_Hub] FOREIGN KEY ([TeacherHk]) REFERENCES [Ib].[HubTeacher] ([TeacherHk])
)
go

CREATE TABLE [Ib].[SatCourseDetails]
(
    [CourseHk]         binary(32)    NOT NULL ,
    [LoadDts]          datetime2(7)  NOT NULL ,
    [LoadEndDts]       datetime2(7)  NULL ,
    [HashDiff]         binary(32)    NOT NULL ,
    [CourseName]       varchar(200)  NULL ,
    [ProgrammeCode]    varchar(10)   NULL ,       -- PYP, MYP, DP, CP
    [SubjectGroup]     tinyint       NULL ,       -- DP groups 1-6
    [SubjectArea]      varchar(100)  NULL ,
    [CourseLevel]      varchar(10)   NULL ,       -- HL, SL, Standard
    [LanguageOfInstruction] varchar(50) NULL ,
    [TeachingHours]    smallint      NULL ,       -- 240 HL / 150 SL
    [IsCore]           bit           NULL ,       -- TOK, EE, CAS
    [RecordSource]     varchar(100)  NOT NULL ,
    CONSTRAINT [PK_SatCourseDetails] PRIMARY KEY CLUSTERED ([CourseHk], [LoadDts]),
    CONSTRAINT [FK_SatCourseDetails_Hub] FOREIGN KEY ([CourseHk]) REFERENCES [Ib].[HubCourse] ([CourseHk])
)
go

CREATE TABLE [Ib].[SatCourseSectionDetails]
(
    [CourseSectionHk]  binary(32)    NOT NULL ,
    [LoadDts]          datetime2(7)  NOT NULL ,
    [LoadEndDts]       datetime2(7)  NULL ,
    [HashDiff]         binary(32)    NOT NULL ,
    [SectionCode]      varchar(20)   NULL ,
    [Room]             varchar(30)   NULL ,
    [SchedulePeriod]   varchar(30)   NULL ,
    [Capacity]         smallint      NULL ,
    [SectionStatus]    varchar(20)   NULL ,
    [RecordSource]     varchar(100)  NOT NULL ,
    CONSTRAINT [PK_SatCourseSectionDetails] PRIMARY KEY CLUSTERED ([CourseSectionHk], [LoadDts]),
    CONSTRAINT [FK_SatCourseSectionDetails_Hub] FOREIGN KEY ([CourseSectionHk]) REFERENCES [Ib].[HubCourseSection] ([CourseSectionHk])
)
go

CREATE TABLE [Ib].[SatSchoolDetails]
(
    [SchoolHk]         binary(32)    NOT NULL ,
    [LoadDts]          datetime2(7)  NOT NULL ,
    [LoadEndDts]       datetime2(7)  NULL ,
    [HashDiff]         binary(32)    NOT NULL ,
    [SchoolName]       varchar(200)  NULL ,
    [CountryCode]      varchar(2)    NULL ,
    [City]             varchar(100)  NULL ,
    [TimeZone]         varchar(50)   NULL ,
    [AuthorizedProgrammes] varchar(50) NULL ,      -- e.g. PYP,MYP,DP
    [RecordSource]     varchar(100)  NOT NULL ,
    CONSTRAINT [PK_SatSchoolDetails] PRIMARY KEY CLUSTERED ([SchoolHk], [LoadDts]),
    CONSTRAINT [FK_SatSchoolDetails_Hub] FOREIGN KEY ([SchoolHk]) REFERENCES [Ib].[HubSchool] ([SchoolHk])
)
go

CREATE TABLE [Ib].[SatAcademicTermDetails]
(
    [AcademicTermHk]   binary(32)    NOT NULL ,
    [LoadDts]          datetime2(7)  NOT NULL ,
    [LoadEndDts]       datetime2(7)  NULL ,
    [HashDiff]         binary(32)    NOT NULL ,
    [TermName]         varchar(50)   NULL ,
    [AcademicYear]     varchar(9)    NULL ,       -- 2025-2026
    [StartDate]        date          NULL ,
    [EndDate]          date          NULL ,
    [InstructionalDays] smallint     NULL ,
    [RecordSource]     varchar(100)  NOT NULL ,
    CONSTRAINT [PK_SatAcademicTermDetails] PRIMARY KEY CLUSTERED ([AcademicTermHk], [LoadDts]),
    CONSTRAINT [FK_SatAcademicTermDetails_Hub] FOREIGN KEY ([AcademicTermHk]) REFERENCES [Ib].[HubAcademicTerm] ([AcademicTermHk])
)
go

CREATE TABLE [Ib].[SatProgrammeDetails]
(
    [ProgrammeHk]      binary(32)    NOT NULL ,
    [LoadDts]          datetime2(7)  NOT NULL ,
    [LoadEndDts]       datetime2(7)  NULL ,
    [HashDiff]         binary(32)    NOT NULL ,
    [ProgrammeName]    varchar(100)  NULL ,
    [AgeRange]         varchar(20)   NULL ,       -- 3-12, 11-16, 16-19
    [RecordSource]     varchar(100)  NOT NULL ,
    CONSTRAINT [PK_SatProgrammeDetails] PRIMARY KEY CLUSTERED ([ProgrammeHk], [LoadDts]),
    CONSTRAINT [FK_SatProgrammeDetails_Hub] FOREIGN KEY ([ProgrammeHk]) REFERENCES [Ib].[HubProgramme] ([ProgrammeHk])
)
go

CREATE TABLE [Ib].[SatAssessmentDetails]
(
    [AssessmentHk]     binary(32)    NOT NULL ,
    [LoadDts]          datetime2(7)  NOT NULL ,
    [LoadEndDts]       datetime2(7)  NULL ,
    [HashDiff]         binary(32)    NOT NULL ,
    [AssessmentName]   varchar(200)  NULL ,
    [AssessmentType]   varchar(20)   NULL ,       -- IA, EE, TOK, CAS, EXAM, INTERNAL
    [IsInternal]       bit           NULL ,
    [MaxScore]         decimal(8,2)  NULL ,
    [WeightPercent]    decimal(5,2)  NULL ,
    [RecordSource]     varchar(100)  NOT NULL ,
    CONSTRAINT [PK_SatAssessmentDetails] PRIMARY KEY CLUSTERED ([AssessmentHk], [LoadDts]),
    CONSTRAINT [FK_SatAssessmentDetails_Hub] FOREIGN KEY ([AssessmentHk]) REFERENCES [Ib].[HubAssessment] ([AssessmentHk])
)
go

/* ---- Link satellites ---- */
CREATE TABLE [Ib].[SatEnrollmentDetails]
(
    [EnrollmentHk]     binary(32)    NOT NULL ,
    [LoadDts]          datetime2(7)  NOT NULL ,
    [LoadEndDts]       datetime2(7)  NULL ,
    [HashDiff]         binary(32)    NOT NULL ,
    [EnrollmentStatus] varchar(20)   NULL ,       -- Enrolled, Dropped, Completed
    [CourseLevel]      varchar(10)   NULL ,       -- HL / SL chosen by the student
    [EnrollDate]       date          NULL ,
    [DropDate]         date          NULL ,
    [RecordSource]     varchar(100)  NOT NULL ,
    CONSTRAINT [PK_SatEnrollmentDetails] PRIMARY KEY CLUSTERED ([EnrollmentHk], [LoadDts]),
    CONSTRAINT [FK_SatEnrollmentDetails_Link] FOREIGN KEY ([EnrollmentHk]) REFERENCES [Ib].[LnkEnrollment] ([EnrollmentHk])
)
go

CREATE TABLE [Ib].[SatStudentProgrammeDetails]
(
    [StudentProgrammeHk] binary(32)  NOT NULL ,
    [LoadDts]          datetime2(7)  NOT NULL ,
    [LoadEndDts]       datetime2(7)  NULL ,
    [HashDiff]         binary(32)    NOT NULL ,
    [CandidateType]    varchar(30)   NULL ,       -- Diploma, Course candidate
    [StartDate]        date          NULL ,
    [ExpectedGraduationDate] date    NULL ,
    [ProgrammeStatus]  varchar(20)   NULL ,
    [RecordSource]     varchar(100)  NOT NULL ,
    CONSTRAINT [PK_SatStudentProgrammeDetails] PRIMARY KEY CLUSTERED ([StudentProgrammeHk], [LoadDts]),
    CONSTRAINT [FK_SatStudentProgrammeDetails_Link] FOREIGN KEY ([StudentProgrammeHk]) REFERENCES [Ib].[LnkStudentProgramme] ([StudentProgrammeHk])
)
go

CREATE TABLE [Ib].[SatAttendanceDetails]
(
    [AttendanceHk]     binary(32)    NOT NULL ,
    [LoadDts]          datetime2(7)  NOT NULL ,
    [LoadEndDts]       datetime2(7)  NULL ,
    [HashDiff]         binary(32)    NOT NULL ,
    [AttendanceStatusCode] varchar(10) NULL ,     -- P, A, L, E (see RefAttendanceStatus)
    [MinutesLate]      smallint      NULL ,
    [AbsenceReason]    varchar(200)  NULL ,
    [RecordedBy]       varchar(100)  NULL ,
    [RecordedDts]      datetime2(7)  NULL ,
    [RecordSource]     varchar(100)  NOT NULL ,
    CONSTRAINT [PK_SatAttendanceDetails] PRIMARY KEY CLUSTERED ([AttendanceHk], [LoadDts]),
    CONSTRAINT [FK_SatAttendanceDetails_Link] FOREIGN KEY ([AttendanceHk]) REFERENCES [Ib].[LnkAttendance] ([AttendanceHk])
)
go

CREATE TABLE [Ib].[SatAssessmentResult]
(
    [AssessmentResultHk] binary(32)  NOT NULL ,
    [LoadDts]          datetime2(7)  NOT NULL ,
    [LoadEndDts]       datetime2(7)  NULL ,
    [HashDiff]         binary(32)    NOT NULL ,
    [RawScore]         decimal(8,2)  NULL ,
    [IbGrade]          tinyint       NULL ,       -- 1-7
    [PredictedGrade]   tinyint       NULL ,       -- 1-7
    [ResultType]       varchar(20)   NULL ,       -- Predicted, Internal, Moderated, Final
    [AssessedDate]     date          NULL ,
    [AssessedBy]       varchar(100)  NULL ,
    [RecordSource]     varchar(100)  NOT NULL ,
    CONSTRAINT [PK_SatAssessmentResult] PRIMARY KEY CLUSTERED ([AssessmentResultHk], [LoadDts]),
    CONSTRAINT [FK_SatAssessmentResult_Link] FOREIGN KEY ([AssessmentResultHk]) REFERENCES [Ib].[LnkAssessmentResult] ([AssessmentResultHk])
)
go


/* =====================================================================
   4. REFERENCE TABLE
   ===================================================================== */
CREATE TABLE [Ib].[RefAttendanceStatus]
(
    [AttendanceStatusCode] varchar(10)  NOT NULL ,
    [StatusName]           varchar(50)  NOT NULL ,
    [IsPresent]            bit          NOT NULL ,
    [IsExcused]            bit          NOT NULL ,
    CONSTRAINT [PK_RefAttendanceStatus] PRIMARY KEY CLUSTERED ([AttendanceStatusCode])
)
go
INSERT INTO [Ib].[RefAttendanceStatus] VALUES ('P','Present',1,0),('L','Late',1,0),('A','Absent - Unexcused',0,0),('E','Absent - Excused',0,1)
go


/* =====================================================================
   5. BUSINESS VAULT: attendance summary per enrollment (computed satellite)
   ===================================================================== */
CREATE TABLE [Ib].[BvSatAttendanceSummary]
(
    [EnrollmentHk]     binary(32)    NOT NULL ,
    [LoadDts]          datetime2(7)  NOT NULL ,
    [LoadEndDts]       datetime2(7)  NULL ,
    [DaysPresent]      int           NULL ,
    [DaysLate]         int           NULL ,
    [DaysAbsentExcused]   int        NULL ,
    [DaysAbsentUnexcused] int        NULL ,
    [AttendancePercent]   decimal(5,2) NULL ,
    [RecordSource]     varchar(100)  NOT NULL ,
    CONSTRAINT [PK_BvSatAttendanceSummary] PRIMARY KEY CLUSTERED ([EnrollmentHk], [LoadDts]),
    CONSTRAINT [FK_BvSatAttendanceSummary_Link] FOREIGN KEY ([EnrollmentHk]) REFERENCES [Ib].[LnkEnrollment] ([EnrollmentHk])
)
go
