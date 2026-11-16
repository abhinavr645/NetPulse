# NetPulse - Network Bandwidth Monitoring and Analysis System

NetPulse is a college Computer Networks project for monitoring and analyzing
network bandwidth. Future phases will add a Swing desktop interface and
monitoring history stored in SQLite.

Phase 3 adds a reusable measurement engine to the command-line TCP upload and
download tests. It tracks payload bytes, elapsed time, current/average/peak
throughput, and transfer progress. GUI and database operations are not
implemented yet.

## Technologies

- JDK 25
- Maven
- Java Swing for the future desktop interface
- TCP socket programming for upload and download tests
- SQLite with JDBC for future history storage
- JUnit 5 for automated tests
- Visual Studio Code
- Windows 11

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
    ui/
    model/
        BandwidthSnapshot.java
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
