# AAVA Bug RCA Analysis Report — AAVA - Data Studio\DMS, AAVA - Data Studio\IMS, AAVA - Data Studio\DEX, AAVA - Data Studio\DPAI — Active

## Summary
This report covers Active Bug work items under the recursively queried area paths AAVA\AAVA - Data Studio\DMS, AAVA\AAVA - Data Studio\IMS, AAVA\AAVA - Data Studio\DEX, and AAVA\AAVA - Data Studio\DPAI. A total of 7 defects were fetched and 7 remained after mandatory ID-based deduplication. Coding Error is the dominant RCA theme at 42.86%, followed by Design Gap at 28.57% and Incorrect Design at 28.57%. Total estimated implementation and verification effort is 34h, concentrated in the UI and workflow-state defects in IMS. No defects required an Insufficient data classification; the strongest remediation signal is to tighten UI state synchronization and success/error handling against backend outcomes.

---

## Summary Statistics by RCA Category

| RCA Category | Count | Percentage |
|---|---:|---:|
| Coding Error | 3 | 42.86% |
| Design Gap | 2 | 28.57% |
| Incorrect Design | 2 | 28.57% |
| Total | 7 | 100.00% |

---

## State Distribution

| State | Count | Percentage |
|---|---:|---:|
| Active | 7 | 100.00% |
| Total | 7 | 100.00% |

---

## Area Distribution

| Area Path | Count | Percentage |
|---|---:|---:|
| AAVA\AAVA - Data Studio\IMS | 6 | 85.71% |
| AAVA\AAVA - Data Studio\DEX | 1 | 14.29% |
| Total | 7 | 100.00% |

---

## Effort Profile

| Effort Tier | Defect Count | Summed Effort |
|---|---:|---:|
| 1h-3h | 2 | 6h |
| 4h-7h | 4 | 22h |
| 8h-11h | 1 | 8h |
| Total | 7 | 34h |

Effort is derived per defect from the recommended fix scope, 1 day = 8 hours, sub-12h in hours.

Total effort: 34h. Spread: 3h to 8h. Unestimated count: 0.

---

## Detailed Defect Table

| Defect ID | Title | State | Area Path | RCA Category | Root Cause | Recommended Fix | Time to Fix |
|---|---|---|---|---|---|---|---|
| 111461 | IMS - Cognos to PBI - BIM Conversion Status Remains "In Progress" on Co… | Active | IMS | Design Gap | The convert screen does not refresh or subscribe to backend completion status, so the UI state remains stale after the conversion job finishes. | Update the IMS conversion status workflow to poll or receive completion events from the backend and verify the Convert screen transitions to Completed when Monitoring Logs do. | 8h (verification needs integration test) |
| 111488 | IMS - WebFocus to Power BI - Test Use Cases Option Remains Disabled Due t… | Active | IMS | Design Gap | The enablement of Test Use Cases is tightly coupled to successful PBIT artifact creation and lacks resilient handling when generation flow fails. | Fix the PBIT generation path and the dependent UI enablement logic in IMS convert flow, then verify the option enables after successful artifact creation. | 6h (change spans workflow state and dependent UI logic) |
| 122544 | IMS - Azure-QE - MicroStrategy to Power BI - File Not Deleted Despite Suc… | Active | IMS | Coding Error | The delete action reports success without removing the record from persistence or refreshing the metadata grid from the actual backend state. | Correct the delete API handling and post-delete grid refresh in IMS extract flow, then verify the file disappears from UI and backend storage after confirmation. | 5h (manual regression required) |
| 122501 | IMS - Azure-QE - Cognos to PowerBi - No Files is visible on extract page … | Active | IMS | Coding Error | Empty-state messaging is implemented inconsistently across extract and convert screens, causing divergent no-data responses for the same condition. | Refactor the shared no-data UI handling in IMS workbench screens and verify a single consistent empty-state message across extract and convert. | 5h (shared module UI correction) |
| 111214 | IMS - PBI - Looker - Error is Displayed When trying to Click on Test Case… | Active | IMS | Incorrect Design | The Test Case action is exposed without guarding preconditions or handling unsupported report state before invocation. | Add precondition checks and safe error handling around the Test Case action in the PBI-to-Looker convert screen, then verify valid items open without error. | 4h (single-module interaction guard) |
| 111212 | IMS - PBI_Looker - Failed to Download Report message is visible after suc… | Active | IMS | Coding Error | The download workflow surfaces a failure toast despite successful artifact generation because success and error response handling are misaligned. | Fix the download response evaluation and notification logic in the PBI-to-Looker module, then verify successful generation and download show no failure message. | 3h (single-function logic correction) |
| 122166 | DEX - Azure-QE - Search functionality is not working properly when email … | Active | DEX | Incorrect Design | The search implementation does not normalize or match email-format values correctly for the created-by field. | Correct the DEX workbench search/filter logic for email-based created-by values and verify exact and partial email searches return expected records. | 3h (single-module filter logic) |

---

## RCA Themes & Recommendations

- Coding Error: Multiple defects show mismatched success/error handling and inconsistent UI refresh after backend actions. Remediation: add response-contract tests for delete, download, and empty-state handlers so UI outcomes are asserted against backend results.
- Design Gap: Workflow completion and dependent action enablement rely on missing or weak state propagation between backend jobs and UI screens. Remediation: introduce a standard async job-status contract with explicit terminal states and verify each screen updates from that contract.
- Incorrect Design: User actions are allowed without sufficient guardrails for data shape or readiness. Remediation: define control enablement and validation rules for search and action icons, then test those rules with representative input formats and states.

---

## Data Quality

Insufficient-data defects: 0.

Missing fields observed in insufficient-data cases: —.

---

## Execution Notes

- Generated date: 2026-11-17.
- Area paths queried recursively: AAVA\AAVA - Data Studio\DMS, AAVA\AAVA - Data Studio\IMS, AAVA\AAVA - Data Studio\DEX, AAVA\AAVA - Data Studio\DPAI.
- States queried: Active.
- Fetched count: 7. Final deduplicated count: 7. Collapsed IDs: none after ID-based duplicate check.
- Returned defects were present only in AAVA\AAVA - Data Studio\IMS and AAVA\AAVA - Data Studio\DEX; DMS and DPAI contributed zero Active bugs in this result set.
- RCAD mappings into taxonomy: none required from stored values; items with blank RCAD were inferred from title and repro steps.
- Retries: none required.
- Sizing caveats: effort values estimate implementation plus named verification only; they exclude queue time, resourcing constraints, and release scheduling.
