# Runbook: Payout orchestration demo

This runbook is for presenting the demo. The [README](README.md) explains what it is and how it's built.

## What it shows

A bank's payout service validates a payout, reserves the funds, checks the FX quote, sends the instruction to a payment rail, and waits for the bank to confirm. One Temporal Workflow, `PayoutWorkflow`, runs every step, and the demo makes five paths through it visible on one screen.

The demo makes three problems visible:

- **Downstream systems fail, sometimes briefly and sometimes for good.** The rail times out twice and then accepts, on the same idempotency key, with no retry loop in the code. A permanent rejection stops at once and unwinds what the payout already did: the bank instruction is reversed and the reserved funds are released. Scenarios 2 and 3 show it.
- **A payout waits on people and on banks, for longer than any request lasts.** Large payouts wait for a human approver, with a deadline. The bank confirms out of band, or never does, and the Workflow has to find out. Scenarios 4 and 5 show it, and stopping the only Worker in the middle of an approval shows the wait survives.
- **An AI assistant helps the approver without being able to move money.** Before an approval, a model chooses which synthetic customer lookups to run and drafts an advisory brief. Each step is an Activity with a short timeout and no retries, so a slow or failing model leaves the payout waiting for a human, with "AI brief unavailable", rather than stuck.

## Before you start

- Install Docker, any JDK 21, and the [Temporal CLI](https://docs.temporal.io/cli) **1.8.3** exactly. The CLI version decides the bundled Server and Web UI, and `make start` refuses to run on any other version. `mise install` gets both the JDK and the CLI from `.mise.toml`.
- Optional, for AI briefs on a laptop: install Ollama and run `ollama pull qwen3.5:9b` once. Without it, every payout still runs and the brief reads "AI brief unavailable".
- Clone the repo and run `make preflight`. It fetches the Tailwind binary on a clean checkout and reports the Docker images, CLI, JDK, fonts and Ollama model it found.
- On [tmprl-demo.cloud](https://payout-orchestration.tmprl-demo.cloud), the catalog entry builds this branch's `docker/cloud.Dockerfile` and runs it against a Temporal Cloud Namespace. Nothing needs installing. What changes there:
  1. The model is OpenAI `gpt-4.1-mini`, with the key injected from a secret, instead of local Qwen.
  2. The right-hand pane is an **Open current workflow ↗** link to the Temporal Cloud UI instead of the embedded Temporal UI, and the **Temporal UI** row of shortcut buttons is hidden.
  3. There is no **Metrics** tab, so no load simulator and no Grafana. Skip beat 6.
  4. The catalog entry declares all eleven custom Search Attributes, and the platform's Temporal proxy lets the app keep using `localhost:7233`.
  5. Failure injection is stored in the container's filesystem, so a pod restart clears it. Workflow histories stay in Temporal Cloud.

## Start it

From the repository root:

```bash
make start     # Temporal dev server, Ollama if installed, Caddy/Prometheus/Grafana, the API and one Worker
make seed      # optional: six payouts in six end states, so the workflow list isn't empty
```

Open http://localhost:8080. A healthy start shows:

- The header reads `Kotlin · Temporal Java SDK 1.38.0 · payouts · default · worker UP`.
- The scenario buttons **1** to **5** are on the left, with **Successful payout** selected.
- The **Workers** panel reads **1 / 10 running**.
- The right-hand pane shows the Temporal UI's workflow list.

`make start` ends by printing the URLs: the Temporal UI at http://localhost:8233 (also embedded on the right), Grafana at http://localhost:3000, and Prometheus at http://localhost:9090. The backend log is `/tmp/payout-demo-backend.log`.

## The walkthrough

The scenario buttons at the top left are numbered **1** to **5**; the scenario's name appears below them. Every scenario's start button reads **Start $<amount> payout**, and starting one points the right-hand pane at that payout's **Timeline** tab. On Temporal Cloud, press **Open current workflow ↗** instead.

Two amounts decide the path, and they're worth saying out loud early: under $100 the bank answers inline with no callback, and from $500 a human has to approve. Each simulated step takes 1 to 2.5 seconds, so you can follow along.

Every payout you start from this page also runs the AI steps, before the approval check. Their results appear under **AI investigation brief** in the **Interact with this execution** panel.

### 1. A payout runs start to finish

1. Press **1** (**Successful payout**). Leave the amount at $75.00 and the review fact at "typical customer behavior".
2. Press **Start $75.00 payout**.
3. The **Live status** card steps through `VALIDATING`, `FUNDS_RESERVED`, `SETTLING_WITH_BANK` and ends on `COMPLETED`. The Timeline fills with one bar per step, each labeled in business terms: "Validate payout request", "Reserve funds in the ledger", "Submit instruction to bank rail".
4. With a model available, the brief says **No additional review needed**, and **Investigation skipped** gives the reason. The Timeline has an "AI decided no investigation is needed" bar for that decision.
5. Say: "This is the whole business process, written as ordinary sequential code in one Workflow. Each step is an Activity, and every one of them is in the history on the right. Nobody had to correlate logs."

### 2. The rail times out twice, then accepts

1. Press **2** (**Transient failure + retry**) and **Start $75.00 payout**.
2. The payout completes. The **Live status** card shows **attempt 3**. The two failed attempts fail at once and retry after about a second each, so the retrying is over before you can open it.
3. Say: "The rail failed twice. There's no retry loop anywhere in our code. The retry policy is declared on the Activity, and Temporal ran the attempts. Every attempt sent the same idempotency key, so the rail can tell it's one payment."
4. For proof, open the "Submit instruction to bank rail" event in the history. There are no events for the failed attempts; the Activity's started event records `attempt 3`, and its input carries the idempotency key `<payout id>-rail-1`.

### 3. The rail rejects the payout, and it unwinds

1. Press **3** (**Permanent failure + compensation**) and **Start $75.00 payout**.
2. The rail step fails on the first attempt. The status moves to `COMPENSATING`, then `COMPENSATED`, and ends on `FAILED`.
3. In the Timeline, "Reverse bank instruction" runs, then "Release reserved funds", then the outcome is recorded and the customer notified.
4. Say: "This time the rail said no for good. That failure is marked non-retryable, so Temporal doesn't try again. The Workflow undoes what it already did, newest step first, and those undo steps retry until they succeed, because giving up would leave the customer's money reserved."

### 4. A large payout waits for a person, and survives losing its Worker

1. Press **4** (**Human approval + timeout**). Leave the amount at $2,500.00 and the review facts at "new recipient" and "unusual amount for this customer". Pick **cust-0002 · unresolved account alert** under **Synthetic customer record** if you want the lookups to find something.
2. Press **Start $2,500.00 payout**. The brief appears with a recommendation, and under **Agent tool calls · synthetic data** the lookups the model chose. The status is `AWAITING_APPROVAL`, tier `SENIOR`, and **Approve** and **Reject** appear.
3. Say: "The model picked which customer records to look up and wrote a recommendation. It can't approve or move money. Now the Workflow is waiting for a person. Nothing is polling and no thread is held open."
4. You have 60 seconds from the brief appearing to finish this beat. Press **Stop a worker** in the **Workers** panel. It reads **0 / 10 running** and "nothing is polling the task queue". The **Live status** card can stop updating, because it reads the Workflow through a Query, and a Query needs a Worker.
5. Press **Approve**. The note reads "Approval sent — signals are fire-and-forget, watch the status above." Temporal records the Signal without a Worker.
6. Press **Start a worker**. Once the new Worker is up, the payout moves on to `APPROVED` and then `AWAITING_BANK_CONFIRMATION`, and the bank buttons appear.
7. Say: "We killed the only process running this code, and the approval still landed. A new Worker picked up the history and carried on from the exact step."
8. Press **Bank: completed**. The payout ends `COMPLETED`.

If the 60 seconds run out, the approval timer fires and the payout compensates and ends `CANCELLED`. That's a fine way to show the deadline: let it happen on purpose, or press **Reject** to show a declined approval.

### 5. The bank never confirms, so the Workflow asks

1. Press **5** (**Unknown bank status**). Under **When the workflow polls, the bank eventually...**, leave **Bank confirms completed** selected.
2. Press **Start $250.00 payout**. At $250 there's no approver, and the payout reaches `AWAITING_BANK_CONFIRMATION`.
3. Don't press a bank button. After 45 seconds the wait ends and the status moves to `POLLING_BANK_STATUS`. To skip the wait, press **Bank: unknown**.
4. In the Temporal UI, the pending Activity "Poll bank for payment status" shows its attempt count rising and the last failure `bank still reports the instruction as pending (poll 3)`. The fourth poll gets an answer and the payout completes.
5. Say: "The bank took the instruction and went quiet. Rather than page someone, the Workflow asks the bank. The polling loop is the Activity's retry policy: each 'still pending' is a failed attempt, and Temporal schedules the next one."
6. Run it again with **Bank never answers**. Polling runs out of attempts, the payout compensates, and it ends `FAILED`. Open the Workflow's result in the Temporal UI: `failureCategory` is `UNKNOWN_BANK_STATUS`. The **Live status** card doesn't show that field.

### 6. Load and Worker metrics (local only)

1. Switch to the **Metrics** tab. Pick **Safe 5/s · 5 min** and press **▸ Start simulation**.
2. The strip counts started, in-flight, completed, failed, compensated, awaiting approval, unknown and retries every second. The Grafana dashboard below samples Prometheus every 10 seconds.
3. Run `make workers N=3` in a terminal, or press **Start a worker** on the **Demo** tab twice. Each Worker is a separate JVM and gets its own series on the dashboard.
4. Press **■ STOP** when you're done.

## Show it in code

All links point at commit [`3a044a5`](https://github.com/temporal-sa/temporal-demo-payments/commit/3a044a58dffc88a0f1319bfecff581804410e893) on the `codex/cloud-registry-adapter` branch, which is what tmprl-demo.cloud deploys. Open a file locally with `code -g <path>:<line>`. The Workflow is in `backend/kotlin/src/main/kotlin/com/example/payouts/workflow/PayoutWorkflowImpl.kt`; the paths below shorten `backend/kotlin/src/main/kotlin/com/example/payouts/` to `…/payouts/`.

### Retry a failing step without writing a retry loop

**When to use it:** after beat 2, or when someone asks what the retry code looks like.

**Where it is:** [`…/payouts/workflow/PayoutWorkflowImpl.kt` lines 42–48](https://github.com/temporal-sa/temporal-demo-payments/blob/3a044a58dffc88a0f1319bfecff581804410e893/backend/kotlin/src/main/kotlin/com/example/payouts/workflow/PayoutWorkflowImpl.kt#L42-L48) and [lines 195–204](https://github.com/temporal-sa/temporal-demo-payments/blob/3a044a58dffc88a0f1319bfecff581804410e893/backend/kotlin/src/main/kotlin/com/example/payouts/workflow/PayoutWorkflowImpl.kt#L195-L204) (`code -g backend/kotlin/src/main/kotlin/com/example/payouts/workflow/PayoutWorkflowImpl.kt:195`)

```kotlin
/** [DEMO_MIN_ATTEMPTS]-[DEMO_MAX_ATTEMPTS] attempts, 1s initial, x1.1, capped at 20s. */
private fun demoRetry(): io.temporal.common.RetryOptions = RetryOptions {
    setInitialInterval(Duration.ofSeconds(1))
    setBackoffCoefficient(1.1)
    setMaximumInterval(Duration.ofSeconds(20))
    setMaximumAttempts(DEMO_MIN_ATTEMPTS + rng.nextInt(DEMO_MAX_ATTEMPTS - DEMO_MIN_ATTEMPTS + 1))
}
```

```kotlin
private val rail = Workflow.newActivityStub(
    RailActivities::class.java,
    ActivityOptions {
        setStartToCloseTimeout(Duration.ofSeconds(10))
        setScheduleToCloseTimeout(Duration.ofMinutes(5))
        setTaskQueue(TASK_QUEUE)
        setSummary("Submit instruction to bank rail")
        setRetryOptions(demoRetry().toBuilder().setDoNotRetry("RailRejected", "BankRejected").build())
    },
)
```

**What you need to understand:**

- The retry policy is part of the Activity options. The Workflow calls `rail.submitToRail(...)` once, and Temporal retries a failed attempt on the schedule: 1 second, then 10% longer each time, capped at 20 seconds.
- Retries stop at whichever comes first: the attempt cap, or the 5-minute schedule-to-close timeout, which covers all attempts. Each attempt gets 10 seconds (start-to-close).
- `setDoNotRetry("RailRejected", "BankRejected")` names failure types that are never retried. The same rejection from the rail fails once and goes straight to compensation (beat 3).
- The attempt cap is random, 7 to 10 per payout, which is a demo choice so executions differ. `Workflow.newRandom()` is seeded per execution and replays the same value. A customer would set a fixed cap, or none.
- Failed attempts write no events. While the Activity retries, the Temporal UI shows its attempt count and last failure from the pending Activity; when it closes, the started event records the final attempt number.
- `setSummary` is the label in the Timeline. It's why the history reads "Submit instruction to bank rail" rather than a method name.

**Docs:** [Retry Policies](https://docs.temporal.io/encyclopedia/retry-policies), [Activity timeouts](https://docs.temporal.io/encyclopedia/detecting-activity-failures)

### Undo completed steps when a later one fails

**When to use it:** after beat 3, or when someone asks how the money gets released.

**Where it is:** [`…/payouts/workflow/PayoutWorkflowImpl.kt` lines 277–283](https://github.com/temporal-sa/temporal-demo-payments/blob/3a044a58dffc88a0f1319bfecff581804410e893/backend/kotlin/src/main/kotlin/com/example/payouts/workflow/PayoutWorkflowImpl.kt#L277-L283), [lines 362–383](https://github.com/temporal-sa/temporal-demo-payments/blob/3a044a58dffc88a0f1319bfecff581804410e893/backend/kotlin/src/main/kotlin/com/example/payouts/workflow/PayoutWorkflowImpl.kt#L362-L383) and [lines 589–601](https://github.com/temporal-sa/temporal-demo-payments/blob/3a044a58dffc88a0f1319bfecff581804410e893/backend/kotlin/src/main/kotlin/com/example/payouts/workflow/PayoutWorkflowImpl.kt#L589-L601) (`code -g backend/kotlin/src/main/kotlin/com/example/payouts/workflow/PayoutWorkflowImpl.kt:277`)

```kotlin
// Registered before the call it undoes, so it exists even if reserveFunds moves
// money and then fails before returning. Keyed on payoutId, which is known now;
// the reservation id is not.
saga.addCompensation {
    compensationLedger.releaseReservedFunds(ReleaseFundsRequest(request.payoutId, ""))
}
ledger.reserveFunds(ReserveFundsRequest(request.payoutId, request.amount))
// ...
saga.addCompensation {
    reversalReference = compensationRail.reverseRailInstruction(
        ReverseRailRequest(
            payoutId = request.payoutId,
            idempotencyKey = request.idempotencyKey,
            rail = request.rail,
            reason = failure.name,
        ),
    ).reversalReference
}
val submission = rail.submitToRail(
    // ...
)
```

```kotlin
// Compensation must survive the cancellation that triggered it.
Workflow.newDetachedCancellationScope {
    // Unwinds in reverse registration order: reverse the bank instruction first,
    // then release the funds we reserved.
    saga.compensate()
    advance(BusinessStatus.COMPENSATED, "Bank instruction reversed and reserved funds released")
    ledgerOutcome.markPayout(
        MarkPayoutRequest(request.payoutId, if (cancelled) "CANCELLED" else "FAILED", failure.name),
    )
    notifier.notify(
        NotifyRequest(request.payoutId, "customer", "Payout ${failure.name} - funds returned"),
    )
}.run()
```

**What you need to understand:**

- `Saga` is a helper in the Java SDK. Each `addCompensation` pushes an undo step, and `compensate()` runs them in reverse order. That's why beat 3 reverses the bank instruction before releasing the funds.
- Each undo step is registered before the call it undoes, so a step that did its work and then failed still gets undone. The release is keyed on the payout ID because the reservation ID doesn't exist yet.
- The saga is created with `setContinueWithError(true)` ([line 262](https://github.com/temporal-sa/temporal-demo-payments/blob/3a044a58dffc88a0f1319bfecff581804410e893/backend/kotlin/src/main/kotlin/com/example/payouts/workflow/PayoutWorkflowImpl.kt#L262)): if one undo step fails for good, the next still runs.
- Every failure path lands in the same `catch` and the same `compensate()`: a rail rejection, a bank rejection, a declined or timed-out approval, and a bank that never answers.
- The detached cancellation scope keeps the clean-up running even if the Workflow itself is being cancelled.
- A validation failure also reaches this code, with nothing registered yet, so it runs no undo steps and still ends `FAILED`.

**Docs:** [Saga pattern](https://docs.temporal.io/design-patterns/saga-pattern), [Java: cancellation scopes](https://docs.temporal.io/develop/java/workflows/cancellation)

### Undo steps that don't give up

**When to use it:** when someone asks what happens if the ledger is down during compensation.

**Where it is:** [`…/payouts/workflow/PayoutWorkflowImpl.kt` lines 109–122](https://github.com/temporal-sa/temporal-demo-payments/blob/3a044a58dffc88a0f1319bfecff581804410e893/backend/kotlin/src/main/kotlin/com/example/payouts/workflow/PayoutWorkflowImpl.kt#L109-L122) (`code -g backend/kotlin/src/main/kotlin/com/example/payouts/workflow/PayoutWorkflowImpl.kt:109`)

```kotlin
/** Compensation has no attempt cap: attempts unset means unlimited. Flat 5s backoff. */
private val compensationLedger = Workflow.newActivityStub(
    LedgerActivities::class.java,
    ActivityOptions {
        setStartToCloseTimeout(Duration.ofSeconds(10))
        setScheduleToCloseTimeout(Duration.ofHours(1))
        setTaskQueue(TASK_QUEUE)
        setSummary("Release reserved funds")
        setRetryOptions {
            setInitialInterval(Duration.ofSeconds(5))
            setBackoffCoefficient(1.0)
        }
    },
)
```

**What you need to understand:**

- With no maximum attempts set, the retry policy has no attempt cap. A backoff coefficient of 1.0 retries every 5 seconds instead of backing off.
- The one-hour schedule-to-close is the only limit. Giving up early would leave the customer's money reserved, so the undo step keeps trying while the ledger recovers.
- The reversal at the rail uses the same settings ([lines 124–137](https://github.com/temporal-sa/temporal-demo-payments/blob/3a044a58dffc88a0f1319bfecff581804410e893/backend/kotlin/src/main/kotlin/com/example/payouts/workflow/PayoutWorkflowImpl.kt#L124-L137)). The forward steps use the capped policy instead, and the notifier is capped so a dead notifier can't hold a payout in `COMPENSATING`.
- The undo stubs are separate from the forward stubs only so each has its own retry policy and Timeline label. They call the same Activity implementations.

### Wait for a human, with a deadline

**When to use it:** during beat 4, or when someone asks how the approval wait works.

**Where it is:** [`…/payouts/workflow/PayoutWorkflowImpl.kt` lines 444–477](https://github.com/temporal-sa/temporal-demo-payments/blob/3a044a58dffc88a0f1319bfecff581804410e893/backend/kotlin/src/main/kotlin/com/example/payouts/workflow/PayoutWorkflowImpl.kt#L444-L477), with the Signal handler at [lines 417–420](https://github.com/temporal-sa/temporal-demo-payments/blob/3a044a58dffc88a0f1319bfecff581804410e893/backend/kotlin/src/main/kotlin/com/example/payouts/workflow/PayoutWorkflowImpl.kt#L417-L420) (`code -g backend/kotlin/src/main/kotlin/com/example/payouts/workflow/PayoutWorkflowImpl.kt:444`)

```kotlin
override fun approve(request: ApprovalDecisionRequest) {
    // A decision arriving after the deadline timer fired is ignored.
    if (!approvalDeadlinePassed) approval = request
}
```

```kotlin
private fun awaitApproval(request: ProcessPayoutRequest): Boolean {
    val summary = "Waiting for $approvalTier approval"
    advance(BusinessStatus.AWAITING_APPROVAL, summary)
    // An explicit timer, not await's built-in timeout: the signal and the timer are
    // ordered by which callback ran first, which replays identically. The timer is owned
    // by a cancellation scope because a bare newTimer is not cancelled by await, and an
    // uncancelled one stays in history as an unresolved TimerStarted.
    val deadline = Workflow.newCancellationScope(Runnable {
        Workflow.newTimer(
            Duration.ofSeconds(approvalTimeoutSeconds()),
            TimerOptions.newBuilder().setSummary(summary).build(),
        )
            .thenApply { approvalDeadlinePassed = true }
    })
    deadline.run()
    Workflow.await { approval != null || approvalDeadlinePassed }
    deadline.cancel("approval decided")

    val decision = approval
    return when {
        decision == null -> {
            failure = FailureCategory.APPROVAL_TIMEOUT
            false
        }
        // ...
    }
}
```

**What you need to understand:**

- `Workflow.await` blocks the Workflow until a Signal sets `approval` or the timer fires. Both are events in the history, so the Workflow holds no thread and no process while it waits. That's why beat 4 survives stopping the only Worker.
- The Signal is written to the history when Temporal receives it, whether or not a Worker is running. The timer runs on the Temporal Service, not in the Worker.
- The deadline is 60 seconds for payouts started from the page, which run the AI brief, and 30 seconds otherwise ([line 645](https://github.com/temporal-sa/temporal-demo-payments/blob/3a044a58dffc88a0f1319bfecff581804410e893/backend/kotlin/src/main/kotlin/com/example/payouts/workflow/PayoutWorkflowImpl.kt#L645)). The timer starts after the brief is ready. A real approval would wait hours or days; the code doesn't change.
- Whichever arrives first wins: a late Approve is ignored by the handler. A timeout and a rejection both compensate and end `CANCELLED`, with different failure categories.
- The tier comes from the USD equivalent after the FX check: under $500 no approver, up to $1,000 `L1`, above that `SENIOR` ([`…/payouts/model/domain/Domain.kt` lines 86–94](https://github.com/temporal-sa/temporal-demo-payments/blob/3a044a58dffc88a0f1319bfecff581804410e893/backend/kotlin/src/main/kotlin/com/example/payouts/model/domain/Domain.kt#L86-L94)).
- The bank callback wait after approval is the same pattern with a 45-second timer ([lines 524–539](https://github.com/temporal-sa/temporal-demo-payments/blob/3a044a58dffc88a0f1319bfecff581804410e893/backend/kotlin/src/main/kotlin/com/example/payouts/workflow/PayoutWorkflowImpl.kt#L524-L539)).

**Docs:** [Java: Signals](https://docs.temporal.io/develop/java/workflows/message-passing), [Durable timers](https://docs.temporal.io/workflow-execution/timers-delays)

### The live status is a Query

**When to use it:** during beat 4, when the status card stops while no Worker is running, or when someone asks where the status is stored.

**Where it is:** [`…/payouts/api/PayoutController.kt` lines 126–130](https://github.com/temporal-sa/temporal-demo-payments/blob/3a044a58dffc88a0f1319bfecff581804410e893/backend/kotlin/src/main/kotlin/com/example/payouts/api/PayoutController.kt#L126-L130) (`code -g backend/kotlin/src/main/kotlin/com/example/payouts/api/PayoutController.kt:126`), answered by `currentStatus()` at [`…/payouts/workflow/PayoutWorkflowImpl.kt` lines 428–440](https://github.com/temporal-sa/temporal-demo-payments/blob/3a044a58dffc88a0f1319bfecff581804410e893/backend/kotlin/src/main/kotlin/com/example/payouts/workflow/PayoutWorkflowImpl.kt#L428-L440)

```kotlin
/** Backed by the workflow Query rather than a cache: Temporal holds the state. */
@GetMapping("/payouts/{workflowId}/status")
fun status(@PathVariable workflowId: String): ResponseEntity<PayoutStatusResponse> = runCatching {
    ResponseEntity.ok(client.newWorkflowStub(PayoutWorkflow::class.java, workflowId).currentStatus())
}.getOrElse { ResponseEntity.notFound().build() }
```

**What you need to understand:**

- The page asks the Workflow for its state once a second. There's no database of payout status beside Temporal.
- A Query is answered by a Worker from the Workflow's state, and it adds nothing to the history. With no Worker running there's nothing to answer it, which is what beat 4 shows. Signals and timers don't need a Worker.
- The Approve and Bank buttons follow this status, so the page offers whichever Signal the Workflow is waiting for.

**Docs:** [Workflow message passing](https://docs.temporal.io/encyclopedia/workflow-message-passing)

### Poll the bank with a retry policy

**When to use it:** after beat 5, or when someone asks where the polling loop is.

**Where it is:** [`…/payouts/workflow/PayoutWorkflowImpl.kt` lines 206–224](https://github.com/temporal-sa/temporal-demo-payments/blob/3a044a58dffc88a0f1319bfecff581804410e893/backend/kotlin/src/main/kotlin/com/example/payouts/workflow/PayoutWorkflowImpl.kt#L206-L224) and the Activity at [`…/payouts/activities/ActivitiesImpl.kt` lines 118–134](https://github.com/temporal-sa/temporal-demo-payments/blob/3a044a58dffc88a0f1319bfecff581804410e893/backend/kotlin/src/main/kotlin/com/example/payouts/activities/ActivitiesImpl.kt#L118-L134) (`code -g backend/kotlin/src/main/kotlin/com/example/payouts/activities/ActivitiesImpl.kt:123`)

```kotlin
/**
 * The retry policy is the polling loop: `pollBankStatus` throws a retryable failure while
 * the bank reports "pending", so Temporal re-invokes it on a schedule.
 * ...
 */
private val bank = Workflow.newActivityStub(
    BankActivities::class.java,
    ActivityOptions {
        setStartToCloseTimeout(Duration.ofSeconds(10))
        setScheduleToCloseTimeout(Duration.ofMinutes(5))
        setTaskQueue(TASK_QUEUE)
        setSummary("Poll bank for payment status")
        setRetryOptions(demoRetry())
    },
)
```

```kotlin
override fun pollBankStatus(request: BankStatusProbeRequest) =
    mock.run("pollBankStatus", request.payoutId) {
        val config = scenarios.get(request.payoutId)
        val attempt = mock.currentAttempt()
        if (config.pollingNeverResolves || attempt <= config.pollsBeforeResolution) {
            throw ApplicationFailure.newFailure(
                "bank still reports the instruction as pending (poll $attempt)",
                "BankStatusPending",
            )
        }
        BankStatusProbeResponse(status = BankStatus.valueOf(config.resolvedStatus))
    }
```

**What you need to understand:**

- "Still pending" is thrown as a retryable failure, so each poll is an attempt and the retry policy sets the schedule. The Workflow makes one call; there's no loop or sleep in the Workflow code.
- However many polls it takes, the history holds one scheduled event and one started event with the final attempt number. A loop in the Workflow would add a timer and an Activity per poll.
- When the policy runs out, the Workflow treats the status as unknown ([lines 545–555](https://github.com/temporal-sa/temporal-demo-payments/blob/3a044a58dffc88a0f1319bfecff581804410e893/backend/kotlin/src/main/kotlin/com/example/payouts/workflow/PayoutWorkflowImpl.kt#L545-L555)), compensates, and records `UNKNOWN_BANK_STATUS`. The code comments flag the real-world risk: if the instruction did settle at the bank, releasing the reservation pays twice. A customer would route that case to a person instead.
- A customer would replace the fake bank with a status call and keep the throw. The pending count and the outcome here come from the scenario controls.

**Docs:** [Polling pattern](https://docs.temporal.io/design-patterns/polling), [Retry Policies](https://docs.temporal.io/encyclopedia/retry-policies)

### Keep the AI steps from blocking the payout

**When to use it:** during beat 4, or when someone asks what happens if the model is slow or down.

**Where it is:** [`…/payouts/workflow/PayoutWorkflowImpl.kt` lines 150–160](https://github.com/temporal-sa/temporal-demo-payments/blob/3a044a58dffc88a0f1319bfecff581804410e893/backend/kotlin/src/main/kotlin/com/example/payouts/workflow/PayoutWorkflowImpl.kt#L150-L160) and [lines 347–351](https://github.com/temporal-sa/temporal-demo-payments/blob/3a044a58dffc88a0f1319bfecff581804410e893/backend/kotlin/src/main/kotlin/com/example/payouts/workflow/PayoutWorkflowImpl.kt#L347-L351) (`code -g backend/kotlin/src/main/kotlin/com/example/payouts/workflow/PayoutWorkflowImpl.kt:150`)

```kotlin
/** One bounded attempt: model failure must not block a human approval. */
private val ai = Workflow.newActivityStub(
    AiBriefActivities::class.java,
    ActivityOptions {
        setStartToCloseTimeout(Duration.ofSeconds(30))
        setScheduleToCloseTimeout(Duration.ofSeconds(30))
        setTaskQueue(TASK_QUEUE)
        setSummary("Draft AI payout brief")
        setRetryOptions { setMaximumAttempts(1) }
    },
)
```

```kotlin
} catch (e: ActivityFailure) {
    log.warn("AI brief unavailable; continuing payout workflow", e)
    AiBrief(summary = "AI brief unavailable. Review the payout facts directly.", unavailable = true,
        investigation = investigation, investigationSkipReason = investigationSkipReason)
}
```

**What you need to understand:**

- The model's choice of lookups (15 seconds), each lookup (3 seconds), and the brief (30 seconds) are separate Activities with one attempt each. Each shows up on the Timeline, and its result is recorded in the history, so a replay doesn't call the model again.
- Any failure lands in the `catch`, and the payout goes on to the approval with "AI brief unavailable". The approval timer starts after this, so a slow model doesn't eat into the approver's time.
- The model only chooses from two read-only lookups, and the Workflow filters its answer to those two names ([lines 311–313](https://github.com/temporal-sa/temporal-demo-payments/blob/3a044a58dffc88a0f1319bfecff581804410e893/backend/kotlin/src/main/kotlin/com/example/payouts/workflow/PayoutWorkflowImpl.kt#L311-L313)). It has no tool that approves, rejects or moves money.
- Here the model is advisory, so one attempt is right. If the model's output were required, a customer would let it retry with a backoff instead.
- The provider is a setting: Ollama with Qwen locally, OpenAI on tmprl-demo.cloud ([`…/payouts/activities/AiBriefActivitiesImpl.kt` lines 116–121](https://github.com/temporal-sa/temporal-demo-payments/blob/3a044a58dffc88a0f1319bfecff581804410e893/backend/kotlin/src/main/kotlin/com/example/payouts/activities/AiBriefActivitiesImpl.kt#L116-L121)).

### Start each payout exactly once

**When to use it:** when someone asks what stops a payout from being paid twice.

**Where it is:** [`…/payouts/api/PayoutController.kt` lines 90–114](https://github.com/temporal-sa/temporal-demo-payments/blob/3a044a58dffc88a0f1319bfecff581804410e893/backend/kotlin/src/main/kotlin/com/example/payouts/api/PayoutController.kt#L90-L114) (`code -g backend/kotlin/src/main/kotlin/com/example/payouts/api/PayoutController.kt:90`)

```kotlin
val stub = client.newWorkflowStub<PayoutWorkflow> {
    setWorkflowId(workflowId)
    setTaskQueue(TASK_QUEUE)
    // REJECT_DUPLICATE, not the default AllowDuplicate, which permits a second payout
    // once the first has closed.
    setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
    setStaticSummary("Payout $payoutId - ${body.scenario}")
    // ...
}

val request = ProcessPayoutRequest(
    // ...
    idempotencyKey = "$payoutId-rail-1",
    // ...
)
val execution = WorkflowClient.start(stub::processPayout, request)
```

**What you need to understand:**

- Temporal never runs two open Workflows with the same ID. `REJECT_DUPLICATE` also refuses a new run when a closed one with that ID is still within the Namespace's retention period. The default would allow it.
- In the demo every click mints a new payout ID, so a duplicate never happens on screen. A customer would use their own payout ID as the Workflow ID, so a retried request from upstream can't start a second payout.
- The idempotency key is made once, before the Workflow starts, and passed to every rail attempt and to the reversal. That's what lets the rail treat retries as one instruction.

**Docs:** [Workflow ID reuse policy](https://docs.temporal.io/workflow-execution/workflowid-runid#workflow-id-reuse-policy)

### Change the Workflow without breaking payouts in flight

**When to use it:** when someone asks how you ship a change while payouts are mid-flight.

**Where it is:** [`…/payouts/workflow/PayoutWorkflowImpl.kt` lines 499–507](https://github.com/temporal-sa/temporal-demo-payments/blob/3a044a58dffc88a0f1319bfecff581804410e893/backend/kotlin/src/main/kotlin/com/example/payouts/workflow/PayoutWorkflowImpl.kt#L499-L507) (`code -g backend/kotlin/src/main/kotlin/com/example/payouts/workflow/PayoutWorkflowImpl.kt:499`)

```kotlin
private fun settlesInline(request: ProcessPayoutRequest): Boolean {
    if (!SettlementThresholds.settlesSynchronously(request.amount.amountMinor)) return false
    val version = Workflow.getVersion(INLINE_SETTLEMENT_CHANGE, Workflow.DEFAULT_VERSION, INLINE_SETTLEMENT_VERSION)
    if (version == Workflow.DEFAULT_VERSION) return false
    Workflow.upsertTypedSearchAttributes(
        TEMPORAL_CHANGE_VERSION.valueSet(listOf("$INLINE_SETTLEMENT_CHANGE-$version")),
    )
    return true
}
```

**What you need to understand:**

- The inline settlement for payouts under $100 was added after payouts were already running. `Workflow.getVersion` records a marker for new executions; a history recorded before the change has no marker, gets `DEFAULT_VERSION`, and keeps the old callback wait.
- The repo upserts `TemporalChangeVersion` by hand so a visibility query can find runs still on the old branch. Java SDK 1.38 can do this itself with the experimental `setEnableUpsertVersionSearchAttributes` option, which is off by default.
- `PayoutWorkflowReplayTest` replays recorded histories from `src/test/resources/histories/` against the current code, which catches a change that would break running payouts before it ships.
- This repo doesn't use Worker Versioning. With Worker Deployments, a pinned Workflow finishes on the code it started on, which removes the need for these branches.

**Docs:** [Java: versioning](https://docs.temporal.io/develop/java/workflows/versioning), [Worker Versioning](https://docs.temporal.io/production-deployment/worker-deployments/worker-versioning)

### What's demo-only

When someone asks what they'd change for their own system, these are the parts to leave out:

- **Every downstream system is a mock.** [`…/payouts/activities/ActivitiesImpl.kt` lines 16–38](https://github.com/temporal-sa/temporal-demo-payments/blob/3a044a58dffc88a0f1319bfecff581804410e893/backend/kotlin/src/main/kotlin/com/example/payouts/activities/ActivitiesImpl.kt#L16-L38): each Activity checks for injected failures, sleeps 1 to 2.5 seconds, and returns a fixed result. Replace the bodies with calls to the ledger, FX service, rail and bank, and the Workflow doesn't change.
- **Failure injection lives in the Activities, never in the Workflow.** [`…/payouts/scenario/ScenarioStore.kt` lines 40–47](https://github.com/temporal-sa/temporal-demo-payments/blob/3a044a58dffc88a0f1319bfecff581804410e893/backend/kotlin/src/main/kotlin/com/example/payouts/scenario/ScenarioStore.kt#L40-L47) and [lines 100–127](https://github.com/temporal-sa/temporal-demo-payments/blob/3a044a58dffc88a0f1319bfecff581804410e893/backend/kotlin/src/main/kotlin/com/example/payouts/scenario/ScenarioStore.kt#L100-L127): the API writes each payout's scenario to a file, and the Activities read it and throw `RailTransient`, `RailRejected` or `BankRejected`. The Workflow sees the same failures a real rail would cause.
- **The customer records are synthetic.** [`…/payouts/activities/CustomerInvestigationActivitiesImpl.kt` lines 8–19](https://github.com/temporal-sa/temporal-demo-payments/blob/3a044a58dffc88a0f1319bfecff581804410e893/backend/kotlin/src/main/kotlin/com/example/payouts/activities/CustomerInvestigationActivitiesImpl.kt#L8-L19): two fixed customers, no fraud service.
- **The random attempt cap** of 7 to 10 ([`…/payouts/workflow/PayoutWorkflowImpl.kt` lines 19–28](https://github.com/temporal-sa/temporal-demo-payments/blob/3a044a58dffc88a0f1319bfecff581804410e893/backend/kotlin/src/main/kotlin/com/example/payouts/workflow/PayoutWorkflowImpl.kt#L19-L28)) is there so executions differ. Use a fixed policy.
- **Workers as child processes of the API.** [`…/payouts/worker/WorkerSupervisor.kt` lines 51–57](https://github.com/temporal-sa/temporal-demo-payments/blob/3a044a58dffc88a0f1319bfecff581804410e893/backend/kotlin/src/main/kotlin/com/example/payouts/worker/WorkerSupervisor.kt#L51-L57): **Stop a worker** sends SIGKILL to a JVM the API started, so the page survives to start a replacement. A customer runs Workers as their own deployment.
- **The load simulator** in `…/payouts/simulation/SimulationRunner.kt` approves, declines and posts bank callbacks on its own. It skips the AI steps.

## Reset and tear down

- **Between scenarios:** nothing to reset. Each press of **Start** makes a new payout ID and its own failure injection.
- **To clear the workflow list:** run `make reset`. It stops everything and deletes the dev server's database and the scenario file. Then `make start` again.
- **To stop:** press **■ STOP** on the **Metrics** tab if the simulation is running, then run `make stop`. It stops the API, the Workers, the dev server, the containers, and Ollama if `make start` started it.
- **On Temporal Cloud:** every payout ends on its own. The approval wait has a 60-second deadline, the bank wait 45 seconds, and polling stops when its retry policy runs out. The load simulator isn't reachable there. `POST /demo-api/reset` clears failure injection only; it doesn't touch Workflows.

## When something goes wrong

| What you see | Likely cause | Fix |
|---|---|---|
| `make start` says "Wrong temporal CLI" | A different CLI is first on `PATH` | Install 1.8.3 (`mise install`) or fix `PATH` order; `make preflight` shows which one it found |
| `make start` says "No JDK 21 found" | No JDK 21 installed | `mise install` or `brew install openjdk@21` |
| `make start` says "Port … is already in use" | A previous run is still up | `make stop`, then `make start` |
| `make start` says Docker Desktop is not responding | Docker isn't running or is stuck | Restart Docker Desktop, then `make stop && make start` |
| The brief says "AI brief unavailable" | Locally, Ollama isn't running or `qwen3.5:9b` isn't pulled. On Cloud, the model call failed or the OpenAI secret is missing | The payout continues either way. Locally, `ollama pull qwen3.5:9b` and restart |
| **Workers** reads **0 / 10 running** and payouts don't move | A Worker was stopped, or failed to start | Press **Start a worker**; check `/tmp/payout-demo-worker-*.log` |
| The bank buttons never appear | The amount is under $100, so the bank answered inline | Use $100 or more |
| No **Approve** button | The USD amount is under $500, so no approval is needed | Use $500 or more |
| The approval payout ends `CANCELLED` | The 60-second deadline passed before **Approve** | Expected; start a new payout and approve sooner |
| The **Live status** card shows **Backend not responding** | No Worker is running to answer the Query, or the API is down | Start a Worker; if the header says "backend unreachable", check `/tmp/payout-demo-backend.log` |
| The Grafana pane is blank | Grafana didn't start, or you're on Cloud, where the **Metrics** tab doesn't exist | Locally, check http://localhost:3000 and `docker compose ps` |

## Keeping this runbook current

The code links point at a fixed commit. When a linked file changes, update its link, line numbers, and snippet together. Before an event, list the linked files that have changed:

```sh
grep -oE 'blob/[0-9a-f]{40}/[^#)]+' RUNBOOK.md | sort -u | while IFS=/ read -r _ sha file; do
  git diff --quiet "$sha" HEAD -- "$file" || echo "changed since ${sha:0:7}: $file"
done
```
