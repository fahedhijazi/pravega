/**
 * Copyright (c) 2017 Dell Inc., or its subsidiaries. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.pravega.segmentstore.storage.impl.bookkeepertier2;

import com.google.common.base.Preconditions;
import io.pravega.segmentstore.contracts.SegmentProperties;
import io.pravega.segmentstore.contracts.StreamSegmentNotExistsException;
import io.pravega.segmentstore.storage.SegmentHandle;
import io.pravega.segmentstore.storage.Storage;
import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.apache.curator.framework.CuratorFramework;

@Slf4j
public class BookkeeperStorage implements Storage {
    private static final int NUM_RETRIES = 3;

    //region members

    private final BookkeeperStorageConfig config;
    private final ExecutorService executor;
    private final AtomicBoolean closed;
    private final StorageLedgerManager manager;
    private final CuratorFramework zkClient;

    //endregion

    //region constructor

    /**
     * Creates a new instance of the BookkeeperStorage class.
     *
     * @param config   The configuration to use.
     * @param zkClient Curator framework to interact with ZK.
     * @param executor The executor to use for running async operations.
     */
    public BookkeeperStorage(BookkeeperStorageConfig config, CuratorFramework zkClient, ExecutorService executor) {
        Preconditions.checkNotNull(config, "config");
        Preconditions.checkNotNull(executor, "executor");
        this.closed = new AtomicBoolean(false);
        this.config = config;
        this.zkClient = zkClient;
        this.executor = executor;
        manager = new StorageLedgerManager(config, zkClient, executor);
    }

    //endregion

    //region Storage implementation

    /**
     * Initialize is a no op here as we do not need a locking mechanism in case of file system write.
     *
     * @param containerEpoch The Container Epoch to initialize with (ignored here).
     */
    @Override
    public void initialize(long containerEpoch) {
        manager.initialize();
    }

    @Override
    public CompletableFuture<SegmentHandle> openRead(String streamSegmentName) {
        return manager.exists(streamSegmentName, null).thenApply(exist -> {
            if (exist) {
                return BookkeeperSegmentHandle.readHandle(streamSegmentName);
            } else {
                throw new CompletionException(new StreamSegmentNotExistsException(streamSegmentName));
            }
        }).exceptionally((Throwable exception) -> {
            throw new CompletionException(new StreamSegmentNotExistsException(streamSegmentName));
        });
    }

    @Override
    public CompletableFuture<Integer> read(SegmentHandle handle,
                                           long offset,
                                           byte[] buffer,
                                           int bufferOffset,
                                           int length,
                                           Duration timeout) {
        if (offset < 0 || bufferOffset < 0 || length < 0) {
            throw new ArrayIndexOutOfBoundsException();
        }
        if (bufferOffset + length > buffer.length) {
            throw new ArrayIndexOutOfBoundsException();
        }
        return manager.read(handle.getSegmentName(), offset, buffer, bufferOffset, length, timeout);
    }

    @Override
    public CompletableFuture<SegmentProperties> getStreamSegmentInfo(String streamSegmentName, Duration timeout) {
        return manager.getOrRetrieveStorageLedgerDetails(streamSegmentName);
    }

    @Override
    public CompletableFuture<Boolean> exists(String streamSegmentName, Duration timeout) {
       return manager.exists(streamSegmentName, timeout)
                     .thenApply(bool -> bool)
                     .exceptionally(t -> false);
    }

    @Override
    public CompletableFuture<SegmentHandle> openWrite(String streamSegmentName) {
        return manager.fence(streamSegmentName)
                      .thenApply(u -> BookkeeperSegmentHandle.writeHandle(streamSegmentName));
    }

    @Override
    public CompletableFuture<SegmentProperties> create(String streamSegmentName, Duration timeout) {
        return manager.create(streamSegmentName, timeout)
                .thenCompose(str -> this.getStreamSegmentInfo(streamSegmentName, timeout));
    }

    @Override
    public CompletableFuture<Void> write(SegmentHandle handle,
                                         long offset,
                                         InputStream data,
                                         int length,
                                         Duration timeout) {
        Preconditions.checkArgument(!handle.isReadOnly(), "handle must not be read-only.");
        return manager.write(handle.getSegmentName(), offset, data, length);
    }

    @Override
    public CompletableFuture<Void> seal(SegmentHandle handle, Duration timeout) {
        Preconditions.checkArgument(!handle.isReadOnly(), "handle must not be read-only.");
        return manager.seal(handle.getSegmentName());
    }

    @Override
    public CompletableFuture<Void> concat(SegmentHandle targetHandle, long offset, String sourceSegment,
                                          Duration timeout) {
        return manager.concat(targetHandle.getSegmentName(), sourceSegment, offset, timeout)
                .thenCompose(v -> manager.delete(sourceSegment));
    }

    @Override
    public CompletableFuture<Void> delete(SegmentHandle handle, Duration timeout) {
        return manager.delete(handle.getSegmentName());
    }

    //endregion

    //region AutoClosable

    @Override
    public void close() {
        this.closed.set(true);
    }

    //endregion
}