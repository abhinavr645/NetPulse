# Screenshot provenance

The four PNG files are genuine Windows captures of the verified NetPulse GUI.
They are copied from acceptance-test output under `target/`; no traffic values
or interfaces were drawn into the images. The original Phase 9 screenshots
were reviewed before the clean build, and final release screenshots were
selected after the corresponding GUI checks passed.

- `netpulse-dashboard.png`: selected real adapter, speed cards, session totals.
- `netpulse-graphs.png`: real download/upload points in the 60-second window.
- `netpulse-alerts.png`: actual threshold events and severity/history controls.
- `netpulse-history.png`: real saved readings shown in the history viewer.

Tiny thresholds in demonstration screenshots are deliberate test configuration,
not the application's default 100/50 Mbps thresholds. Sparse points near Now
show a short real session; the application does not invent a full minute of data.
The alert panel capture is limited to its visible section to exclude unrelated
desktop content and local storage paths. No fabricated or placeholder images
are used. The original pulse icon is a separate project graphic, not a screenshot.
