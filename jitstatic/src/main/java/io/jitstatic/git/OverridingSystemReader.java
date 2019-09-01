package io.jitstatic.git;

/*-
 * #%L
 * jitstatic
 * %%
 * Copyright (C) 2017 - 2019 H.Hegardt
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;

import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.ConfigConstants;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;

/* 
 * Ugly fix because JGit doesn't cache the configuration so it gets read from disk on hot path.
 * This might be because of the support of NFS and the problems related to that.
 * This is a quick and dirty fix for now. Configurations doesn't change that often with JitStatic
*/
public class OverridingSystemReader extends org.eclipse.jgit.util.SystemReader {

    private volatile String hostname;
    private volatile FileBasedConfig systemConfig;
    private volatile FileBasedConfig userConfig;

    @Override
    public String getenv(final String variable) {
        return System.getenv(variable);
    }

    @Override
    public String getProperty(final String key) {
        return System.getProperty(key);
    }

    @Override
    public FileBasedConfig openSystemConfig(final Config parent, final FS fs) {
        FileBasedConfig fbc;
        if ((fbc = systemConfig) == null) {
            final File configFile = fs.getGitSystemConfig();
            if (configFile == null) {
                fbc = new FileBasedConfig(null, fs) {
                    @Override
                    public void load() {
                        // empty
                    }

                    @Override
                    public boolean isOutdated() {
                        return false;
                    }
                };
            } else {
                fbc = new LoadOnceFileBaseConfig(parent, configFile, fs);
                ((LoadOnceFileBaseConfig) fbc).tryLoad();
            }
            systemConfig = fbc;
        }
        return fbc;
    }

    @Override
    public FileBasedConfig openUserConfig(final Config parent, final FS fs) {
        FileBasedConfig cfg;
        if ((cfg = userConfig) == null) {
            final File home = fs.userHome();
            cfg = new LoadOnceFileBaseConfig(parent, new File(home, ".gitconfig"), fs);
            ((LoadOnceFileBaseConfig) cfg).tryLoad();
            cfg.setBoolean(ConfigConstants.CONFIG_CORE_SECTION, null, ConfigConstants.CONFIG_KEY_SUPPORTSATOMICFILECREATION, true);
            userConfig = cfg;
        }
        return cfg;
    }

    @Override
    public String getHostname() {
        if (hostname == null) {
            try {
                final InetAddress localMachine = InetAddress.getLocalHost();
                hostname = localMachine.getCanonicalHostName();
            } catch (UnknownHostException e) {
                // we do nothing
                hostname = "localhost";
            }
        }
        return hostname;
    }

    @Override
    public long getCurrentTime() {
        return System.currentTimeMillis();
    }

    @Override
    public int getTimezone(final long when) {
        return getTimeZone().getOffset(when) / (60 * 1000);
    }

    private static class LoadOnceFileBaseConfig extends FileBasedConfig {

        private volatile Exception ex;

        public LoadOnceFileBaseConfig(Config base, File cfgLocation, FS fs) {
            super(base, cfgLocation, fs);
        }

        private void tryLoad() {
            try {
                super.load();
            } catch (IOException | ConfigInvalidException e) {
                ex = e;
            }
        }

        @Override
        public void load() throws IOException, ConfigInvalidException {
            if (ex != null) {
                if (ex instanceof IOException) {
                    throw (IOException) ex;
                }
                if (ex instanceof ConfigInvalidException) {
                    throw (ConfigInvalidException) ex;
                }
                throw new ReadConfigurationException(ex);
            }
        }

        @Override
        public boolean isOutdated() {
            return false;
        }
    }

    private static class ReadConfigurationException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public ReadConfigurationException(Exception ex) {
            super(ex);
        }
    }

    @Override
    public StoredConfig getSystemConfig()
            throws IOException, ConfigInvalidException {
        FileBasedConfig c = openSystemConfig(null, FS.DETECTED);
        if (c.isOutdated()) {
            c.load();
        }
        return c;
    }

    @Override
    public StoredConfig getUserConfig()
            throws IOException, ConfigInvalidException {
        FileBasedConfig c = openUserConfig(getSystemConfig(), FS.DETECTED);
        if (c.isOutdated()) {
            c.load();
        }
        return c;
    }
}
