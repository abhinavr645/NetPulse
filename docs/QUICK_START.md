# NetPulse — quick start and college demonstration

## Run the Windows release

1. Extract the complete release ZIP to a folder you can read, such as Documents.
2. Open `NetPulse` and double-click `NetPulse.cmd`. Keep the complete folder.
3. The GUI opens by default. A bundled Java 25 runtime is used; Java and Maven
   do not have to be installed separately for the release.
4. SQLite is created automatically at `%LOCALAPPDATA%\NetPulse\netpulse.db`.
   The dashboard's storage status shows the active path. It writes no database
   inside the application folder, even when the folder is protected.

The generated unsigned `NetPulse.exe` is included but was blocked by application
control on the verification computer. Its launch is not claimed as verified.
The `.cmd` file invokes the bundled vendor-signed Java launcher; do not disable
security settings to run the unsigned EXE.

Windows 11 x64, an interactive desktop and Windows PowerShell's NetAdapter
module are required. A permission or adapter error is shown without inventing
readings. The unsigned release is a portable folder, not a setup installer.

## Monitor adapter traffic

1. Select the active Wi-Fi or Ethernet adapter; click Refresh adapters if needed.
2. Click **Start Monitoring**. Allow a baseline and two successful samples;
   PowerShell queries make updates approximately three seconds apart.
3. Use a browser normally to create real traffic. Observe download/upload
   cards, session totals and the two 60-second graphs.
4. Click **Stop Monitoring**. Graphs freeze; the last pending reading is saved.

Speeds describe actual adapter counter differences, including other programs.
Unavailable values show `--`, not fake zeros. Session totals cover valid observed
intervals and exclude traffic before monitoring or during unavailable intervals.

## Run the TCP demonstration

1. Scroll to **TCP bandwidth test** and click **Start local server**.
2. Confirm **Running on 127.0.0.1:5001**. If the port is occupied, stop your own
   other server or use it for tests; NetPulse does not terminate other programs.
3. Choose Upload, enter `10` MB, and click Start Test. Repeat with Download.
   Each transfers exactly 10,000,000 payload bytes through TCP sockets.
4. Compare elapsed time and measured Mbps/MB/s. A short localhost test can be
   much faster than Wi-Fi because it does not traverse the physical adapter.
5. Click **Stop local server**. Closing the dashboard also stops its own server.

## Demonstrate alerts and persistence

Defaults: alerts disabled, download threshold 100 Mbps, upload 50 Mbps, idle 30 s.
Enable alerts and Apply settings to save changes. For a demonstration with light
traffic, temporarily use a small positive threshold appropriate to the readings
you actually see. An active condition alerts once until it clears and recurs.
Twice the configured threshold produces CRITICAL; other threshold events are
WARNING. Valid zero traffic for the configured duration generates INFO and does
not imply Internet disconnection. Real idle periods may not occur on a busy PC.

Open **View saved history**. Use Network readings, Saved alerts and Daily totals;
choose Last 24 hours, Last 7 days or All time and Refresh history. Each tab shows
at most 500 rows. Readings are stored about every five seconds plus session-end
flushes. Daily totals summarize observed traffic, not traffic while NetPulse is
closed. Restart the packaged launcher to demonstrate saved settings and history.

**Clear history** in the alert section asks for confirmation and deletes all
saved alerts. It retains network history, daily summaries and settings.
Automatic network-history deletion is disabled by default. Developer retention
configuration is documented in the source README; changing launcher JVM options
is an advanced deployment choice, not required for the demonstration.

## Close and troubleshoot

Close the main window normally and allow cleanup. Monitoring queries, graphs,
owned TCP sockets/server and the database worker stop. A separately launched
server remains independent. Abrupt process termination can lose pending records.

- Missing adapter/statistics: choose a supported physical adapter and check
  `Get-NetAdapterStatistics` availability in Windows PowerShell.
- Port occupied: close the server you own; no administrator privileges or
  firewall-rule changes should be needed for localhost tests.
- Storage error: check write permission and disk space under local application
  data. Live monitoring continues; failed writes may leave gaps. Refresh retries.
- Cannot launch: extract all files, use Windows x64 and keep `runtime` intact.
- For an academic submission, edit the name/USN/college/guide placeholders in
  `docs/NetPulse_Project_Report.md` before presenting or printing it.

## Developer commands (source checkout only)

Install JDK 25 and Maven, open a terminal at the folder containing `pom.xml`:

```shell
mvn clean compile
mvn test
mvn exec:java "-Dexec.args=gui"
mvn exec:java "-Dexec.args=server"
mvn exec:java "-Dexec.args=client UPLOAD 10485760"
mvn exec:java "-Dexec.args=client DOWNLOAD 10485760"
```

The last three commands use separate terminals as appropriate. Source commands
keep `data/netpulse.db` in the working directory. They are not required to run
the packaged GUI. Build instructions and all test commands are in the source README.
