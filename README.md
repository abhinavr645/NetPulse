# NetPulse - Network Bandwidth Monitoring and Analysis System

NetPulse is a college Computer Networks project with a Swing dashboard for
network adapter monitoring, real-time bandwidth graphs, TCP bandwidth tests,
and monitoring history stored in SQLite.

NetPulse includes SQLite monitoring history, daily observed-traffic summaries,
persistent alerts and saved alert settings, plus a dashboard history viewer.
Phase 10 adds a self-contained Windows release, local server controls and
college submission documentation. Adapter statistics and TCP payload
measurements remain separate.

## Windows release and submission documents

The distributable is `release/NetPulse-1.0.0-windows-x64.zip`. Extract the whole
ZIP, open its `NetPulse` folder and use **NetPulse.cmd**. The batch launcher
uses the bundled, vendor-signed `runtime/bin/javaw.exe`, opens the GUI by default
and requires no separately installed Java or Maven. Keep `app` and `runtime`
beside the launchers. The image is built with JDK 25 `jpackage` and is a portable
application folder, not an MSI/setup installer.

`NetPulse.exe` is also generated with the project icon. Windows Application
Control on the verification machine blocks this unsigned generated EXE before
Java starts. It is therefore not claimed as verified. No security policy was
disabled; the signed-runtime launcher is supplied as the runnable release path.
See the exact results and limitations in
[Phase 10 release verification](docs/PHASE10_RELEASE_VERIFICATION.md).

- [College project report](docs/NetPulse_Project_Report.md) — editable student,
  USN, college and guide fields; actual implementation and references.
- [Quick start and demonstration](docs/QUICK_START.md).
- [Four maintained Mermaid architecture diagrams](docs/ARCHITECTURE.md).
- [Verified screenshots](docs/screenshots/) and [dependency notices](docs/THIRD_PARTY_NOTICES.md).

Packaged SQLite data is stored at `%LOCALAPPDATA%\NetPulse\netpulse.db`, with a
user-home local-data fallback, rather than in the installation directory.
Developer commands below retain `data/netpulse.db`. Data is not automatically
migrated between developer and packaged locations.

To reproduce the release on Windows with JDK 25 and Maven:

```powershell
mvn clean compile
mvn test
mvn test "-Dtest=DashboardDesktopTest,TrafficAlertsDesktopTest,PersistenceDesktopTest,StabilityDesktopTest" "-Dnetpulse.gui.smoke=true"
.\packaging\Build-WindowsRelease.ps1
.\packaging\Verify-WindowsRelease.ps1 -Launcher Runtime
```

The build script runs Maven package/tests, stages only runtime JARs, invokes
`jpackage`, preserves licenses and builds a ZIP plus SHA-256 checksum. It replaces
only its generated versioned release directory and staging directory. Generated
releases are excluded from Git. The verification script uses isolated
`LOCALAPPDATA`, clears Java/Maven from the child's PATH and removes JAVA_HOME;
its external test agent and evidence remain under `target/`, outside the release.
`-Launcher Native` checks the generated EXE where policy permits it. Running
the release on a separate machine with no JDK installed has not been verified.

## Technologies

- JDK 25
- Maven
- Java Swing for the desktop dashboard
- TCP socket programming for upload and download tests
- SQLite with JDBC for local history storage
- JUnit 5 for automated tests
- Visual Studio Code
- Windows 11
- Windows PowerShell with the NetAdapter module for adapter statistics

## Project structure

```text
src/main/java/com/netpulse/
    Main.java
    DesktopLauncher.java
    network/
        BandwidthServer.java
        BandwidthClient.java
        TransferProtocol.java
    monitoring/
        BandwidthMeasurement.java
        NetworkAdapterMonitor.java
        WindowsAdapterStatistics.java
        TrafficAnalyzer.java
    ui/
        DashboardFrame.java
        DashboardPanel.java
        DashboardController.java
        DashboardStyle.java
        BandwidthGraphPanel.java
        AlertPanel.java
        HistoryDialog.java
    model/
        BandwidthSnapshot.java
        BandwidthGraphHistory.java
        NetworkAdapter.java
        NetworkStatisticsSnapshot.java
        AlertSettings.java
        TrafficAlert.java
        TransferResult.java
    database/
        DatabaseManager.java
        NetworkHistoryRepository.java
        AlertRepository.java
        AlertSettingsRepository.java
        TrafficSummaryRepository.java
        PersistenceService.java
    alerts/
```

The remaining empty `alerts` package is reserved for later phases. Automated tests are
under `src/test/java/com/netpulse/`.

## Compile and run

1. Install JDK 25 and Maven on Windows 11.
2. Set `JAVA_HOME` to your JDK 25 installation directory. Add
   `%JAVA_HOME%\bin` and Maven's `bin` directory to your Windows `Path`.
3. Restart your terminal so it picks up the environment changes.
4. Open the project folder in VS Code, then open a terminal in the project root,
   where `pom.xml` is located.

Check your setup:

```shell
java -version
mvn -version
```

Both commands should report Java 25. Maven compiles the project with
`maven.compiler.release` set to `25` in `pom.xml`.

Compile the application:

```shell
mvn clean compile
```

Run the application after compilation:

```shell
mvn exec:java
```

Expected application output:

```text
NetPulse - Network Bandwidth Monitoring and Analysis System
Application Started Successfully
```

Maven may also print build and download messages. The first build requires
Internet access to download dependencies and plugins.

## Open the Swing dashboard

After compiling, launch the desktop application:

```shell
mvn exec:java "-Dexec.args=gui"
```

The existing no-argument startup command and all `server`, `client`, `adapters`,
and `monitor` commands still work.

The dashboard provides:

- A dark theme, title/subtitle, and Running/Stopped monitoring indicator.
- An adapter selector, adapter name/status, and adapter-list refresh.
- Download/upload cards in Mbps, with MB/s details, plus session receive/send
  totals and the underlying Windows cumulative counters.
- Start Monitoring, Stop Monitoring, and Refresh controls. Stop monitoring
  before changing adapters. Refresh displays the latest completed sample;
  it does not launch an extra PowerShell query.
- Independent TCP upload/download tests with size input in decimal MB and
  final throughput, elapsed time, and exact transferred-byte count.
- Inline error messages for unavailable adapters, PowerShell failures, invalid
  transfer sizes, missing servers, and connection timeouts.

To use the TCP section, click **Start local server** and confirm the displayed
Running status. **Stop local server** closes only the server started by that
dashboard. An occupied port produces a clear error. An alternative is to start
the existing server in a second terminal:

```shell
mvn exec:java "-Dexec.args=server"
```

Then select Upload or Download in the dashboard and click Start Test. The
default GUI size of `10` MB means exactly 10,000,000 bytes. Fractional MB values
are allowed when they represent whole bytes; the maximum is 1073.741824 MB
(1 GiB). The GUI starts a server only when requested. If none is listening on
`127.0.0.1:5001`, it reports a connection error and remains responsive.

Dashboard responsibilities are separated:

- `DashboardPanel` creates components and renders existing immutable models.
- `DashboardController` calls `NetworkAdapterMonitor.listAdapters()`, creates
  the selected monitor, and invokes the existing `BandwidthClient.transfer()`.
  Local server controls reuse `BandwidthServer` on background tasks; its CLI
  command remains available. The TCP protocol is unchanged.
- A 500 ms Swing timer reads the monitor's thread-safe snapshot getter. Actual
  counter updates still follow the existing PowerShell sampling interval;
  the GUI does not invent intermediate readings.
- Background executor tasks handle adapter discovery, monitor lifecycle calls,
  and TCP transfers. `SwingUtilities.invokeLater` delivers UI updates to the
  Event Dispatch Thread. No socket or PowerShell work runs on that thread.
- JDK 25 virtual threads allow cancellation to interrupt the existing socket
  I/O without changing the TCP engine. Window closing stops the timer, cancels
  background work, stops monitoring/PowerShell, and waits for cleanup on a
  separate thread before disposing the window. A JVM shutdown hook also cleans
  up owned background work, including a dashboard-owned local server. An
  externally started CLI server remains separate.

The window can be resized and scrolled on smaller screens. Unavailable speeds
display `--`; a measured zero displays `0.000000`. Totals describe valid observed
traffic in the current monitoring session. TCP results are shown in their own
section and are never substituted for adapter readings.

Socket cancellation reference:
[JDK 25 Socket API](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/net/Socket.html).

## Real-time bandwidth graphs

The dashboard includes two custom-painted Swing panels: Download Speed (Mbps)
and Upload Speed (Mbps). They use the existing `NetworkStatisticsSnapshot`
receive/send rates, not the localhost TCP test results. No charting dependency
or simulated values are used.

`BandwidthGraphHistory` holds a shared pair of rates for each observed reading.
A small background collector polls only the monitor's cached snapshot every
200 ms. It forwards each new snapshot object to the EDT once. Repeated display
refreshes never add the same snapshot again; a new reading with the same speed
is still a valid new sample. Graph collection is independent of the 500 ms
display/repaint timer, and it never launches additional PowerShell commands.

`BandwidthGraphPanel` uses `Graphics2D` with antialiasing, rounded line ends,
sample markers, adaptive Mbps axes, and a time axis from 60 seconds ago to Now.
Straight segments join actual points; no smoothing algorithm changes the
measured values. Both graph panels resize with the viewport. Vertical scrolling
keeps the adapter controls, cards, graphs, and TCP section accessible on smaller
laptop screens.

The history uses an `ArrayDeque` with two bounds:

- Points older than 60 seconds are removed when new readings arrive or the
  running graph is painted. The visible time window continues to scroll even
  while waiting for a reading.
- At most 600 samples are retained, even if many readings arrive with the same
  timestamp. Normal PowerShell sampling produces far fewer points per minute.

Points use monotonic elapsed time from `System.nanoTime()`. A point's time is
when its snapshot reaches the EDT, rather than an exact Windows counter-query
timestamp. Collection and UI scheduling can therefore shift it slightly.

Valid zero speeds are plotted at zero. Baseline, unavailable, reset, and invalid
interval readings are stored as gaps; the line breaks rather than drawing fake
zeros or connecting across missing data. Before starting, the graphs show an
empty-state message. While waiting for a valid reading, they show a waiting or
unavailable message. The graph does not extend the last value to invent data
between the latest sample and Now.

Stop Monitoring freezes both the samples and their time axis. The final graph
remains readable until a new session starts or the adapter selection changes.
Starting a new session clears old graph data. Closing the window also stops
the graph collector, alongside the existing monitoring and TCP cleanup.

Graphs show interval-average adapter throughput, not instantaneous packet
traffic or available bandwidth capacity. PowerShell's existing sampling delay
limits detail for short bursts. During the first minute, the portion before the
session began is intentionally empty.

## Traffic analysis and alerts

Scroll to **Traffic analysis & alerts** in the dashboard. This section analyzes
the selected adapter's actual statistics. TCP test results are not used as
adapter traffic and never trigger these alerts directly.

Defaults, defined in `AlertSettings.defaults()`:

| Setting | Default |
|---|---|
| Alerts | Disabled |
| Download threshold | 100 Mbps |
| Upload threshold | 50 Mbps |
| Inactivity duration | 30 seconds |
| Recent history limit | 100 alerts |

The speed defaults are illustrative warning limits for a college demonstration,
not estimates of available bandwidth or Internet connection capacity. Change
them to values appropriate for the selected adapter and demonstration.

Edit Download Mbps, Upload Mbps, and Idle seconds, then click **Apply settings**.
Thresholds must be finite numbers greater than zero; the duration must be a
whole number from 1 to 86400 seconds. **Enable alerts** applies valid current
settings and enables detection. Unchecking it disables detection even if an
unapplied text field contains invalid input. Invalid settings leave the previous
configuration active and display an explanatory message.

### Detection and severity

`TrafficAnalyzer` is a reusable, synchronized class in `monitoring`. The existing
background snapshot collector invokes it once for each new immutable
`NetworkStatisticsSnapshot`. No analysis runs on the EDT, and no extra
PowerShell commands or notification threads are created. Immutable alert/history
copies are sent to Swing on the EDT.

| Condition | Severity | Behavior |
|---|---|---|
| Speed strictly exceeds its configured threshold | WARNING, amber | One alert for that direction |
| Speed is at least twice its threshold when first exceeded | CRITICAL, red | One higher-severity threshold alert |
| Both directions remain exactly zero for the configured observed duration | INFO, blue | Informational inactivity alert |

Download and upload conditions are independent. Severity is chosen when a
threshold condition first occurs. A continuously active condition is suppressed,
including later increases in speed; it does not generate an alert every sample.
A valid reading at or below the threshold clears that direction's condition,
allowing another alert if it exceeds the threshold again.

Inactivity accumulates consecutive valid zero-traffic sampling intervals. Those
intervals are measured by the adapter monitor with `System.nanoTime()`, not by
UI timer ticks. Traffic resuming in either direction resets the inactivity timer
and rearms its alert. A long zero-traffic interval can satisfy a short configured
duration in one reading. An idle adapter can be normal: the INFO description
explicitly says that idle traffic does not imply Internet disconnection.

Missing speeds, nonpositive intervals, adapters not Up, baseline/reset states,
and failed readings do not generate alerts. They reset inactivity accumulation
so unknown time is not treated as observed idle time. They do not prove an
already active threshold condition has cleared; a valid below-limit reading is
required. Duplicate references to the same snapshot are ignored.

Changing settings or starting a new monitoring session resets detection state
for the next fresh reading. Existing history remains. Stopping monitoring stops
analysis, and closing the application shuts down its existing collector.

### Alert history and limitations

The table shows local timestamp, severity, type, and description, with newest
alerts first. Hover over a row to read its full description. Each `TrafficAlert`
is immutable and stores its timestamp as an `Instant`. Only the latest 100 alerts
are retained in memory and restored from SQLite at startup. **Clear history**
asks for confirmation before deleting both the displayed and all persisted
alerts. Cancelling leaves them unchanged. Successful clearing does not rearm
active conditions, so clearing alone cannot cause repeated alerts.

History and configuration are persisted locally. There are no email, SMS,
Telegram, or external notifications. Alerts describe sampled interval-average adapter
traffic, including other applications. PowerShell sampling limits reaction time
and can miss short bursts or threshold clear/re-cross events between readings.
Inactivity is an observation of valid zero-traffic intervals, not an Internet
connectivity test.

## SQLite history and settings

Source/developer NetPulse automatically creates `data/netpulse.db` relative to
the working directory, normally the project root. The packaged launcher uses
`%LOCALAPPDATA%\NetPulse\netpulse.db`, falling back to the user's
`AppData/Local/NetPulse` directory if the environment variable is missing.
An explicit `netpulse.database.path` test/developer override takes precedence.
Run source commands from the project folder to keep using the same developer
database. `.gitignore` excludes `data/`, `release/` and SQLite sidecar files.
No additional dependency is required: the existing SQLite JDBC driver is used.
The checked-in `.mvn/jvm.config` and Surefire configuration enable its native
access on JDK 25. If launching Java directly, add
`--enable-native-access=ALL-UNNAMED` to the JVM command.

### Schema

| Table | Columns |
|---|---|
| `network_history` | `id INTEGER PRIMARY KEY AUTOINCREMENT`, `adapter_name TEXT`, `timestamp INTEGER`, `download_mbps REAL`, `upload_mbps REAL`, `received_bytes INTEGER`, `sent_bytes INTEGER` |
| `traffic_summary` | `id INTEGER PRIMARY KEY AUTOINCREMENT`, `adapter_name TEXT`, `date TEXT`, `total_received_bytes INTEGER`, `total_sent_bytes INTEGER` |
| `alerts` | `id INTEGER PRIMARY KEY AUTOINCREMENT`, `timestamp INTEGER`, `alert_type TEXT`, `severity TEXT`, `description TEXT` |
| `alert_settings` | `id INTEGER PRIMARY KEY` (always 1), `download_threshold REAL`, `upload_threshold REAL`, `inactivity_seconds INTEGER`, `enabled INTEGER` (0 or 1) |

All non-ID columns are required. Timestamps use UTC epoch milliseconds;
the viewer formats them in local time. Summary dates use local `yyyy-MM-dd`.
Unique constraints prevent duplicate adapter/timestamp readings, duplicate
alerts, and multiple summary rows for one adapter/date. Time indexes support
recent-history queries. Counter and threshold checks reject invalid values.

### Saving records

`PersistenceService` owns one database worker and one reusable JDBC connection.
Its bounded queue holds at most 256 pending operations. Neither the Swing EDT
nor the monitoring collector waits for SQLite. Prepared statements and
try-with-resources close statements and result sets; the connection closes
after queued work drains on shutdown.

- Each fresh, valid, Up-adapter snapshot reaches persistence through the
  existing background collector. Repeated callbacks for the same snapshot
  are ignored. Baselines, unavailable readings, counter resets, and invalid
  intervals are not stored as zero-speed readings.
- The first valid reading is saved immediately, then at most once every five
  seconds during continuous monitoring. The next actual sample after that
  interval is used; the existing PowerShell timing often makes this about
  six seconds. Stopping, changing sessions, a day change, and shutdown flush
  the final pending reading, which can be less than five seconds later.
  Stop/shutdown also captures the monitor's final published reading if it is
  newer than the collector's last poll; identity deduplication prevents recounting.
- Network history stores interval-average Mbps and the actual cumulative
  Windows receive/send counters. These counters include traffic before NetPulse
  started and must not be summed to obtain session traffic.
- Daily summaries accumulate changes in the monitor's session totals from all
  collected valid snapshots, including those between stored history rows.
  They represent observed traffic while NetPulse monitors the adapter, not
  full-day traffic while the application is closed. Intervals crossing midnight
  belong to the date when the reading is observed. Counter resets and unknown
  intervals contribute no fabricated traffic. History and summary updates
  commit together in one transaction.
- New analyzer alerts are queued as soon as they are generated. Persistent
  alerts are independent of the 100-alert in-memory display limit. Startup
  restores the newest 100 alerts without generating them again.
- **Apply settings** and the **Enable alerts** checkbox save the active
  thresholds, inactivity duration and enabled state. Startup loads them before
  enabling monitoring controls. A new database uses the defaults above.

### Viewing and clearing history

Scroll below the alert section and click **View saved history**.
The nonmodal window contains **Network readings**, **Saved alerts**, and
**Daily totals** tabs. Choose **Last 24 hours**, **Last 7 days**, or **All time**,
then use **Refresh history** to read the latest committed records. Each tab
shows at most 500 newest rows. Daily totals show whole selected calendar dates,
including any part of the starting date outside a rolling 24-hour range.

**Clear history** in the alert controls deletes all persisted alerts, including
older ones outside the 100-row display, only after confirmation. Database
failure leaves the displayed alert history intact. Monitoring history, daily
summaries and settings are retained. Refresh an already-open history window
after clearing to update its saved-alert tab.

### Storage errors and limits

Storage status appears in the dashboard. A missing/unwritable directory,
locked database, or failed query reports an error while live monitoring,
graphs and TCP tests remain usable. SQLite lock waits are limited to one
second per operation. Later operations retry initialization after a startup
failure; **Refresh history** also retries a failed history query.

Failed SQL writes are reported and are not replayed indefinitely. Prolonged
storage errors, a full queue, forced process termination, or an abrupt machine
shutdown can leave gaps. The normal shutdown drains queued writes; after an
eight-second wait it cancels pending work rather than holding the window open
indefinitely. A session change cannot attribute an unsaved previous session's
bytes to the next adapter. Queue overflow reports the lost pending reading
instead. After recovery the storage status retains a notice about earlier
failures, since a successful refresh cannot restore a failed write.
There is no cloud storage, export, account system or reporting engine.

### Optional network history retention

Continuous five-second storage can produce about 17,280 detailed rows per day
before accounting for PowerShell timing and session-boundary flushes. To
preserve existing history, automatic deletion is **disabled by default**.
An optional retention policy uses a **30-day period** when enabled:

```shell
mvn exec:java "-Dexec.args=gui" "-Dnetpulse.history.retention.enabled=true"
```

To choose a different period, add `"-Dnetpulse.history.retention.days=60"`.
Allowed values are whole numbers from 1 to 36500. An invalid value disables
deletion and prints an explanation. These launch settings must be supplied
again on future launches; they are not alert settings stored in SQLite.
Storage status shows whether retention is off or the configured number of days.

When explicitly enabled, cleanup runs on the database worker at startup and
on the next database operation after each hour. It deletes only
`network_history` rows whose UTC timestamp is strictly older than the configured
period. Alerts, daily summaries and alert settings remain unchanged. Disable
it by omitting the enabled property or setting it to `false`. Deleted readings
cannot be recovered from NetPulse. SQLite normally reuses freed pages; the
database file may not shrink immediately. No automatic `VACUUM` blocks the UI.

## Run TCP transfers

After compiling, open two terminals in the project root.

In the first terminal, start the server:

```shell
mvn exec:java "-Dexec.args=server"
```

The server listens only on `127.0.0.1:5001`. Leave this terminal running.

In the second terminal, run an upload, then a download:

```shell
mvn exec:java "-Dexec.args=client UPLOAD 10485760"
mvn exec:java "-Dexec.args=client DOWNLOAD 10485760"
```

Replace the final number to configure the payload size in bytes. Sizes must be
between 1 and 1,073,741,824 bytes (1 GiB). `10485760` bytes is exactly 10 MiB,
or approximately 10.49 decimal MB. Modes are case-insensitive.

The client prints the mode, confirmed payload byte count, elapsed seconds,
average/current/peak throughput, and progress. Rates use decimal units: 1 MB is
1,000,000 bytes and 1 Mbps is 1,000,000 bits per second.

Press Ctrl+C in the server terminal to stop it and close its client sockets.
If port 5001 is occupied, stop the process using it before starting NetPulse.

## How communication works

Each connection handles one request using `DataInputStream` and
`DataOutputStream`:

1. The client sends a UTF mode (`UPLOAD` or `DOWNLOAD`) and a 64-bit byte count.
2. The server validates both values and responds with UTF `READY`, or
   `ERROR: ...` for an invalid request.
3. The sender transfers exactly the requested payload using a 64 KiB buffer,
   then half-closes its socket output to signal the end of the payload.
4. For upload, the server verifies the count and end of stream, then sends UTF
   `COMPLETE` and a 64-bit received-byte count. For download, the client verifies
   the payload and end of stream, then sends a 64-bit received-byte count.
5. The connection closes. Short or oversized payloads are errors.

The test payload contains zero-filled bytes, which are actually sent through
TCP sockets. Results are measured, not simulated. `System.nanoTime()` measures
client-side elapsed time after `READY`, including payload completion and the
confirmation exchange; connection setup and request validation are excluded.
Protocol bytes are excluded from payload totals.

## Measurement engine

`BandwidthMeasurement` in the `monitoring` package tracks one transfer. Its
methods are synchronized, so a transfer thread can record bytes while another
thread reads statistics. `BandwidthSnapshot` is a Java record: an immutable data
object containing a consistent view of all counters and rates.

The engine supports `start()`, `recordBytes(count)`, `getSnapshot()`, and
`finish()`. Production timing uses `System.nanoTime()`. Tests inject a
controllable clock rather than relying on sleeps or real network speeds.

Calculations:

```text
current bytes/second = bytes since the previous sample / seconds since that sample
average bytes/second = total payload bytes / total elapsed seconds
peak bytes/second    = highest sampled current rate
MB/s                 = bytes/second / 1,000,000
Mbps                 = MB/s * 8
progress             = min(100, total payload bytes / expected bytes * 100)
```

Current and peak rates refresh when a byte update or snapshot request reaches
at least 100 ms since the previous sample. `finish()` also measures a shorter
final interval. No timer thread is required for the engine: callers poll
`getSnapshot()` when they need an update. During idle periods, polling advances
elapsed time and eventually samples a zero current rate. Average throughput
uses total bytes and time, not the arithmetic mean of sampled rates.

An empty measurement has zero rates; a known expected size of zero means 100%
progress because there is no payload to transfer. An unknown expected size,
created with `new BandwidthMeasurement()`, has no percentage
(`OptionalDouble.empty()`). Negative expected sizes and byte updates are
rejected. Zero elapsed time produces zero rates, negative elapsed inputs are
rejected, and backward clock readings cannot reduce elapsed time. Finishing is
idempotent and freezes the final statistics. A new engine is needed per transfer.

To read updates independently of the console, create an engine and pass it to
the client overload from a background task:

```java
BandwidthMeasurement measurement = new BandwidthMeasurement(10_485_760);

try (var worker = Executors.newSingleThreadExecutor()) {
    var transfer = worker.submit(() -> client.transfer("UPLOAD", 10_485_760, measurement));
    // Poll this as needed while the background transfer runs.
    BandwidthSnapshot update = measurement.getSnapshot();
    TransferResult result = transfer.get();
}
```

`TransferResult.getMeasurement()` exposes the final snapshot. Existing
`getThroughputMbps()` and `getMegabytesPerSecond()` methods still return average
rates, preserving the Phase 2 API. The TCP command-line size range remains
1 byte to 1 GiB; zero-byte support belongs to the reusable engine.

Only successful payload reads/writes update counters. The wire protocol,
exact-count checks, and server acknowledgments remain unchanged. Live upload
counts represent bytes written to the socket; receiver confirmation is required
before returning a successful result. Downloads count payload bytes read.
Progress reaching 100% alone does not mean the final confirmation has succeeded.
Failed transfers throw an exception and freeze partial statistics without
returning a successful `TransferResult`.

Sampled peaks depend on polling times, chunk completion times, and sample
duration; they are not physical link capacity. For transfers shorter than
100 ms, the final interval may be the only sample, so current, average, and peak
can legitimately be equal. Longer transfers may end with a zero current rate
while waiting for confirmation. Very short transfers remain sensitive to
scheduling overhead.

Connections have a three-second connect timeout, five-second read inactivity
timeout, and a 60-second overall deadline. Closing a socket at the overall
deadline also stops blocked writes. The server uses four client worker threads
and a bounded queue of four additional requests; excess connections are closed.

Localhost throughput measures this application's TCP payload performance. It
does not measure Internet bandwidth, adapter traffic, or physical link capacity.
Small transfers are especially affected by scheduling and acknowledgment time.
This phase does not provide encryption, authentication, packet-loss analysis,
payload integrity checks beyond TCP, or remote/LAN configuration.

## Monitor a Windows network adapter

List interfaces discovered by Java:

```shell
mvn exec:java "-Dexec.args=adapters"
```

Choose an interface index from the output. Prefer the active physical Wi-Fi or
Ethernet adapter. Java can also list loopback, tunnels, and driver filter
interfaces that Windows does not expose through `Get-NetAdapterStatistics`.
Their readings will be marked unavailable rather than replaced with fake data.

Monitor the selected index (replace `6` with the index on your computer):

```shell
mvn exec:java "-Dexec.args=monitor 6 4"
```

This prints four reading attempts, including the initial baseline, then stops
automatically. Omit the last argument for five attempts; the allowed range is
1 to 60. At least two successful readings are needed to calculate speed. Ctrl+C
also stops monitoring and closes an active PowerShell process.

The output includes the Windows adapter name and status, cumulative receive/send
counters, independent receive/send speeds in Mbps and MB/s, and bytes observed
during this monitoring session. A counter is not the same as a session total:
Windows counters may have accumulated before NetPulse started.

### Collection and calculations

`NetworkAdapterMonitor.listAdapters()` uses Java's `NetworkInterface` API for
interface indexes, names, descriptions, Up/Down state, and loopback detection.
That standard API does not expose traffic-byte counters. The small
`WindowsAdapterStatistics` helper therefore launches a fixed, noninteractive
PowerShell command using `ProcessBuilder`:

```powershell
$adapter = Get-NetAdapter -InterfaceIndex <selected-index> -IncludeHidden
$adapter | Get-NetAdapterStatistics
```

Only a validated numeric index enters the command. Adapter names are never
inserted into shell code. The helper checks the process exit code, times out
after five seconds, validates the returned index/GUID/counters, and rejects
missing, negative, malformed, or out-of-range values. Names use UTF-8 and Base64
in the small delimited response so spaces, Unicode, and delimiters are safe.
No third-party dependency or PowerShell profile is required.

The monitor runs one background scheduled worker, with a two-second delay
between completed queries. PowerShell startup/query time adds overhead; actual
intervals can be around three seconds or longer. Rates use the actual elapsed
time between completed readings, measured with `System.nanoTime()`:

```text
received delta = current received counter - previous received counter
sent delta     = current sent counter - previous sent counter
receive MB/s   = received delta / elapsed seconds / 1,000,000
send MB/s      = sent delta / elapsed seconds / 1,000,000
Mbps           = MB/s * 8
session total  = sum of valid observed deltas for that direction
```

### Snapshots, errors, and lifecycle

`NetworkStatisticsSnapshot` is an immutable record. The latest snapshot is
published through a `volatile` field; `getSnapshot()` returns immediately and
does not launch PowerShell. Future dashboard code can poll it independently of
the console. One `NetworkAdapterMonitor` instance represents one selected
adapter and one session. Call `start()` and later `stop()` or `close()`. Create
a new instance to restart or select another adapter.

Snapshot states distinguish `STARTING`, `BASELINE`, `VALID`, `COUNTER_RESET`,
`INVALID_INTERVAL`, `UNAVAILABLE`, and `STOPPED`. Speeds and counters use optional
values, and the console prints `N/A` when they are unavailable. A valid interval
with unchanged counters reports a measured zero speed.

- The first valid reading establishes a baseline and does not include previous
  adapter traffic in the session totals.
- A decrease in either counter or a changed adapter GUID renews the baseline.
  Existing session totals remain, but the reset interval is excluded.
- Zero/backward elapsed time has no speed and retains the previous baseline.
- Command failures, disconnected adapters, and invalid readings have no speed.
  Recovery requires a new baseline, so unknown traffic during gaps is excluded.
- Shutdown cancels scheduling, interrupts the worker, terminates an active
  PowerShell query, and waits for the worker to stop. Final totals remain
  readable; stopped snapshots have no current speed.

If access is denied or the NetAdapter module is unavailable, the error is shown
in the snapshot. The application does not automatically request administrator
rights or change Windows settings.

### Limitations and measurement meaning

These are Windows adapter counters, including traffic from other applications.
They are not NetPulse's own TCP test payload totals and do not measure available
bandwidth capacity. Localhost tests generally do not traverse physical Wi-Fi or
Ethernet adapters. Virtual/VPN interfaces can overlap other interfaces, so
NetPulse monitors one selected adapter rather than summing every interface.

PowerShell sampling is suitable for a college demonstration but adds process
overhead and cannot capture short bursts accurately. Permissions, driver
support, and interface availability affect readings. Query-completion timing is
an approximation of when Windows collected its counters. Cumulative counters
can reset, and session totals exclude intervals whose traffic cannot be
measured safely. Counters beyond Java's signed `long` range are rejected.

References: [Java 25 NetworkInterface API](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/net/NetworkInterface.html),
[Microsoft Get-NetAdapterStatistics](https://learn.microsoft.com/en-us/powershell/module/netadapter/get-netadapterstatistics).

## Automated tests

```shell
mvn test
```

Tests start and close their own loopback servers on automatically assigned
ports. They do not require a manually running server or use port 5001.
Coverage includes connections, uploads, downloads, exact byte counts, invalid
requests, unavailable servers, malformed transfers, timeout handling,
concurrent clients, server shutdown, and throughput unit conversions.
Measurement tests also cover zero/invalid elapsed time, empty and unknown-size
measurements, weighted averages, sampled peaks, idle periods, progress limits,
immutable snapshots, and polling statistics during real uploads/downloads.
Adapter tests use sample counters and a controlled clock to verify independent
receive/send rates, deltas, zero/backward time, resets, unavailable/disconnected
adapters, recovery, parsing, and worker shutdown. Sample values are used only in
tests; the production adapter monitor always reads Windows counters.

GUI unit tests check size conversion, snapshot rendering, adapter selection,
result display, Event Dispatch Thread responsiveness, and cancellation of a
blocked TCP read. Graph tests cover sample collection, duplicate snapshots,
60-second eviction, valid zeros, unavailable gaps, bounded memory, frozen
stopped history, immutable views, and painting at different sizes. They do not
open a window or require real PowerShell counters.

Additional desktop tests are opt-in because they open visible windows, move
the mouse, read real Windows counters, and temporarily start a test server on
port 5001. The dashboard test also attempts a small HTTPS download of a public Java API page to
generate real adapter activity; if that request fails, it observes existing
traffic. Close other NetPulse windows and stop any server on that port first.
Run them on an interactive Windows desktop with an active adapter:

```shell
mvn test "-Dtest=DashboardDesktopTest,TrafficAlertsDesktopTest,PersistenceDesktopTest,StabilityDesktopTest" "-Dnetpulse.gui.smoke=true"
```

The dashboard test verifies opening the dashboard, adapter refresh/selection, real live speeds,
both graphs, resize behavior, frozen graphs after stopping, an unavailable
adapter, a missing TCP server, both transfer modes, and closing with monitoring
active. It saves actual screenshots to `target/netpulse-dashboard.png` and
`target/netpulse-graphs.png`. The alert desktop test verifies settings,
real threshold alerts, suppression, disabling, clearing, responsiveness, and
shutdown; it saves `target/netpulse-alerts.png`. It also watches briefly for a
genuine idle interval. If the active adapter never becomes idle, it reports
that explicitly instead of fabricating an inactivity alert. Controlled analyzer
and Swing-rendering tests cover the inactivity INFO path independently.
The persistence desktop tests save real adapter readings and alerts, restart
the dashboard, verify restored settings and history tabs, cancel and confirm
alert deletion, and check recovery from a blocked database path. The history
viewer screenshot is saved to `target/netpulse-history.png`. All desktop tests
use temporary database paths rather than the real `data/netpulse.db`.
`StabilityDesktopTest` also starts the real TCP server in a separate JVM,
checks GUI uploads/downloads and exact throughput calculations, closes the
dashboard during or shortly after a large transfer with monitoring active,
and verifies graceful server shutdown and port reuse. It tests empty history,
1,000 clearly labelled history fixtures, all three time filters, the 500-row
display cap, database-lock errors, failed deletion preserving alerts, smaller
windows, and closing while a confirmation dialog is open. Fixture history
is confined to temporary test databases and is not presented as real traffic.
Normal `mvn test` skips these seven opt-in desktop test cases.

Alert unit tests cover independent thresholds, severity, strict comparisons,
suppression, clear/re-cross behavior, inactivity duration/reset, duplicate and
unavailable readings, disabled alerts, history bounds, configuration validation,
and disabling with an invalid draft field.

Database tests also use JUnit `@TempDir`, never the real application database.
They cover initialization/table creation, prepared-statement round trips,
duplicate suppression, time filtering, unavailable-reading exclusion, default
and updated settings, alert clearing, transaction rollback, daily totals,
five-second storage cadence, session restarts, shutdown flushing, locked and
unavailable database recovery, and file/connection cleanup. Regression tests
also cover final readings that the periodic collector has not seen, consecutive
monitoring sessions, queue saturation, retention cutoffs, preservation of alerts
and summaries, and valid/invalid retention configuration.

## Verification status and remaining limits

See [Phase 9 verification](docs/PHASE9_VERIFICATION.md) for the approved stability
baseline and [Phase 10 release verification](docs/PHASE10_RELEASE_VERIFICATION.md)
for final packaging results. A bundled runtime/application image is supplied;
an installer, code signing and separate clean-machine testing are not claimed.

The application requires an interactive Windows desktop, JDK 25, Maven, and
working NetAdapter PowerShell commands for real adapter statistics. Restricted
machines and unsupported interfaces may produce unavailable readings. Internet
activity and idle periods depend on the computer; deterministic counter tests
cover cases that cannot be forced reliably on an active adapter.

TCP testing remains localhost-only, without authentication/encryption or an
Internet capacity/packet-loss test. Graphs and alerts inherit the adapter
sampling delay. Daily totals describe valid observed intervals, not continuous
24-hour capture. History filtering returns at most 500 rows per tab; large
databases are not fully loaded into Swing. Long storage outages and forced
termination can still lose records. Retention is opt-in, and alerts/daily
summaries continue growing until explicitly managed in a later phase.
