# NetPulse architecture diagrams

These diagrams describe the implemented application. Arrows show method calls
or immutable data being passed between components, not packet capture.

## Overall system architecture

```mermaid
flowchart TB
    EXE[NetPulse.cmd / native EXE + bundled Java 25 runtime] --> DL[DesktopLauncher: GUI default and packaged data mode]
    CLI[Developer CLI] --> MAIN[Main]
    DL --> MAIN
    MAIN --> UI[DashboardFrame / DashboardPanel / HistoryDialog]
    UI <--> CTRL[DashboardController]
    CTRL --> MON[NetworkAdapterMonitor]
    MON --> PS[Windows PowerShell NetAdapter counters]
    MON --> SNAP[Immutable NetworkStatisticsSnapshot]
    SNAP --> ANALYSIS[TrafficAnalyzer]
    SNAP --> GRAPHS[Bounded BandwidthGraphHistory + Java2D panels]
    ANALYSIS --> ALERTS[Immutable TrafficAlert + bounded display history]
    CTRL --> CLIENT[BandwidthClient + BandwidthMeasurement]
    CTRL --> SERVER[Optional dashboard-owned BandwidthServer]
    CLIENT <-->|TCP 127.0.0.1:5001| SERVER
    CTRL --> STORE[PersistenceService: bounded queue / one JDBC worker]
    STORE --> REPOS[Repositories + DatabaseManager]
    REPOS --> DB[(SQLite netpulse.db)]
```

Swing components and graph history are updated on the Event Dispatch Thread.
The generated native EXE was blocked by signing policy on the verification
machine; the supplied batch launcher uses the bundled vendor-signed Java runtime.
PowerShell, TCP transfers, server lifecycle, analysis and SQLite work run in
background tasks. SQLite stores adapter readings, not TCP test payload results.

## TCP client-server communication

```mermaid
sequenceDiagram
    participant GUI as GUI / CLI caller
    participant C as BandwidthClient
    participant S as BandwidthServer
    GUI->>C: transfer(mode, expected bytes)
    C->>S: TCP connect to 127.0.0.1:5001
    C->>S: UTF UPLOAD/DOWNLOAD + long expected bytes
    S-->>C: UTF READY (or ERROR)
    Note over C: System.nanoTime measurement starts after READY
    alt UPLOAD
        C->>S: Exact payload, 64 KiB buffer
        C->>S: Half-close output (EOF)
        S->>S: Check byte count and EOF
        S-->>C: UTF COMPLETE + long received count
    else DOWNLOAD
        S-->>C: Exact payload, 64 KiB buffer
        S-->>C: Half-close output (EOF)
        C->>C: Check byte count and EOF
        C->>S: long received count
    end
    C->>C: Finish measurement; exclude protocol bytes
    C-->>GUI: Immutable TransferResult / error
    Note over C,S: One transfer per connection; sockets close afterward
```

The GUI can start/stop its own server. The server can also run in a separate
process using the unchanged developer CLI. Localhost throughput is not Internet
capacity and usually does not traverse the physical Wi-Fi/Ethernet adapter.

## Network monitoring data flow

```mermaid
flowchart LR
    JAVA[NetworkInterface lists adapters] --> SELECT[User selects interface index]
    SELECT --> QUERY[Background fixed PowerShell query]
    QUERY --> VALIDATE[Validate identity, status and byte counters]
    VALIDATE --> DIFF[Counter differences / nanoTime interval]
    VALIDATE --> GAP[Unavailable/reset/baseline states: no fake speeds]
    DIFF --> SNAP[Thread-safe immutable snapshot]
    GAP --> SNAP
    SNAP --> COLLECT[200 ms cached-snapshot collector; deduplicate identity]
    COLLECT --> ANALYZE[Thresholds, observed inactivity and suppression]
    COLLECT --> SAVE[Five-second persistence aggregation]
    COLLECT --> EDT[EDT: add actual graph points]
    SNAP --> TIMER[500 ms Swing display timer]
    EDT --> HISTORY[60-second / 600-sample bounded history]
    TIMER --> CARDS[Cards, session totals and repaint]
    HISTORY --> CARDS
```

The collector never launches extra PowerShell commands. Stop captures the
monitor's final published reading before flushing persistence; graph samples
and the time axis freeze. Unknown readings create gaps rather than zeros.

## Database interaction

```mermaid
flowchart TB
    START[GUI startup] --> LOAD[Load settings + newest 100 saved alerts]
    SNAP[Valid observed adapter snapshots] --> AGG[Downsample history and accumulate observed deltas]
    ALERT[New analyzer alerts] --> Q[Bounded 256-operation queue]
    SETTINGS[Apply settings / enabled state] --> Q
    AGG --> Q
    LOAD --> Q
    VIEW[History viewer filters / Refresh] --> Q
    CLEAR[Confirmed clear alerts] --> Q
    Q --> WORKER[One database worker + reusable connection]
    WORKER --> NH[(network_history)]
    WORKER --> TS[(traffic_summary)]
    WORKER --> AL[(alerts)]
    WORKER --> AS[(alert_settings)]
    NH -.transaction with observed deltas.-> TS
    WORKER --> EDT[Immutable results / errors posted to EDT]
    CLOSE[Normal shutdown] --> FLUSH[Final pending reading + drain queue + close connection]
    FLUSH --> WORKER
```

Packaged data path: `%LOCALAPPDATA%\NetPulse\netpulse.db`, with a user-home
local-data fallback. Developer data path: `data/netpulse.db`. Explicit test
paths take precedence. Retention is opt-in, defaults to 30 days when enabled,
and affects only old detailed network-history rows.
