/*
 * Copyright 2013 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package com.netflix.priam.utils;

import com.google.common.util.concurrent.RateLimiter;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.netflix.priam.ICassandraProcess;
import com.netflix.priam.IConfiguration;
import com.netflix.priam.health.InstanceState;
import com.netflix.priam.scheduler.SimpleTimer;
import com.netflix.priam.scheduler.Task;
import com.netflix.priam.scheduler.TaskTimer;
import org.apache.cassandra.tools.NodeProbe;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.atomic.AtomicBoolean;

/*
 * This task checks if the Cassandra process is running.
 */
@Singleton
public class CassandraMonitor extends Task {

    public static final String JOBNAME = "CASS_MONITOR_THREAD";
    private static final Logger logger = LoggerFactory.getLogger(CassandraMonitor.class);
    private static final AtomicBoolean isCassandraStarted = new AtomicBoolean(false);
    private InstanceState instanceState;
    private ICassandraProcess cassProcess;
    private RateLimiter startRateLimiter;

    @Inject
    protected CassandraMonitor(IConfiguration config, InstanceState instanceState, ICassandraProcess cassProcess) {
        super(config);
        this.instanceState = instanceState;
        this.cassProcess = cassProcess;
        startRateLimiter = RateLimiter.create(1.0);
    }

    @Override
    public void execute() throws Exception {
        try{
            checkRequiredDirectories();
            instanceState.setIsRequiredDirectoriesExist(true);
        }catch (IllegalStateException e)
        {
            instanceState.setIsRequiredDirectoriesExist(false);
        }

        Process process = null;
        BufferedReader input = null;
        try {
            // This returns pid for the Cassandra process
            // This needs to be sent as command list as "pipe" of results is not allowed. Also, do not try to change
            // with pgrep as it has limitation of 4K command list (cassandra command can go upto 5-6 KB as cassandra lists all the libraries in command.
            final String[] cmd = { "/bin/sh", "-c", "ps -ef |grep -v -P \"\\sgrep\\s\" | grep " + config.getCassProcessName()};
            process = Runtime.getRuntime().exec(cmd);
            input = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line = input.readLine();
            if (line != null) {
                //Setting cassandra flag to true
                instanceState.setCassandraProcessAlive(true);
                instanceState.setShouldCassandraBeAlive(true);
                isCassandraStarted.set(true);
                NodeProbe bean = JMXNodeTool.instance(this.config);
                instanceState.setIsGossipActive(bean.isGossipRunning());
                instanceState.setIsNativeTransportActive(bean.isNativeTransportRunning());
                instanceState.setIsThriftActive(bean.isThriftServerRunning());
            } else if (line == null) {
                //Setting cassandra flag to false
                instanceState.setCassandraProcessAlive(false);
                isCassandraStarted.set(false);
            }
        } catch (Exception e) {
            logger.warn("Exception thrown while checking if Cassandra is running or not ", e);
            instanceState.setCassandraProcessAlive(false);
            isCassandraStarted.set(false);
        } finally {
            if (process != null) {
                IOUtils.closeQuietly(process.getInputStream());
                IOUtils.closeQuietly(process.getOutputStream());
                IOUtils.closeQuietly(process.getErrorStream());
            }

            if (input != null)
                IOUtils.closeQuietly(input);
        }

        try {
            int rate = config.getRemediateDeadCassandraRate();
            if (rate >= 0) {
                if (instanceState.shouldCassandraBeAlive() && !instanceState.isCassandraProcessAlive()) {
                    if (rate == 0 || startRateLimiter.tryAcquire(rate)) {
                        cassProcess.start(true);
                    }
                }
            }
        } catch (IOException e) {
            logger.warn("Failed to remediate dead Cassandra", e);
        }
    }

    private void checkRequiredDirectories() {
        checkDirectory(config.getDataFileLocation());
        checkDirectory(config.getBackupCommitLogLocation());
        checkDirectory(config.getCommitLogLocation());
        checkDirectory(config.getCacheLocation());
        checkDirectory(config.getHintsLocation());
        checkDirectory(config.getLogDirLocation());
    }

    private void checkDirectory(String directory) {
        checkDirectory(new File(directory));
    }

    private void checkDirectory(File directory) {
        if (!directory.exists())
            throw new IllegalStateException(String.format("Directory: {} does not exist", directory));

        if (!directory.canRead() || !directory.canWrite())
            throw new IllegalStateException(String.format("Directory: {} does not have read/write permissions."));
    }

    public static TaskTimer getTimer() {
        return new SimpleTimer(JOBNAME, 10L * 1000);
    }

    @Override
    public String getName() {
        return JOBNAME;
    }

    public static Boolean isCassadraStarted() {
        return isCassandraStarted.get();
    }

    //Added for testing only
    public static void setIsCassadraStarted() {
        //Setting cassandra flag to true
        isCassandraStarted.set(true);
    }

}
