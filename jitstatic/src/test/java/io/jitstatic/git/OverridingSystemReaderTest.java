package io.jitstatic.git;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.spi.FileSystemProvider;

import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class OverridingSystemReaderTest {

    @Test
    void testHostName() {
        OverridingSystemReader reader = new OverridingSystemReader();
        assertNotNull(reader.getHostname());
    }

    @Test
    void testLoadFileNoSystemConfig() throws IOException, ConfigInvalidException {
        OverridingSystemReader reader = new OverridingSystemReader();
        Config config = Mockito.mock(Config.class);
        FS fs = Mockito.mock(FS.class);
        FileBasedConfig openSystemConfig = reader.openSystemConfig(config, fs);
        assertNotNull(openSystemConfig);
        Mockito.verify(fs).getGitSystemConfig();
        openSystemConfig.load();
        assertFalse(openSystemConfig.isOutdated());
    }

}
