# NetPulse - Network Bandwidth Monitoring and Analysis System

NetPulse is a college Computer Networks project with a Swing dashboard for
network adapter monitoring and TCP bandwidth tests. Future phases will add
real-time graphs and monitoring history stored in SQLite.

Phase 5 adds a dark-themed Java Swing dashboard for the existing Windows
adapter monitor and TCP bandwidth tests. Adapter statistics and TCP payload
measurements remain separate. Graphs, alerts, traffic analysis, and database
operations are not implemented yet.

## Technologies

- JDK 25
- Maven
- Java Swing for the desktop dashboard
- TCP socket programming for upload and download tests
- SQLite with JDBC for future history storage
- JUnit 5 for automated tests
- Visual Studio Code
- Windows 11
- Windows PowerShell with the NetAdapter module for adapter statistics

## Project structure

```text
src/main/java/com/netpulse/
    Main.java
    network/
        BandwidthServer.java
        BandwidthClient.java
        TransferProtocol.java
    monitoring/
        BandwidthMeasurement.java
        NetworkAdapterMonitor.java
        WindowsAdapterStatistics.java
    ui/
        DashboardFrame.java
        DashboardPanel.java
        DashboardController.java
        DashboardStyle.java
    model/
        BandwidthSnapshot.java
        NetworkAdapter.java
        NetworkStatisticsSnapshot.java
        TransferResult.java
    database/
    alerts/
```

The remaining empty packages are reserved for later phases. Automated tests are
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

To use the TCP section, start the existing server in a second terminal:

```shell
mvn exec:java "-Dexec.args=server"
```

Then select Upload or Download in the dashboard and click Start Test. The
default GUI size of `10` MB means exactly 10,000,000 bytes. Fractional MB values
are allowed when they represent whole bytes; the maximum is 1073.741824 MB
(1 GiB). The GUI does not automatically start a server. If none is listening on
`127.0.0.1:5001`, it reports a connection error and remains responsive.

Dashboard responsibilities are separated:

- `DashboardPanel` creates components and renders existing immutable models.
- `DashboardController` calls `NetworkAdapterMonitor.listAdapters()`, creates
  the selected monitor, and invokes the existing `BandwidthClient.transfer()`.
  The server continues to use the existing `BandwidthServer` CLI command.
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
  up owned background work. An externally started CLI server remains separate.

The window can be resized and scrolled on smaller screens. Unavailable speeds
display `--`; a measured zero displays `0.000000`. Totals describe valid observed
traffic in the current monitoring session. TCP results are shown in their own
section and are never substituted for adapter readings.

Socket cancellation reference:
[JDK 25 Socket API](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/net/Socket.html).

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
blocked TCP read. They do not open a window or require real PowerShell counters.

An additional desktop test is opt-in because it opens a visible window, moves
the mouse, reads real Windows counters, and temporarily starts a test server on
port 5001. Close other NetPulse windows and stop any server on that port first.
Run it on an interactive Windows desktop with an active adapter:

```shell
mvn test "-Dtest=DashboardDesktopTest" "-Dnetpulse.gui.smoke=true"
```

It verifies opening the dashboard, adapter refresh/selection, real live speeds,
monitor start/stop, an unavailable adapter, a missing TCP server, both transfer
modes, and closing with monitoring active. It saves an actual screenshot to
`target/netpulse-dashboard.png`. Normal `mvn test` skips this one desktop test.
