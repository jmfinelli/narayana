/*
 * JBoss, Home of Professional Open Source
 * Copyright 2009, Red Hat Middleware LLC, and individual contributors
 * as indicated by the @author tags.
 * See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 * This copyrighted material is made available to anyone wishing to use,
 * modify, copy, or redistribute it subject to the terms and conditions
 * of the GNU Lesser General Public License, v. 2.1.
 * This program is distributed in the hope that it will be useful, but WITHOUT A
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE.  See the GNU Lesser General Public License for more details.
 * You should have received a copy of the GNU Lesser General Public License,
 * v.2.1 along with this distribution; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA  02110-1301, USA.
 *
 * (C) 2009,
 * @author JBoss Inc.
 */
package org.jboss.jbossts.qa.junit;

import org.junit.*;
import org.jboss.jbossts.qa.Utils.FileServerIORStore;

import java.io.*;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Base class from which all autogenerated test suites inherit common behaviour.
 *
 * @author Jonathan Halliday (jonathan.halliday@redhat.com) 2009-05
 */

public class TestGroupBase
{
    @Rule public final QATestNameRule testName = new QATestNameRule();

    protected boolean isRecoveryManagerNeeded = false;
    private Task recoveryManager;

    private final List<Task> servers = new LinkedList<Task>();
    private final Map<String,Integer> objectStoreNamesToTaskIds = new HashMap<String,Integer>();
    private int clientCount = 0;
    private int taskCount = 0;

    @Before public void setUp()
	{
        clientCount = 0;
        taskCount = 0;

        // no need to do this here as it gets done in tearDown
        // TaskImpl.cleanupTasks();

        Task emptyObjectStore = createTask("emptyObjectStore", org.jboss.jbossts.qa.Utils.EmptyObjectStore.class, Task.TaskType.EXPECT_PASS_FAIL, 480);
        emptyObjectStore.perform();

        if(isRecoveryManagerNeeded) {
            recoveryManager = createTask("server0", com.arjuna.ats.arjuna.recovery.RecoveryManager.class, Task.TaskType.EXPECT_READY, 480);
		    recoveryManager.start("-test");
        }
    }

	@After public void tearDown()
	{
        stopServers();

        if(isRecoveryManagerNeeded) {
                recoveryManager.terminate();
        }

        TaskImpl.cleanupTasks();

        servers.clear();
        objectStoreNamesToTaskIds.clear();

        Task emptyObjectStore = createTask("emptyObjectStore", org.jboss.jbossts.qa.Utils.EmptyObjectStore.class, Task.TaskType.EXPECT_PASS_FAIL, 480);
        emptyObjectStore.perform();

        try {
            Thread.sleep(3000);
        } catch(InterruptedException e) {}
    }

    /**
     * By default the group name for a test method is the name of the class
     * with any TestGroup_ prefix removed.
     * 
     * @return the test group name for the current test.
     */
    protected String getTestGroupName() {
        return testName.getGroupName();
    }

    protected void setTestName(String testMethodName) {
        // QATestNameRule is now used instead
    }

    protected void startServer(Class clazz, String... args) {

        Task server;
        synchronized(servers) {
            String name = "server_"+servers.size();
            server = createTask(name, clazz, Task.TaskType.EXPECT_READY, 480);
            servers.add(server);
        }

        server.start(args);
    }

    private void stopServers() {
        // stop them in reverse order
        while(!servers.isEmpty()) {
            Task server = servers.remove(servers.size()-1);
            server.terminate();
        }
    }

    protected void startAndWaitForClient(Class clazz, String... args) {
        String name = "client_"+clientCount;
        clientCount+=1;
        Task client = createTask(name, clazz, Task.TaskType.EXPECT_PASS_FAIL, 480);
		client.start(args);
		client.waitFor();
    }

    protected void startAndWaitForClientWithFixedStoreDir(Class clazz, String... args) {
        String name = "client_"+clientCount;
        clientCount+=1;
        Task client = createTask(name, clazz, Task.TaskType.EXPECT_PASS_FAIL, 480, "client");
		client.start(args);
		client.waitFor();
    }

    protected Task createTask(String taskName, Class clazz, Task.TaskType taskType, int timeout) {
        return createTask(taskName, clazz, taskType, timeout, taskName);
    }

    protected Task createTask(String taskName, Class clazz, Task.TaskType taskType, int timeout, String objectStoreDirBaseName) {

        OutputStream out;
        int portOffsetId = taskCount;
        taskCount += 1;

        String filename = "./testoutput/"+getTestGroupName()+"/"+(testName.getMethodName() == null ? "" : testName.getMethodName())+
                (testName.getParameterSetNumber() == null ? "" : "_paramSet"+testName.getParameterSetNumber())+"/"+taskName+"_output.txt";
        try {
            File outFile = new File(filename);
            if (outFile.isDirectory()) {
                Assert.fail("createTask : output file name identifies directory " + filename);
            }
            File directory = outFile.getParentFile();
            if (!directory.exists() && !directory.mkdirs()) {
                Assert.fail("createTask : could not create directory for file " + filename);
            }
            out = new FileOutputStream(outFile);

            File emmaCoverageFile = new File(directory, taskName+"-coverage.ec");

            List<String> additionalCommandLineElements = new LinkedList<String>();

            additionalCommandLineElements.add("-Demma.coverage.out.file="+emmaCoverageFile);

            File objectStoreBaseDir = new File(directory, objectStoreDirBaseName);

            if(objectStoreNamesToTaskIds.containsKey(objectStoreDirBaseName)) {
                portOffsetId = objectStoreNamesToTaskIds.get(objectStoreDirBaseName);
            } else {
                objectStoreNamesToTaskIds.put(objectStoreDirBaseName, portOffsetId);
            }

            additionalCommandLineElements.add("-DportOffsetId="+portOffsetId);
            additionalCommandLineElements.add("-DObjectStoreBaseDir="+objectStoreBaseDir.getCanonicalPath());
            additionalCommandLineElements.add("-DRecoveryEnvironmentBean.recoveryListener=true"); // JBTM-810

            return new TaskImpl(taskName, clazz, taskType, new PrintStream(out, true), timeout, additionalCommandLineElements, directory);
        } catch (Exception e) {
            e.printStackTrace();
            Assert.fail("createTask : could not open output stream for file " + filename);
            return null;
        }
    }

    /**
     * 
     */
    protected void removeServerIORStore(String name, String... params) {
        // the old, slow way spawned a cleanup task:
        //Task task = createTask(name, org.jboss.jbossts.qa.Utils.RemoveServerIORStore.class, Task.TaskType.EXPECT_PASS_FAIL, 1480);
        //task.perform(params);

        // the new, quick way does it in-process with the test harness.
        // this may break if the tests are changed to use a different store implementation, as it
        // does not bother with the plugin abstraction used by RemoveServerIORStore/ServerIORStore
        FileServerIORStore store = new FileServerIORStore();
        store.remove();
    }
}
