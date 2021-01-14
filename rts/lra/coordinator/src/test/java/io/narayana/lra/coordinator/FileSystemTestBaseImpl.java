/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2019, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package io.narayana.lra.coordinator;

import io.narayana.lra.coordinator.domain.model.LongRunningAction;
import io.narayana.lra.logging.LRALogger;

import org.junit.BeforeClass;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

public class FileSystemTestBaseImpl extends AbstractTestBase {

    private static Path storeDir;

    @BeforeClass
    public static void beforeClass() {
        storeDir = Paths.get(String.format("%s/standalone/data/tx-object-store", System.getProperty("env.JBOSS_HOME", "null")));
    }

    @Override
    void clearRecoveryLog() {
        try (Stream<Path> recoveryLogFiles = Files.walk(storeDir)) {
            recoveryLogFiles
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        } catch (IOException ioe) {
            // transaction logs will only exists after there has been a previous run
            LRALogger.logger.debugf(ioe,"Cannot finish delete operation on recovery log dir '%s'", storeDir);
        }
    }

    @Override
    String getFirstLRA() {
        Path lraDir = Paths.get(storeDir.toString(), "ShadowNoFileLockStore", "defaultStore", LongRunningAction.getType());

        try {
            Optional<Path> lra = Files.list(new File(lraDir.toString()).toPath()).findFirst();

            return lra.map(path -> path.getFileName().toString()).orElse(null);
        } catch (IOException e) {
            return null;
        }
    }
}
