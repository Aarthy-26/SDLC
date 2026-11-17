# AAVA Bug RCA Analysis Report — AAVA - Data Studio\IMS — Active

## Summary
This report covers 6 active bugs in the recursively scoped area path AAVA\AAVA - Data Studio\IMS. Coding Error is the dominant RCA theme at 50.00% (3 defects), followed by Design Gap at 16.67% (1 defect), with Incorrect Design and Data/Configuration Issue at 16.67% each. Total estimated implementation and verification effort is 34h, concentrated in the two largest fixes at 8h each and a broader cluster of 6h fixes. No defects lacked enough data to analyze, and the clearest remediation signal is to tighten backend-to-UI status synchronization, artifact lifecycle handling, and success/error response validation so the interface reflects actual processing outcomes.

---

## Summary Statistics by RCA Category

| RCA Category | Count | Percentage |
| --- | ---: | ---: |
| Coding Error | 3 | 50.00% |
| Design Gap | 1 | 16.67% |
| Incorrect Design | 1 | 16.67% |
| Data/Configuration Issue | 1 | 16.67% |
| Total | 6 | 100.00% |

---

## State Distribution

| State | Count | Percentage |
| --- | ---: | ---: |
| Active | 6 | 100.00% |
| Total | 6 | 100.00% |

---

## Effort Profile

| Effort Tier | Defect Count | Summed Effort |
| --- | ---: | ---: |
| 1h to <4h | 0 | 0h |
| 4h to <8h | 4 | 24h |
| 8h to <12h | 2 | 16h |
| 1.5d to <3d | 0 | 0d |
| 3d+ | 0 | 0d |

Effort is derived per defect from the recommended fix scope, 1 day = 8 hours, sub-12h in hours.

Total effort: 34h. Spread: 4h to 8h. Unestimated count: 0.

---

## Detailed Defect Table

| Defect ID | Title | State | RCA Category | Root Cause | Recommended Fix | Time to Fix |
| --- | --- | --- | --- | --- | --- | --- |
| 111461 | IMS - Cognos to PBI - BIM Conversion Status Remains "In Progress" on C… | Active | Design Gap | The convert screen does not consume or refresh final backend job completion state, so the UI status remains stale after conversion completion. | Update the convert workflow and status polling/state refresh logic to read completed backend job status and auto-refresh the BIM row; verify with an end-to-end conversion status sync test. | 8h (cross-module state synchronization) |
| 111488 | IMS - WebFocus to Power BI - Test Use Cases Option Remains Disabled Due… | Active | Data/Configuration Issue | PBIT generation is failing upstream, leaving the dependent Test UseCases action disabled because the required artifact is not produced. | Fix the PBIT generation path and its dependency/configuration handling in the conversion pipeline, then verify artifact creation and Test UseCases enablement in an integration test. | 8h (pipeline dependency failure) |
| 111212 | IMS - PBI_Looker - Failed to Download Report message is visible after s… | Active | Incorrect Design | The download flow is showing a failure message even after successful artifact generation because success and error UI states are not mutually gated. | Correct the download result handling in the Looker conversion UI/service so failure messaging is shown only on failed responses; verify with successful and failed download scenarios. | 6h (UI and response-state handling) |
| 111214 | IMS - PBI - Looker - Error is Displayed When trying to Click on Test Ca… | Active | Coding Error | The Test Case action is invoking a failing handler or request path when the icon is clicked, causing an avoidable runtime or service error. | Fix the Test Case click handler and downstream request validation in the Power BI to Looker module, then verify icon behavior with a targeted regression test. | 6h (module logic correction) |
| 122544 | IMS - Azure-QE - MicroStrategy to Power BI - File Not Deleted Despite S… | Active | Coding Error | The delete workflow reports success without removing the file record from storage or without refreshing the grid from the persisted delete result. | Correct delete transaction handling and post-delete grid refresh in the extract module, then verify record removal in backend storage and UI after deletion. | 4h (single workflow correction) |
| 122501 | IMS - Azure-QE - Cognos to PowerBi - No Files is visible on extract pag… | Active | Coding Error | Empty-state messaging is implemented inconsistently across Extract and Convert pages, producing conflicting labels for the same no-data condition. | Standardize no-data message rendering in the shared workbench UI components and verify both pages show the same empty-state text. | 2h (shared UI text logic) |

---

## RCA Themes & Recommendations

- Coding Error: Multiple defects stem from incorrect frontend or workflow logic, including inconsistent empty-state messaging, delete confirmation without persisted removal, and failing action handlers. Remediation: add targeted unit and regression coverage for user actions that mutate state or trigger downloads so UI success states always reconcile with persisted backend outcomes.
- Design Gap: Status orchestration is missing a robust mechanism to propagate backend completion into the active screen state. Remediation: implement a single status synchronization contract for long-running jobs and verify it with an end-to-end completion-state refresh test.
- Incorrect Design: Success and failure feedback are not cleanly separated in artifact download interactions. Remediation: redesign response handling so notification state is derived from explicit response status branches and validate with positive/negative API mocks.
- Data/Configuration Issue: Dependent feature enablement breaks when required generation outputs are not produced upstream. Remediation: add pipeline validation and artifact existence checks before enabling downstream actions, backed by integration coverage around generation prerequisites.

---

## Data Quality

Insufficient-data defects: 0.

Missing fields observed in insufficient-data records: —.

---

## Execution Notes

- Generated date: 2026-11-17.
- Area path queried: AAVA\AAVA - Data Studio\IMS (recursive).
- States covered: Active.
- Fetched count vs final deduplicated count: 6 vs 6.
- Collapsed duplicate IDs: none after mandatory ID-based check.
- RCAD mappings into taxonomy: none required; one defect inferred from available title and repro details where RCAD was blank.
- Retries: none required.
- Sizing caveats: effort reflects only implementation plus the explicitly named verification step per defect; it excludes queue time, resourcing, and release dependencies.