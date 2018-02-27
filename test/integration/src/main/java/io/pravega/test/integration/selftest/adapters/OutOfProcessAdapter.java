/**
 * Copyright (c) 2017 Dell Inc., or its subsidiaries. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.pravega.test.integration.selftest.adapters;

import com.google.common.base.Preconditions;
import io.pravega.common.Exceptions;
import io.pravega.common.io.FileHelpers;
import io.pravega.common.lang.ProcessStarter;
import io.pravega.common.util.Property;
import io.pravega.segmentstore.server.host.ServiceStarter;
import io.pravega.segmentstore.server.host.stat.AutoScalerConfig;
import io.pravega.segmentstore.server.store.ServiceBuilderConfig;
import io.pravega.segmentstore.server.store.ServiceConfig;
import io.pravega.segmentstore.storage.impl.bookkeeper.BookKeeperConfig;
import io.pravega.segmentstore.storage.impl.bookkeeper.BookKeeperServiceRunner;
import io.pravega.segmentstore.storage.impl.bookkeeper.ZooKeeperServiceRunner;
import io.pravega.segmentstore.storage.impl.filesystem.FileSystemStorageConfig;
import io.pravega.shared.metrics.MetricsConfig;
import io.pravega.test.integration.selftest.TestConfig;
import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.bookkeeper.util.IOUtils;

/**
 * Store adapter wrapping a real Pravega Client targeting a local cluster out-of-process. This class creates a new Pravega
 * Cluster made up of a SegmentStore, Controller, ZooKeeper and BookKeeper, and using local FileSystem as Tier2 Storage.
 */
class OutOfProcessAdapter extends ExternalAdapter {
    //region Members

    private static final int PROCESS_SHUTDOWN_TIMEOUT_MILLIS = 10 * 1000;
    private final ServiceBuilderConfig builderConfig;
    private final AtomicReference<Process> zooKeeperProcess;
    private final AtomicReference<Process> bookieProcess;
    private final List<Process> segmentStoreProcesses;
    private final List<Process> controllerProcesses;
    private final AtomicReference<File> segmentStoreRoot;
    private final Thread destroyChildProcesses;

    //endregion

    //region Constructor

    /**
     * Creates a new instance of the ClientAdapterBase class.
     *
     * @param testConfig    The TestConfig to use.
     * @param builderConfig SegmentStore Builder Configuration.
     * @param testExecutor  An ExecutorService used by the Test Application.
     */
    OutOfProcessAdapter(TestConfig testConfig, ServiceBuilderConfig builderConfig, ScheduledExecutorService testExecutor) {
        super(testConfig, testExecutor);
        Preconditions.checkArgument(testConfig.getBookieCount() > 0, "OutOfProcessAdapter requires at least one Bookie.");
        Preconditions.checkArgument(testConfig.getControllerHost().equals(TestConfig.LOCALHOST),
                "OutOfProcessAdapter cannot work with non-local Controller.");
        this.builderConfig = Preconditions.checkNotNull(builderConfig, "builderConfig");
        this.zooKeeperProcess = new AtomicReference<>();
        this.bookieProcess = new AtomicReference<>();
        this.segmentStoreProcesses = Collections.synchronizedList(new ArrayList<>());
        this.controllerProcesses = Collections.synchronizedList(new ArrayList<>());
        this.segmentStoreRoot = new AtomicReference<>();

        // Make sure the child processes and any created files get killed/deleted if the process is terminated.
        this.destroyChildProcesses = new Thread(this::destroyExternalComponents);
        Runtime.getRuntime().addShutdownHook(this.destroyChildProcesses);
    }

    //endregion

    //region ClientAdapterBase and StorageAdapter Implementation

    @Override
    protected void startUp() throws Exception {
        try {
            startZooKeeper();
            startBookKeeper();
            startAllControllers();
            // TODO: There is no way to figure out when the Controller or SegmentStore services are up. Until we have that,
            // we will need to wait some arbitrary time between these calls.
            Thread.sleep(3000);
            startAllSegmentStores();
            Thread.sleep(3000);
        } catch (Throwable ex) {
            if (!Exceptions.mustRethrow(ex)) {
                close();
            }

            throw ex;
        }

        super.startUp();
    }

    @Override
    protected void shutDown() {
        super.shutDown();

        // Stop all services.
        destroyExternalComponents();
        Runtime.getRuntime().removeShutdownHook(this.destroyChildProcesses);
    }

    private void destroyExternalComponents() {
        // Stop all services.
        int controllerCount = stopProcesses(this.controllerProcesses);
        log("Controller(s) (%d count) shut down.", controllerCount);
        int segmentStoreCount = stopProcesses(this.segmentStoreProcesses);
        log("SegmentStore(s) (%d count) shut down.", segmentStoreCount);
        stopProcess(this.bookieProcess);
        log("Bookies shut down.");
        stopProcess(this.zooKeeperProcess);
        log("ZooKeeper shut down.");

        // Delete temporary files and directories.
        delete(this.segmentStoreRoot);
    }

    //endregion

    //region Services Startup/Shutdown

    private void startZooKeeper() throws Exception {
        Preconditions.checkState(this.zooKeeperProcess.get() == null, "ZooKeeper is already started.");
        this.zooKeeperProcess.set(ProcessStarter
                .forClass(ZooKeeperServiceRunner.class)
                .sysProp(ZooKeeperServiceRunner.PROPERTY_ZK_PORT, this.testConfig.getZkPort())
                .stdOut(ProcessBuilder.Redirect.to(new File(this.testConfig.getComponentOutLogPath("zk", 0))))
                .stdErr(ProcessBuilder.Redirect.to(new File(this.testConfig.getComponentErrLogPath("zk", 0))))
                .start());

        if (!ZooKeeperServiceRunner.waitForServerUp(this.testConfig.getZkPort(), timeout)) {
            throw new RuntimeException("Unable to start ZooKeeper at port " + this.testConfig.getZkPort());
        }

        log("ZooKeeper started (Port = %s).", this.testConfig.getZkPort());
    }

    private void startBookKeeper() throws IOException {
        Preconditions.checkState(this.bookieProcess.get() == null, "Bookies are already started.");
        int bookieCount = this.testConfig.getBookieCount();
        this.bookieProcess.set(ProcessStarter
                .forClass(BookKeeperServiceRunner.class)
                .sysProp(BookKeeperServiceRunner.PROPERTY_BASE_PORT, this.testConfig.getBkPort(0))
                .sysProp(BookKeeperServiceRunner.PROPERTY_BOOKIE_COUNT, bookieCount)
                .sysProp(BookKeeperServiceRunner.PROPERTY_ZK_PORT, this.testConfig.getZkPort())
                .sysProp(BookKeeperServiceRunner.PROPERTY_LEDGERS_PATH, TestConfig.BK_LEDGER_PATH)
                .stdOut(ProcessBuilder.Redirect.to(new File(this.testConfig.getComponentOutLogPath("bk", 0))))
                .stdErr(ProcessBuilder.Redirect.to(new File(this.testConfig.getComponentErrLogPath("bk", 0))))
                .start());
        log("Bookies started (Count = %s, Ports = [%s-%s])",
                bookieCount, this.testConfig.getBkPort(0), this.testConfig.getBkPort(bookieCount - 1));
    }

    private void startAllControllers() throws IOException {
        Preconditions.checkState(this.controllerProcesses.size() == 0, "At least one Controller is already started.");
        for (int i = 0; i < this.testConfig.getControllerCount(); i++) {
            this.controllerProcesses.add(startController(i));
        }
    }

    private Process startController(int controllerId) throws IOException {
        int port = this.testConfig.getControllerPort(controllerId);
        int restPort = this.testConfig.getControllerRestPort(controllerId);
        int rpcPort = this.testConfig.getControllerRpcPort(controllerId);
        Process p = ProcessStarter
                .forClass(io.pravega.controller.server.Main.class)
                .sysProp("CONTAINER_COUNT", this.testConfig.getContainerCount())
                .sysProp("ZK_URL", getZkUrl())
                .sysProp("CONTROLLER_SERVER_PORT", port)
                .sysProp("REST_SERVER_IP", TestConfig.LOCALHOST)
                .sysProp("REST_SERVER_PORT", restPort)
                .sysProp("CONTROLLER_RPC_PUBLISHED_HOST", TestConfig.LOCALHOST)
                .sysProp("CONTROLLER_RPC_PUBLISHED_PORT", rpcPort)
                .stdOut(ProcessBuilder.Redirect.to(new File(this.testConfig.getComponentOutLogPath("controller", controllerId))))
                .stdErr(ProcessBuilder.Redirect.to(new File(this.testConfig.getComponentErrLogPath("controller", controllerId))))
                .start();
        log("Controller %d started (Port = %d, RestPort = %d, RPCPort = %d).", controllerId, port, restPort, rpcPort);
        return p;
    }

    private void startAllSegmentStores() throws IOException {
        Preconditions.checkState(this.segmentStoreProcesses.size() == 0, "At least one SegmentStore is already started.");
        createSegmentStoreFileSystem();
        for (int i = 0; i < this.testConfig.getSegmentStoreCount(); i++) {
            this.segmentStoreProcesses.add(startSegmentStore(i));
        }
    }

    private Process startSegmentStore(int segmentStoreId) throws IOException {
        int port = this.testConfig.getSegmentStorePort(segmentStoreId);
        ProcessStarter ps = ProcessStarter
                .forClass(ServiceStarter.class)
                .sysProp(ServiceBuilderConfig.CONFIG_FILE_PROPERTY_NAME, getSegmentStoreConfigFilePath())
                .sysProp(configProperty(ServiceConfig.COMPONENT_CODE, ServiceConfig.ZK_URL), getZkUrl())
                .sysProp(configProperty(BookKeeperConfig.COMPONENT_CODE, BookKeeperConfig.ZK_ADDRESS), getZkUrl())
                .sysProp(configProperty(ServiceConfig.COMPONENT_CODE, ServiceConfig.LISTENING_PORT), port)
                .sysProp(configProperty(ServiceConfig.COMPONENT_CODE, ServiceConfig.STORAGE_IMPLEMENTATION), ServiceConfig.StorageType.FILESYSTEM)
                .sysProp(configProperty(FileSystemStorageConfig.COMPONENT_CODE, FileSystemStorageConfig.ROOT), getSegmentStoreStoragePath())
                .sysProp(configProperty(AutoScalerConfig.COMPONENT_CODE, AutoScalerConfig.CONTROLLER_URI), getControllerUrl())
                .stdOut(ProcessBuilder.Redirect.to(new File(this.testConfig.getComponentOutLogPath("segmentStore", segmentStoreId))))
                .stdErr(ProcessBuilder.Redirect.to(new File(this.testConfig.getComponentErrLogPath("segmentStore", segmentStoreId))));
        if (this.testConfig.isMetricsEnabled()) {
            ps.sysProp(configProperty(MetricsConfig.COMPONENT_CODE, MetricsConfig.ENABLE_STATISTICS), true);
            ps.sysProp(configProperty(MetricsConfig.COMPONENT_CODE, MetricsConfig.ENABLE_CSV_REPORTER), true);
            ps.sysProp(configProperty(MetricsConfig.COMPONENT_CODE, MetricsConfig.CSV_ENDPOINT),
                    this.testConfig.getComponentMetricsPath("segmentstore", segmentStoreId));
        }

        Process p = ps.start();
        log("SegmentStore %d started (Port = %d).", segmentStoreId, port);
        return p;
    }

    private void createSegmentStoreFileSystem() throws IOException {
        File rootDir = this.segmentStoreRoot.get();
        if (rootDir == null || !rootDir.exists()) {
            rootDir = IOUtils.createTempDir("selftest.segmentstore.", "");
            rootDir.deleteOnExit();
            this.segmentStoreRoot.set(rootDir);
        }

        // Config file.
        File configFile = new File(getSegmentStoreConfigFilePath());
        configFile.delete();
        configFile.createNewFile();
        log("SegmentStore Config: '%s'.", configFile.getAbsolutePath());

        // Storage.
        File storageDir = new File(getSegmentStoreStoragePath());
        storageDir.delete();
        storageDir.mkdir();
        log("SegmentStore Storage: '%s/'.", storageDir.getAbsolutePath());
        this.builderConfig.store(configFile);
    }

    private void stopProcess(AtomicReference<Process> processReference) {
        Process p = processReference.getAndSet(null);
        if (p != null) {
            p.destroy();
            Exceptions.handleInterrupted(() -> p.waitFor(PROCESS_SHUTDOWN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
        }
    }

    private int stopProcesses(Collection<Process> processList) {
        processList.stream().filter(Objects::nonNull).forEach(p -> {
            p.destroyForcibly();
            Exceptions.handleInterrupted(() -> p.waitFor(PROCESS_SHUTDOWN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
        });

        int count = processList.size();
        processList.clear();
        return count;
    }

    private void delete(AtomicReference<File> fileRef) {
        File f = fileRef.getAndSet(null);
        if (f != null && f.exists()) {
            if (FileHelpers.deleteFileOrDirectory(f)) {
                log("Deleted '%s'.", f.getAbsolutePath());
            }
        }
    }

    private String getZkUrl() {
        return String.format("%s:%d", TestConfig.LOCALHOST, this.testConfig.getZkPort());
    }

    private String configProperty(String componentCode, Property<?> property) {
        return String.format("%s.%s", componentCode, property.getName());
    }

    private String getSegmentStoreConfigFilePath() {
        return Paths.get(this.segmentStoreRoot.get().getAbsolutePath(), "config.props").toString();
    }

    private String getSegmentStoreStoragePath() {
        return Paths.get(this.segmentStoreRoot.get().getAbsolutePath(), "storage").toString();
    }

    //endregion
}
