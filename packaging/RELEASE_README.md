# NetPulse 1.0.0 — Windows x64

Network Bandwidth Monitoring and Analysis System — college Computer Networks project.

Extract the complete ZIP, then double-click **NetPulse/NetPulse.cmd**. Keep the
`app` and `runtime` folders beside the launcher. No separate Java or Maven
installation is needed to run this application image. Windows 11 x64 and working
Windows PowerShell NetAdapter commands are required for adapter statistics.

This is a portable application folder, not an MSI/setup installer. It does not
modify the registry, create shortcuts or request administrator access. To remove
the application, close it and delete the extracted folder. Monitoring history
remains separately under `%LOCALAPPDATA%\NetPulse\netpulse.db` until you choose
to remove it. Existing developer databases are not migrated automatically.

- [Quick start and demonstration steps](QUICK_START.md)
- [College project report](docs/NetPulse_Project_Report.md)
- [Architecture diagrams](docs/ARCHITECTURE.md)
- [Release verification and limitations](docs/PHASE10_RELEASE_VERIFICATION.md)
- [Screenshots](docs/screenshots/)
- [Dependency notices](licenses/THIRD_PARTY_NOTICES.md)

Click **Start local server** in the TCP section before an upload/download test.
The dashboard stops only its own server, including on window close. A port-5001
conflict is reported; another application's server is never stopped for you.
Adapter traffic includes other programs and is separate from localhost payload
throughput. Neither is a measurement of Internet bandwidth capacity.

The generated `NetPulse.exe` is also included, but is unsigned and was blocked
by Windows Application Control on the verification machine. It is not claimed
as verified. `NetPulse.cmd` uses the bundled Eclipse-signed Java launcher; no
security settings need to be changed. A separate machine with Java uninstalled
has not been used for verification; see the release verification document for
the bundled-runtime checks actually performed. No cloud service/account is used.

NetPulse's authorship and college details are intentionally editable placeholders
in the report. No open-source license grant for the project's own code is
asserted here. Bundled third-party licenses remain applicable and are included.
