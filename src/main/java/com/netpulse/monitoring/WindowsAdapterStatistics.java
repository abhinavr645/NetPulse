package com.netpulse.monitoring;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** The only Windows-specific part of adapter monitoring. No extra libraries are needed. */
class WindowsAdapterStatistics implements AutoCloseable {
    record Counters(String interfaceId, String name, String status, long receivedBytes, long sentBytes) { }

    private volatile Process activeProcess;
    private volatile boolean closed;

    Counters read(int interfaceIndex) throws IOException {
        if (interfaceIndex <= 0) {
            throw new IllegalArgumentException("Interface index must be positive.");
        }
        if (closed) {
            throw new IOException("The statistics reader is closed.");
        }
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
            throw new IOException("Adapter statistics currently require Windows PowerShell.");
        }

        // Only a validated integer is inserted into the fixed command, never an adapter name.
        String script = """
                $ErrorActionPreference = 'Stop'
                $ProgressPreference = 'SilentlyContinue'
                $WarningPreference = 'SilentlyContinue'
                [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
                try {
                    $adapter = Get-NetAdapter -InterfaceIndex %d -IncludeHidden -ErrorAction Stop
                    $stats = $adapter | Get-NetAdapterStatistics -ErrorAction Stop
                    if ($null -eq $stats.ReceivedBytes -or $null -eq $stats.SentBytes) {
                        throw 'The adapter does not expose byte counters.'
                    }
                    $name = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes([string]$adapter.Name))
                    [Console]::WriteLine(('NETPULSE|{0}|{1}|{2}|{3}|{4}|{5}' -f
                        $adapter.ifIndex, $adapter.InterfaceGuid, $name, [string]$adapter.Status,
                        [string]$stats.ReceivedBytes, [string]$stats.SentBytes))
                } catch {
                    $message = $_.Exception.Message
                    [Console]::Error.WriteLine($message.Substring(0, [Math]::Min(512, $message.Length)))
                    exit 1
                }
                """.formatted(interfaceIndex);

        Process process = new ProcessBuilder("powershell.exe", "-NoLogo", "-NoProfile",
                "-NonInteractive", "-WindowStyle", "Hidden", "-Command", script)
                .redirectErrorStream(true).start();
        activeProcess = process;
        try (var output = process.getInputStream(); var input = process.getOutputStream()) {
            input.close(); // This fixed, noninteractive command never needs standard input.
            if (closed) {
                throw new IOException("The statistics reader was stopped.");
            }
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                throw new IOException("PowerShell adapter query timed out after 5 seconds.");
            }
            // The fixed script emits one short record or a bounded error message.
            byte[] bytes = output.readNBytes(8_193);
            if (bytes.length > 8_192) {
                throw new IOException("PowerShell returned too much output.");
            }
            String text = new String(bytes, StandardCharsets.UTF_8).strip();
            if (process.exitValue() != 0) {
                throw new IOException("PowerShell could not read this adapter: " + text);
            }
            return parse(text, interfaceIndex);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Adapter query interrupted during shutdown.");
        } finally {
            process.destroyForcibly();
            activeProcess = null;
        }
    }

    static Counters parse(String output, int expectedIndex) throws IOException {
        String[] fields = output.strip().split("\\|", -1);
        if (fields.length != 7 || !fields[0].equals("NETPULSE")) {
            throw new IOException("PowerShell returned an invalid adapter record.");
        }
        try {
            if (Integer.parseInt(fields[1]) != expectedIndex) {
                throw new IllegalArgumentException("Interface index does not match the selected adapter.");
            }
            String id = UUID.fromString(fields[2].replace("{", "").replace("}", "")).toString();
            String name = new String(Base64.getDecoder().decode(fields[3]), StandardCharsets.UTF_8);
            long received = Long.parseLong(fields[5]);
            long sent = Long.parseLong(fields[6]);
            if (name.isBlank() || fields[4].isBlank() || received < 0 || sent < 0) {
                throw new IllegalArgumentException("Missing adapter metadata or negative counters.");
            }
            return new Counters(id, name, fields[4], received, sent);
        } catch (IllegalArgumentException exception) {
            throw new IOException("PowerShell returned invalid adapter values: " + exception.getMessage(), exception);
        }
    }

    @Override
    public void close() {
        closed = true;
        Process process = activeProcess;
        if (process != null) {
            process.destroyForcibly();
        }
    }
}
