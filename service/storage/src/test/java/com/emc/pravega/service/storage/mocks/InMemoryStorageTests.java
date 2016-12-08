/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.emc.pravega.service.storage.mocks;

import com.emc.pravega.service.storage.Storage;
import com.emc.pravega.testcommon.AssertExtensions;
import lombok.Cleanup;
import lombok.val;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.util.concurrent.TimeUnit;

/**
 * Unit tests for InMemoryStorage
 */
public class InMemoryStorageTests extends StorageTestBase {
    /**
     * Verifies that InMemoryStorage enforces segment ownership (that is, if an owner changes, no operation is allowed
     * on a segment until open() is called on it).
     */
    @Test
    public void testChangeOwner() throws Exception {
        final String segment1 = "segment1";
        final String segment2 = "segment2";

        @Cleanup
        val storage = new InMemoryStorage();

        // Part 1: Create a segment and verify all operations are allowed.
        storage.create(segment1, TIMEOUT).get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        verifyOperationsSucceed(segment1, storage);

        // Part 2: Change owner, verify segment operations are not allowed until a call to open() is made.
        storage.changeOwner();
        verifyAllOperationsFail(segment1, storage);

        storage.open(segment1);
        verifyOperationsSucceed(segment1, storage);

        // Part 3: Create new segment and verify all operations are allowed.
        storage.create(segment2, TIMEOUT).get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        verifyOperationsSucceed(segment2, storage);

        // Cleanup.
        storage.delete(segment1, TIMEOUT).get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        storage.delete(segment2, TIMEOUT).get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void verifyAllOperationsFail(String segmentName, Storage storage) {
        final byte[] writeData = "hello".getBytes();

        // GetInfo
        AssertExtensions.assertThrows(
                "getStreamSegmentInfo did not throw for non-owned Segment",
                () -> storage.getStreamSegmentInfo(segmentName, TIMEOUT),
                ex -> ex instanceof IllegalStateException);

        // Write
        AssertExtensions.assertThrows(
                "write did not throw for non-owned Segment",
                () -> storage.write(segmentName, 0, new ByteArrayInputStream(writeData), writeData.length, TIMEOUT),
                ex -> ex instanceof IllegalStateException);

        // Seal
        AssertExtensions.assertThrows(
                "seal did not throw for non-owned Segment",
                () -> storage.seal(segmentName, TIMEOUT),
                ex -> ex instanceof IllegalStateException);

        // Read
        byte[] readBuffer = new byte[1];
        AssertExtensions.assertThrows(
                "read() did not throw for non-owned Segment",
                () -> storage.read(segmentName, 0, readBuffer, 0, readBuffer.length, TIMEOUT),
                ex -> ex instanceof IllegalStateException);
    }

    private void verifyOperationsSucceed(String segmentName, Storage storage) throws Exception {
        final byte[] writeData = "hello".getBytes();

        // GetInfo
        val si = storage.getStreamSegmentInfo(segmentName, TIMEOUT).get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

        // Write
        storage.write(segmentName, si.getLength(), new ByteArrayInputStream(writeData), writeData.length, TIMEOUT).get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

        // Read
        byte[] readBuffer = new byte[(int) si.getLength()];
        storage.read(segmentName, 0, readBuffer, 0, readBuffer.length, TIMEOUT).get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    protected Storage createStorage() {
        return new InMemoryStorage();
    }
}