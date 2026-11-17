package com.netpulse;

import java.io.IOException;

/** Entry point for the Windows image. Developer commands still use Main directly. */
public final class DesktopLauncher {
    private DesktopLauncher() { }

    public static void main(String[] args) throws IOException, InterruptedException {
        System.setProperty("netpulse.packaged", "true");
        Main.main(args.length == 0 ? new String[]{"gui"} : args);
    }
}
