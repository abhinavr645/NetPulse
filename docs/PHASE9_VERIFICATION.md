# Phase 9 verification and stability report

Verified on 2026-11-17: Windows 11 Home Single Language (10.0.26300),
Temurin JDK 25.0.5+8, Maven 3.10.1, interactive Swing desktop.
All JUnit database tests use temporary paths; they do not modify `data/netpulse.db`.
Network counter fixtures and historical test rows are labelled test data. Live
adapter and TCP results below come from actual Windows counters and socket bytes.

## Confirmed bugs and fixes

| Problem found by inspection | Fix | Regression verification |
|---|---|---|
| Stop/shutdown could miss the final real snapshot published between 200 ms collector polls. The monitor's stopped snapshot removes its rates. | The monitor returns its final published reading when stopping. The controller records it before flushing. Session initialization and collection share a short lock, with no PowerShell/JDBC work inside it. | A controlled final reading that is never exposed to the periodic collector is persisted on Stop and window shutdown. Consecutive sessions have exact totals without duplicates. |
| When the bounded storage queue rejected a flush, `beginSession()` retained pending byte deltas while resetting the previous session baseline. The next adapter could inherit those bytes. | On session change, reject and explicitly report an unsavable prior batch rather than combining it with a new session. | Fill all 256 queue slots while holding the worker, change adapters, then drain; each adapter retains only its own successfully saved deltas. |
| A clear-history modal callback could resume after its owner began shutdown and submit work to a stopped executor. | Recheck closing state after confirmation returns. Serialize background shutdown against the JVM cleanup hook. | Close the window with confirmation open, then return OK; no rejected task or uncaught EDT exception. |
| A later successful storage read replaced an earlier failure notice without explaining that failed writes were still lost. | Recovered status retains an earlier-failure notice. | Locked-path/SQLite checks verify errors, recovery and retained records; failed writes are not claimed as recovered. |

No TCP protocol, throughput calculation, graph drawing or alert detection
rewrite was needed. Existing short/oversized-payload, disconnect, timeout,
counter-reset, unavailable-reading and threshold-suppression tests continue to
exercise their original behavior. An unreachable mouse-action fallback and
its unused import were removed from the desktop test helper.

## Database growth

At five-second intervals, continuous detailed storage can produce approximately
17,280 rows per day. PowerShell overhead usually reduces this; session-boundary
flushes can add rows. A bounded viewer does not bound disk storage.

Retention is therefore configurable but **disabled by default** to avoid
unexpected deletion of approved Phase 8 data. Explicitly enable it with
`-Dnetpulse.history.retention.enabled=true`. The period defaults to 30 days;
`-Dnetpulse.history.retention.days=N` accepts 1 through 36500. Invalid values
disable deletion and print an explanation.

Only `network_history` rows strictly older than the cutoff are removed, at
startup and on the next storage operation after each hour. Alerts, daily
summaries and settings are preserved. Cleanup runs on the existing database
worker. No automatic `VACUUM` is performed; SQLite can reuse freed pages without
immediately shrinking the database file. Tests verify opt-in configuration,
invalid configuration, exact cutoff preservation and retained alerts/summaries.

## Commands

```shell
mvn clean compile
mvn test
mvn test "-Dtest=DashboardDesktopTest,TrafficAlertsDesktopTest,PersistenceDesktopTest,StabilityDesktopTest" "-Dnetpulse.gui.smoke=true"
```

The opt-in suite opens real windows, reads actual Wi-Fi counters, and owns its
test listeners on port 5001. It refuses to take over an existing server. One
test starts `BandwidthServer` in a separate Java process; stdin is a test-only
signal to stop that process gracefully. Production CLI commands remain unchanged.

## Workflow and edge-case coverage

| Workflow | Verification |
|---|---|
| A — Startup | Main GUI entry point, automatic temporary SQLite creation, adapter discovery, saved settings and newest alert restoration. Startup path failure uses defaults and visible errors while keeping the EDT responsive. |
| B — Monitoring | Real Wi-Fi selection, receive/send speeds, graphs, periodic SQLite rows, observed daily totals, Stop, restart and final pending flush. Deterministic tests cover an otherwise uncollected final reading. |
| C — Alerts | Tiny explicitly configured thresholds trigger real adapter alerts. Suppression, settings validation, disabling, persisted alerts and restoration after restart are checked. Valid idle intervals and INFO alerts are covered by controlled counters when the real adapter remains busy. |
| D — TCP | Missing-server error, invalid GUI sizes, upload/download with a separate server JVM, exact bytes and throughput formula. Existing tests cover malformed requests, short/oversized payloads, disconnects, read timeouts, concurrent clients and server shutdown. |
| E — History | Empty history; 1,000 labelled fixture readings; 24-hour/7-day/all-time filters; respective 100/400/500 displayed readings; saved alerts and daily summaries; refresh; cancellation and confirmation of deletion; restart. Locked deletion preserves the in-memory and persisted alerts. |
| F — Shutdown | Closing with adapter monitoring active; closing during or shortly after a real large TCP transfer; deterministic cancellation of a blocked TCP read; PowerShell/collector/database worker cleanup; closed file can be renamed; separate server remains alive until explicitly stopped; port 5001 reusable afterward. |
| Failure cases | Unsupported loopback adapter, PowerShell command failure, injected counter-source IO failures, malformed output, resets and invalid intervals; unavailable/locked SQLite, queue saturation, invalid settings and transfer sizes. Missing PowerShell uses the same caught process-start IOException path; the installed Windows PowerShell executable is not removed to test it. |
| Layout | Real graphs resize; dashboard tested at 860×600/640 and history window at 860×420, with scrolling. No UI redesign. |

## Actual execution results

| Command/check | Result |
|---|---|
| Baseline `mvn test` before changes | 142 passed, 4 opt-in desktop cases skipped; no failures/errors |
| Final `mvn clean compile` | BUILD SUCCESS; 28 production source files, Java release 25 |
| Final `mvn test` | BUILD SUCCESS; 158 cases discovered, 151 passed, 7 opt-in desktop cases skipped, 0 failures/errors |
| Explicit run of all four desktop test classes | BUILD SUCCESS; all 7 desktop cases passed, 0 skipped/failures/errors |
| Combined latest Surefire XML reports after both runs | 158 cases, 0 failures, 0 errors, 0 skipped |
| Final process/port inspection after desktop and CLI checks | 0 NetPulse windows, 0 active adapter-query PowerShell processes, 0 listeners on port 5001 |
| Git whitespace check | `git diff --check` passed; Git also reported its normal LF/CRLF conversion notices |

The standard test suite intentionally prints errors from malformed-transfer and
invalid-retention fixtures. These assertions passed; the messages are not
unhandled application failures. No existing assertions were removed or
weakened to obtain a successful result. The additional targeted regression
and stability runs also passed before the final complete runs.

Actual final GUI observations:

- Wi-Fi interface index 6 produced real speeds; one dashboard sample showed
  receive **0.001287 Mbps** and send **0.001671 Mbps**, with **476/618** observed
  receive/send bytes at that point. An HTTP request to the Java API reference
  returned status 200 and generated real adapter activity. Both graphs collected
  distinct readings, froze on stop, and resized successfully.
- The persistence restart check saved **2 network readings, 2 alerts**, and
  daily observed totals of **41,177 received / 162,623 sent bytes**. Saved
  thresholds of **0.000001 Mbps** in each direction, **7 seconds** inactivity,
  and **enabled=true** were restored. These tiny thresholds were test settings,
  not changed production defaults. History tabs, cancellation/confirmation of
  clearing, and closed-file cleanup passed.
- Actual threshold alerts included CRITICAL receive **0.0359623 Mbps** and
  send **0.00768290 Mbps** above the explicitly configured tiny thresholds.
  No live inactivity alert was observed because the Wi-Fi adapter remained
  active during the check; deterministic analyzer/rendering tests cover INFO
  inactivity. No fake idle reading was inserted.
- The separate server JVM used PID **15908** in the final run. GUI upload
  transferred exactly **10,000,000 bytes** in **0.018061 s**, reported
  **4429.335 Mbps**; download transferred exactly **10,000,000 bytes** in
  **0.038143 s**, reported **2097.370 Mbps**. Throughput was checked against
  the exact bytes and unrounded nanosecond duration. Displayed durations here
  are rounded. A real **1,000,000,000-byte download request** was started before
  closing the monitoring dashboard; cancellation/cleanup and independent server
  ownership passed. Server closure then released port 5001.
- History fixtures produced exactly **100 / 400 / 500** visible network rows
  for 24 hours / 7 days / all time, respectively, from **1,000** stored rows.
  The all-time display was capped without deleting the underlying rows.
  A real SQLite exclusive lock caused visible errors without blocking the EDT.
  A failed confirmed deletion preserved all **3** fixture alerts. Releasing
  the lock restored refresh operation.

Existing CLI commands were also run separately after the desktop suite:

```shell
mvn exec:java "-Dexec.args=server"
mvn exec:java "-Dexec.args=client UPLOAD 10485760"
mvn exec:java "-Dexec.args=client DOWNLOAD 10485760"
mvn exec:java "-Dexec.args=monitor 6 2"
```

The upload returned exactly **10,485,760 bytes**, **0.005100 s**,
**16448.574 Mbps** and **100%** progress. Download returned exactly
**10,485,760 bytes**, **0.035424 s**, **2368.064 Mbps** and **100%** progress.
Both Maven client commands completed successfully. These are localhost payload
measurements and do not represent Wi-Fi or Internet capacity. The server was
stopped with Ctrl+C and Windows' batch-termination confirmation; its intentional
interruption exited the launcher with code 1, and the final port check confirmed
no listener remained.

The monitoring CLI returned a baseline and one VALID real Wi-Fi reading over
**2.980 seconds**: **525 received / 528 sent bytes**, receive **0.001410 Mbps**,
send **0.001418 Mbps**. It exited normally with BUILD SUCCESS.

Screenshots from the real desktop suite:

- [Dashboard](../target/netpulse-dashboard.png)
- [Graphs](../target/netpulse-graphs.png)
- [Alerts](../target/netpulse-alerts.png)
- [Saved history after restart](../target/netpulse-history.png)

## Files changed

Production:

- `src/main/java/com/netpulse/monitoring/NetworkAdapterMonitor.java`
- `src/main/java/com/netpulse/ui/DashboardController.java`
- `src/main/java/com/netpulse/database/PersistenceService.java`
- `src/main/java/com/netpulse/database/NetworkHistoryRepository.java`

Tests:

- `src/test/java/com/netpulse/monitoring/NetworkAdapterMonitorTest.java`
- `src/test/java/com/netpulse/ui/DashboardControllerTest.java`
- `src/test/java/com/netpulse/ui/DashboardDesktopTest.java`
- `src/test/java/com/netpulse/database/PersistenceServiceTest.java`
- `src/test/java/com/netpulse/ui/StabilityDesktopTest.java` (new)

Documentation/configuration:

- `README.md`
- `pom.xml` (corrected a stale test-dependency comment; no dependency change)
- `docs/PHASE9_VERIFICATION.md` (new)

Build outputs, Surefire reports and desktop screenshots under `target/` are
regenerated by verification. Some `target/` files were already tracked before
Phase 9; this phase does not change the Git index or commit generated files.

## Remaining limits

- Windows NetAdapter availability, driver support and permissions determine
  which adapters can be monitored. Real sampling has PowerShell startup overhead.
- Localhost payload throughput is separate from adapter traffic and Internet
  capacity. No encryption, authentication, LAN configuration or packet-loss
  measurement is added in this phase.
- GUI and lifecycle testing covers this interactive Windows environment, not
  every display scale, driver, Windows edition or machine.
- Storage errors and forced termination can lose records. A full queue is
  reported, not allowed to grow indefinitely. No automatic replay is added.
- Daily summaries cover valid observed intervals; missing intervals and time
  while the application is closed are excluded.
- Retention is opt-in. Saved alerts and daily summaries are not automatically
  deleted. The viewer shows at most 500 rows per tab, not an export of all data.
- No installer, packaged distribution, bundled runtime or clean-machine install
  test has been produced. These belong to Phase 10 after approval.

## Packaging readiness

The tested application is ready to proceed to **Phase 10 packaging work** on
the stated Windows/JDK environment, subject to the limits above. This is not
a claim that an installer or bundled runtime has already been validated.
Packaging and clean-machine installation verification have not started and
require the user's Phase 10 approval.
