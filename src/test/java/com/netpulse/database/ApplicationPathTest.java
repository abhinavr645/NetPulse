package com.netpulse.database;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ApplicationPathTest {
    @Test void developerCommandsKeepTheirExistingWorkingDirectoryDatabase() {
        assertEquals(Path.of("data", "netpulse.db"), DatabaseManager.resolvePath(null, false, "C:/Local", Path.of("C:/User")));
    }
    @Test void packagedLauncherUsesLocalApplicationDataRegardlessOfWorkingDirectory() {
        assertEquals(Path.of("C:/Local/NetPulse/netpulse.db"), DatabaseManager.resolvePath(null, true, "C:/Local", Path.of("C:/User")));
    }
    @Test void missingEnvironmentFallsBackToUserLocalDataRatherThanInstallationDirectory() {
        assertEquals(Path.of("C:/User/AppData/Local/NetPulse/netpulse.db"), DatabaseManager.resolvePath(null, true, "", Path.of("C:/User")));
    }
    @Test void explicitTestOrDeveloperOverrideTakesPrecedence() {
        assertEquals(Path.of("custom/netpulse.db"), DatabaseManager.resolvePath("custom/netpulse.db", true, "C:/Local", Path.of("C:/User")));
    }
}
