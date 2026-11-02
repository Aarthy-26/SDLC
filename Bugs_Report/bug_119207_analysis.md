| Bug | Cause | Recommended Fix | Time to Fix |
| --- | ----- | --------------- | ----------- |
| IMS Convert – BIM Generation Takes Excessive Time and Eventually Fails, Blocking Subsequent Steps | The Azure DevOps work item does not include description, repro steps, stack trace, comments, attachments, environment, linked items, severity, or module/file references. Because the defect evidence is missing, the exact failing code path for "IMS Convert"/"BIM generation" cannot be proven from the ticket alone. Repo inspection shows likely async/orchestration hotspots related to long-running external processing and polling in `datapowerAI/src/main/java/com/ascendion/datapowerAI/service/AsyncOrchestratorService.java`, `ExternalAsyncJobProcessor.java`, `ExternalAsyncPollingService.java`, `AsyncJobService.java`, and API execution paths in `APIExecutionService.java`. However, no class, method, stack trace, log signature, or IMS/BIM-specific module in the provided repository definitively maps to the reported bug. | First, enrich the ADO bug with missing diagnostic data: repro steps, timestamps, API/job IDs, request payload size, failing endpoint, logs, timeout/error message, environment, and any attachments. Then instrument and review the async execution path: 1) verify whether BIM generation is executed through `AppsAPIService.execute()` -> `AsyncOrchestratorService.execute()` or `AsyncJobService.execute()`, 2) add timing/logging around submit, poll, completion, and result retrieval, 3) review polling limits in `ExternalAsyncJobProcessor` (`pollingIntervalSeconds`, `maxPollAttempts`) and timeout handling, 4) confirm large payload handling limits in `WebClientConfig` and external response processing, 5) add failure propagation so downstream steps are blocked only with explicit terminal state and actionable reason, 6) create tests for long-running jobs and timeout/retry scenarios. Until ticket evidence is added, any code change beyond instrumentation would be speculative. | 4-8 hours for diagnostics/instrumentation; 1-2 days more once exact failure point is identified |

## Bug Analysis Summary

### Executive Summary
- **Bug:** IMS Convert – BIM Generation Takes Excessive Time and Eventually Fails, Blocking Subsequent Steps
- **Work Item ID:** 119207
- **ADO State:** Closed
- **Assigned To:** Vineeth B
- **Area:** AAVA\AAVA - Data Studio\IMS
- **Iteration:** AAVA\PI4\Sprint 5
- **Tags:** UAT- Not Leakage

The Azure DevOps item contains only the title and basic metadata. It does **not** contain a description, repro steps, acceptance criteria details, comments/discussion, attachments, linked items, severity, priority, environment, or stack trace. Because of that, the exact bug-to-code mapping cannot be established with confidence.

A repository review of `aava-ds-datapowerai-api` suggests the most relevant backend areas for a long-running generation flow that eventually fails are the async job submission, external polling, and API execution layers. These areas are credible investigation targets, but they cannot be claimed as root cause without additional bug evidence.

### Parsed Bug Details
- **Title:** IMS Convert – BIM Generation Takes Excessive Time and Eventually Fails, Blocking Subsequent Steps
- **Description:** Not present
- **Reproduction Steps:** Not present
- **Acceptance Criteria:** Not present
- **Comments/Discussion:** Not present
- **Attachments:** Not present
- **Linked Items:** Not present
- **Severity/Priority:** Not present
- **Environment:** Not present
- **Stack Trace / Error Message:** Not present

### Repository Findings
The following files are the most relevant candidates for investigation based on the symptom pattern of "takes excessive time" and "eventually fails":

#### 1. Async orchestration and submission
- `datapowerAI/src/main/java/com/ascendion/datapowerAI/service/AsyncOrchestratorService.java`
  - Handles external async execution lifecycle.
  - Creates `AsyncJob` records and extracts external execution IDs.
  - Likely relevant if BIM generation is delegated to an external service.

- `datapowerAI/src/main/java/com/ascendion/datapowerAI/service/AsyncJobService.java`
  - Handles internal async job submission and tracking.
  - Relevant if the generation flow is internally queued.

#### 2. Polling and completion logic
- `datapowerAI/src/main/java/com/ascendion/datapowerAI/service/ExternalAsyncJobProcessor.java`
  - Polls status/result APIs.
  - Applies `maxPollAttempts` and `pollingIntervalSeconds`.
  - Marks jobs `FAILED` on timeout or polling exceptions.
  - Strong candidate if the issue is "runs too long then fails".

- `datapowerAI/src/main/java/com/ascendion/datapowerAI/service/ExternalAsyncPollingService.java`
  - Scheduler that finds polling candidates and processes them.
  - Could contribute if jobs are polled too slowly or backlog accumulates.

#### 3. API execution path
- `datapowerAI/src/main/java/com/ascendion/datapowerAI/service/APIExecutionService.java`
  - Executes outbound APIs.
  - Contains timeout/error handling for external failures.
  - Relevant if BIM generation depends on an external API that stalls or fails.

- `datapowerAI/src/main/java/com/ascendion/datapowerAI/service/implementation/AppsAPIService.java`
  - Routes requests into sync/internal async/external async execution modes.
  - Useful to confirm which mode the BIM flow uses.

#### 4. Executor and memory/network settings
- `datapowerAI/src/main/java/com/ascendion/datapowerAI/config/AsyncConfiguration.java`
  - `corePoolSize=5`, `maxPoolSize=20`, `queueCapacity=500`
  - Could contribute to queueing delays under load.

- `datapowerAI/src/main/java/com/ascendion/datapowerAI/config/WebClientConfig.java`
  - `MAX_IN_MEMORY_SIZE = 50 * 1024 * 1024`
  - Relevant if BIM generation responses are large.

- `datapowerAI/src/main/java/com/ascendion/datapowerAI/config/RestTemplateConfig.java`
  - Connect timeout 10s, response timeout 30s.
  - Relevant if parts of the generation flow use `RestTemplate` instead of `WebClient`.

### Root Cause Assessment
At this time, the root cause is **undetermined** due to missing defect evidence.

What can be said confidently:
- The ticket symptom is consistent with an async external-job or long-running API orchestration issue.
- The repository contains infrastructure that could fail in this way.
- The repository content provided does **not** expose any explicit `IMS Convert`, `BIM Generation`, or domain-specific service naming that conclusively ties the bug to one method or module.

### Most Likely Failure Patterns to Validate
1. **Polling timeout before external job completes**
   - Candidate file: `ExternalAsyncJobProcessor.java`
   - Risk: `maxPollAttempts` or polling delay causes long-running generation to be marked failed.

2. **Executor backlog / delayed processing**
   - Candidate file: `AsyncConfiguration.java`
   - Risk: limited pool + queued jobs makes user perceive excessive processing time.

3. **External API response timeout or connectivity issue**
   - Candidate file: `APIExecutionService.java`
   - Risk: long BIM generation submit/status/result calls exceed configured thresholds or fail intermittently.

4. **Failure state blocks downstream steps without useful recovery/error propagation**
   - Candidate files: `AppsAPIService.java`, `AsyncOrchestratorService.java`, `ExternalAsyncJobProcessor.java`
   - Risk: once a job is failed, subsequent workflow steps are blocked but error context is too weak for remediation.

### Recommended Actions
#### Priority 1: Gather missing bug evidence
Update the ADO work item with:
- exact repro steps
- failing API endpoint or screen flow
- timestamps
- job IDs / workflow execution IDs
- payload size / input sample
- logs from submit, poll, and failure points
- environment (UAT/QA/Prod)
- actual error message / stack trace
- whether failure always occurs or only under load

#### Priority 2: Add targeted diagnostics
Instrument these files with structured logging and durations:
- `AsyncOrchestratorService.java`
- `ExternalAsyncJobProcessor.java`
- `ExternalAsyncPollingService.java`
- `AsyncJobService.java`
- `APIExecutionService.java`

Recommended telemetry:
- appId / apiId / jobId / externalExecutionId
- submit start/end time
- polling attempt count
- status/result API latency
- terminal state reason
- payload/response size where safe

#### Priority 3: Review timeout and scaling behavior
Check:
- async executor saturation in `AsyncConfiguration.java`
- polling thresholds in `ExternalAsyncJobProcessor.java`
- HTTP timeout settings in `RestTemplateConfig.java`
- payload size / response size in `WebClientConfig.java`

#### Priority 4: Add regression tests
Add tests for:
- long-running async jobs that succeed after many polls
- polling timeout behavior
- downstream blocking after terminal failure
- retry/recovery behavior
- large response bodies if BIM output is sizable

### Prioritized Recommendation
1. **Do not implement a speculative fix yet.** The current ADO data is insufficient.
2. **Instrument the async flow first** to identify whether the bottleneck is submission, polling, timeout, queueing, or downstream state handling.
3. **Use the captured evidence** to isolate the exact method/class and then apply a code-level fix.
4. **Add regression coverage** once the true failure point is known.

### Conclusion
This defect is real from a business perspective, but from an engineering perspective the current work item is incomplete. The likely affected backend area is the async execution/polling path, but the precise root cause cannot be proven from the supplied ADO item and repository snapshot alone. The next correct step is targeted diagnostics, not speculative remediation.