/*
 * Copyright 2011 Noa Resare
 * Copyright 2014 Andreas Schildbach
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package co.usc.ulordj.core;

import co.usc.ulordj.params.MainNetParams;
import co.usc.ulordj.params.TestNet3Params;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.Arrays;

import static co.usc.ulordj.core.Utils.HEX;
import static org.junit.Assert.*;

public class UlordSerializerTest {
    private static final byte[] ADDRESS_MESSAGE_BYTES = HEX.decode("f9beb4d96164647200000000000000001f000000" +
            "ed52399b01e215104d010000000000000000000000000000000000ffff0a000001208d");

    private static final byte[] TRANSACTION_MESSAGE_BYTES = HEX.withSeparator(" ", 2).decode(
            "c2 e6 ce f3 74 78 00 00  00 00 00 00 00 00 00 00" +
                    "99 00 00 00 39 a9 f3 4d  01 00 00 00 01 00 00 00" +
                    "00 00 00 00 00 00 00 00  00 00 00 00 00 00 00 00" +
                    "00 00 00 00 00 00 00 00  00 00 00 00 00 ff ff ff" +
                    "ff 22 02 e9 03 04 c5 da  d9 5a 19 2f 74 65 73 74" +
                    "6e 65 74 2d 70 6f 6f 6c  32 2e 75 6c 6f 72 64 2e" +
                    "6f 6e 65 2f 00 00 00 00  02 50 b6 98 9a 02 00 00" +
                    "00 19 76 a9 14 10 98 a6  ed 76 a6 01 87 4a ac 92" +
                    "b3 82 07 62 1b e5 6f 8e  70 88 ac 70 b9 bb 06 00" +
                    "00 00 00 19 76 a9 14 78  85 41 a7 f2 0b 86 32 8c" +
                    "eb 93 5e 9a 28 4a 35 ef  58 25 97 88 ac 00 00 00" +
                    "00");

    @Test
    public void testCachedParsing() throws Exception {
        MessageSerializer serializer = TestNet3Params.get().getSerializer(true);
        
        // first try writing to a fields to ensure uncaching and children are not affected
        UldTransaction transaction = (UldTransaction) serializer.deserialize(ByteBuffer.wrap(TRANSACTION_MESSAGE_BYTES));
        assertNotNull(transaction);
        assertTrue(transaction.isCached());

        transaction.setLockTime(1);
        // parent should have been uncached
        assertFalse(transaction.isCached());
        // child should remain cached.
        assertTrue(transaction.getInputs().get(0).isCached());

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        serializer.serialize(transaction, bos);
        assertFalse(Arrays.equals(TRANSACTION_MESSAGE_BYTES, bos.toByteArray()));

        // now try writing to a child to ensure uncaching is propagated up to parent but not to siblings
        transaction = (UldTransaction) serializer.deserialize(ByteBuffer.wrap(TRANSACTION_MESSAGE_BYTES));
        assertNotNull(transaction);
        assertTrue(transaction.isCached());

        transaction.getInputs().get(0).setSequenceNumber(1);
        // parent should have been uncached
        assertFalse(transaction.isCached());
        // so should child
        assertFalse(transaction.getInputs().get(0).isCached());

        bos = new ByteArrayOutputStream();
        serializer.serialize(transaction, bos);
        assertFalse(Arrays.equals(TRANSACTION_MESSAGE_BYTES, bos.toByteArray()));

        // deserialize/reserialize to check for equals.
        transaction = (UldTransaction) serializer.deserialize(ByteBuffer.wrap(TRANSACTION_MESSAGE_BYTES));
        assertNotNull(transaction);
        assertTrue(transaction.isCached());
        bos = new ByteArrayOutputStream();
        serializer.serialize(transaction, bos);
        assertTrue(Arrays.equals(TRANSACTION_MESSAGE_BYTES, bos.toByteArray()));

        // deserialize/reserialize to check for equals.  Set a field to it's existing value to trigger uncache
        transaction = (UldTransaction) serializer.deserialize(ByteBuffer.wrap(TRANSACTION_MESSAGE_BYTES));
        assertNotNull(transaction);
        assertTrue(transaction.isCached());

        transaction.getInputs().get(0).setSequenceNumber(transaction.getInputs().get(0).getSequenceNumber());

        bos = new ByteArrayOutputStream();
        serializer.serialize(transaction, bos);
        assertTrue(Arrays.equals(TRANSACTION_MESSAGE_BYTES, bos.toByteArray()));
    }

   @Test(expected = BufferUnderflowException.class)
    public void testUlordPacketHeaderTooShort() {
        new UlordSerializer.UlordPacketHeader(ByteBuffer.wrap(new byte[] { 0 }));
    }

    @Test(expected = ProtocolException.class)
    public void testUlordPacketHeaderTooLong() {
        // Message with a Message size which is 1 too big, in little endian format.
        byte[] wrongMessageLength = HEX.decode("000000000000000000000000010000020000000000");
        new UlordSerializer.UlordPacketHeader(ByteBuffer.wrap(wrongMessageLength));
    }

    @Test(expected = BufferUnderflowException.class)
    public void testSeekPastMagicBytes() {
        // Fail in another way, there is data in the stream but no magic bytes.
        byte[] brokenMessage = HEX.decode("000000");
        TestNet3Params.get().getDefaultSerializer().seekPastMagicBytes(ByteBuffer.wrap(brokenMessage));
    }

    /**
     * Tests serialization of an unknown message.
     */
    @Test(expected = Error.class)
    public void testSerializeUnknownMessage() throws Exception {
        MessageSerializer serializer = TestNet3Params.get().getDefaultSerializer();

        Message unknownMessage = new Message() {
            @Override
            protected void parse() throws ProtocolException {
            }
        };
        ByteArrayOutputStream bos = new ByteArrayOutputStream(ADDRESS_MESSAGE_BYTES.length);
        serializer.serialize(unknownMessage, bos);
    }
}
