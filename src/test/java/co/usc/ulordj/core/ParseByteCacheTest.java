/*
 * Copyright 2011 Steve Coughlan.
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
import co.usc.ulordj.params.UnitTestParams;
import co.usc.ulordj.store.UldBlockStore;
import co.usc.ulordj.store.UldMemoryBlockStore;
import co.usc.ulordj.wallet.Wallet;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.Arrays;

import static co.usc.ulordj.core.Coin.*;
import static co.usc.ulordj.core.Utils.HEX;
import static co.usc.ulordj.testing.FakeTxBuilder.createFakeBlock;
import static co.usc.ulordj.testing.FakeTxBuilder.createFakeTx;
import static org.junit.Assert.*;

public class ParseByteCacheTest {
    private static final int BLOCK_HEIGHT_GENESIS = 0;

    //Ulord Transaction.
    private final byte[] txMessage = HEX.withSeparator(" ", 2).decode(
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

    private final byte[] txMessagePart = HEX.withSeparator(" ", 2).decode(
                "ff 22 02 e9 03 04 c5 da  d9 5a 19 2f 74 65 73 74" +
                "6e 65 74 2d 70 6f 6f 6c  32 2e 75 6c 6f 72 64 2e" +
                "6f 6e 65 2f 00 00 00 00  02 50 b6 98 9a 02 00 00" +
                "00 19 76 a9 14 10 98 a6  ed 76 a6 01 87 4a ac 92");

    private UldBlockStore blockStore;
    private static final NetworkParameters PARAMS = UnitTestParams.get();
    
    private byte[] b1Bytes;
    private byte[] b1BytesWithHeader;
    
    private byte[] tx1Bytes;
    private byte[] tx1BytesWithHeader;
    
    private byte[] tx2Bytes;
    private byte[] tx2BytesWithHeader;

    private void resetBlockStore() {
        blockStore = new UldMemoryBlockStore(PARAMS);
    }
    
    @Before
    public void setUp() throws Exception {
        Context context = new Context(PARAMS);
        Wallet wallet = new Wallet(context);

        resetBlockStore();
        
        UldTransaction tx1 = createFakeTx(PARAMS,
                valueOf(2, 0),
                new UldECKey().toAddress(PARAMS));

        // add a second input so can test granularity of byte cache.
        UldTransaction prevTx = new UldTransaction(PARAMS);
        TransactionOutput prevOut = new TransactionOutput(PARAMS, prevTx, COIN, new UldECKey().toAddress(PARAMS));
        prevTx.addOutput(prevOut);
        // Connect it.
        tx1.addInput(prevOut);
        
        UldTransaction tx2 = createFakeTx(PARAMS, COIN,
                new UldECKey().toAddress(PARAMS));

        UldBlock b1 = createFakeBlock(blockStore, BLOCK_HEIGHT_GENESIS, tx1, tx2).block;

        MessageSerializer bs = PARAMS.getDefaultSerializer();
        
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bs.serialize(tx1, bos);
        tx1BytesWithHeader = bos.toByteArray();
        tx1Bytes = tx1.ulordSerialize();
        
        bos.reset();
        bs.serialize(tx2, bos);
        tx2BytesWithHeader = bos.toByteArray();
        tx2Bytes = tx2.ulordSerialize();
        
        bos.reset();
        bs.serialize(b1, bos);
        b1BytesWithHeader = bos.toByteArray();
        b1Bytes = b1.ulordSerialize();
    }
    
    @Test
    public void validateSetup() {
        byte[] b1 = {1, 1, 1, 2, 3, 4, 5, 6, 7};
        byte[] b2 = {1, 2, 3};
        assertTrue(arrayContains(b1, b2));
        assertTrue(arrayContains(txMessage, txMessagePart));
        assertTrue(arrayContains(tx1BytesWithHeader, tx1Bytes));
        assertTrue(arrayContains(tx2BytesWithHeader, tx2Bytes));
        assertTrue(arrayContains(b1BytesWithHeader, b1Bytes));
        assertTrue(arrayContains(b1BytesWithHeader, tx1Bytes));
        assertTrue(arrayContains(b1BytesWithHeader, tx2Bytes));
        assertFalse(arrayContains(tx1BytesWithHeader, b1Bytes));
    }
    
    @Test
    public void testTransactionsRetain() throws Exception {
        testTransaction(TestNet3Params.get(), txMessage, false, true);
        testTransaction(PARAMS, tx1BytesWithHeader, false, true);
        testTransaction(PARAMS, tx2BytesWithHeader, false, true);
    }
    
    @Test
    public void testTransactionsNoRetain() throws Exception {
        testTransaction(TestNet3Params.get(), txMessage, false, false);
        testTransaction(PARAMS, tx1BytesWithHeader, false, false);
        testTransaction(PARAMS, tx2BytesWithHeader, false, false);
    }

    @Test
    public void testBlockAll() throws Exception {
        testBlock(b1BytesWithHeader, false, false);
        testBlock(b1BytesWithHeader, false, true);
    }

    @Test
    public void testCreateTransactionFromBytes() throws Exception {
        byte[] txData = HEX.decode("01000000010000000000000000000000000000000000000000000000000000000000000000ffffffff2202e90304c5dad95a192f746573746e65742d706f6f6c322e756c6f72642e6f6e652f000000000250b6989a020000001976a9141098a6ed76a601874aac92b38207621be56f8e7088ac70b9bb06000000001976a914788541a7f20b86328ceb935e9a284a35ef58259788ac00000000");
        UldTransaction tx = new UldTransaction(TestNet3Params.get(), txData);
        UlordSerializer bs = PARAMS.getSerializer(true);

        System.out.println(tx.toString());
        OutputStream stream = new ByteArrayOutputStream();
        bs.serialize( tx, stream);
        System.out.println(Sha256Hash.bytesToHex(((ByteArrayOutputStream) stream).toByteArray()).toLowerCase());
    }


    public void testBlock(byte[] blockBytes, boolean isChild, boolean retain) throws Exception {
        // reference serializer to produce comparison serialization output after changes to
        // message structure.
        MessageSerializer bsRef = PARAMS.getSerializer(false);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        
        UlordSerializer bs = PARAMS.getSerializer(retain);
        UldBlock b1;
        UldBlock bRef;
        b1 = (UldBlock) bs.deserialize(ByteBuffer.wrap(blockBytes));
        bRef = (UldBlock) bsRef.deserialize(ByteBuffer.wrap(blockBytes));
        
        // verify our reference UlordSerializer produces matching byte array.
        bos.reset();
        bsRef.serialize(bRef, bos);
        assertTrue(Arrays.equals(bos.toByteArray(), blockBytes));
        
        // check retain status survive both before and after a serialization
        assertEquals(retain, b1.isHeaderBytesValid());
        assertEquals(retain, b1.isTransactionBytesValid());
        
        serDeser(bs, b1, blockBytes, null, null);
        
        assertEquals(retain, b1.isHeaderBytesValid());
        assertEquals(retain, b1.isTransactionBytesValid());
        
        // compare to ref block
        bos.reset();
        bsRef.serialize(bRef, bos);
        serDeser(bs, b1, bos.toByteArray(), null, null);
        
        // retrieve a value from a child
        b1.getTransactions();
        if (b1.getTransactions().size() > 0) {
            UldTransaction tx1 = b1.getTransactions().get(0);
            
            // this will always be true for all children of a block once they are retrieved.
            // the tx child inputs/outputs may not be parsed however.
            
            assertEquals(retain, tx1.isCached());
            
            // does it still match ref block?
            serDeser(bs, b1, bos.toByteArray(), null, null);
        }
        
        // refresh block
        b1 = (UldBlock) bs.deserialize(ByteBuffer.wrap(blockBytes));
        bRef = (UldBlock) bsRef.deserialize(ByteBuffer.wrap(blockBytes));
        
        // retrieve a value from header
        b1.getDifficultyTarget();
        
        // does it still match ref block?
        serDeser(bs, b1, bos.toByteArray(), null, null);
        
        // refresh block
        b1 = (UldBlock) bs.deserialize(ByteBuffer.wrap(blockBytes));
        bRef = (UldBlock) bsRef.deserialize(ByteBuffer.wrap(blockBytes));
        
        // retrieve a value from a child and header
        b1.getDifficultyTarget();

        b1.getTransactions();
        if (b1.getTransactions().size() > 0) {
            UldTransaction tx1 = b1.getTransactions().get(0);
            
            assertEquals(retain, tx1.isCached());
        }
        // does it still match ref block?
        serDeser(bs, b1, bos.toByteArray(), null, null);
        
        // refresh block
        b1 = (UldBlock) bs.deserialize(ByteBuffer.wrap(blockBytes));
        bRef = (UldBlock) bsRef.deserialize(ByteBuffer.wrap(blockBytes));

        // change a value in header
        b1.setNonce(new BigInteger("23"));
        bRef.setNonce(new BigInteger("23"));
        assertFalse(b1.isHeaderBytesValid());
        assertEquals(retain , b1.isTransactionBytesValid());
        // does it still match ref block?
        bos.reset();
        bsRef.serialize(bRef, bos);
        serDeser(bs, b1, bos.toByteArray(), null, null);
        
        // refresh block
        b1 = (UldBlock) bs.deserialize(ByteBuffer.wrap(blockBytes));
        bRef = (UldBlock) bsRef.deserialize(ByteBuffer.wrap(blockBytes));
        
        // retrieve a value from a child of a child
        b1.getTransactions();
        if (b1.getTransactions().size() > 0) {
            UldTransaction tx1 = b1.getTransactions().get(0);
            
            TransactionInput tin = tx1.getInputs().get(0);
            
            assertEquals(retain, tin.isCached());
            
            // does it still match ref tx?
            bos.reset();
            bsRef.serialize(bRef, bos);
            serDeser(bs, b1, bos.toByteArray(), null, null);
        }
        
        // refresh block
        b1 = (UldBlock) bs.deserialize(ByteBuffer.wrap(blockBytes));
        bRef = (UldBlock) bsRef.deserialize(ByteBuffer.wrap(blockBytes));
        
        // add an input
        b1.getTransactions();
        if (b1.getTransactions().size() > 0) {
            UldTransaction tx1 = b1.getTransactions().get(0);
            
            if (tx1.getInputs().size() > 0) {
                tx1.addInput(tx1.getInputs().get(0));
                // replicate on reference tx
                bRef.getTransactions().get(0).addInput(bRef.getTransactions().get(0).getInputs().get(0));
                
                assertFalse(tx1.isCached());
                assertFalse(b1.isTransactionBytesValid());
                
                // confirm sibling cache status was unaffected
                if (tx1.getInputs().size() > 1) {
                    assertEquals(retain, tx1.getInputs().get(1).isCached());
                }
                
                // this has to be false. Altering a tx invalidates the merkle root.
                // when we have seperate merkle caching then the entire header won't need to be
                // invalidated.
                assertFalse(b1.isHeaderBytesValid());
                
                bos.reset();
                bsRef.serialize(bRef, bos);
                byte[] source = bos.toByteArray();
                // confirm we still match the reference tx.
                serDeser(bs, b1, source, null, null);
            }
            
            // does it still match ref tx?
            bos.reset();
            bsRef.serialize(bRef, bos);
            serDeser(bs, b1, bos.toByteArray(), null, null);
        }
        
        // refresh block
        b1 = (UldBlock) bs.deserialize(ByteBuffer.wrap(blockBytes));
        UldBlock b2 = (UldBlock) bs.deserialize(ByteBuffer.wrap(blockBytes));
        bRef = (UldBlock) bsRef.deserialize(ByteBuffer.wrap(blockBytes));
        UldBlock bRef2 = (UldBlock) bsRef.deserialize(ByteBuffer.wrap(blockBytes));
        
        // reparent an input
        b1.getTransactions();
        if (b1.getTransactions().size() > 0) {
            UldTransaction tx1 = b1.getTransactions().get(0);
            UldTransaction tx2 = b2.getTransactions().get(0);
            
            if (tx1.getInputs().size() > 0) {
                TransactionInput fromTx1 = tx1.getInputs().get(0);
                tx2.addInput(fromTx1);
                
                // replicate on reference tx
                TransactionInput fromTxRef = bRef.getTransactions().get(0).getInputs().get(0);
                bRef2.getTransactions().get(0).addInput(fromTxRef);
                
                // b1 hasn't changed but it's no longer in the parent
                // chain of fromTx1 so has to have been uncached since it won't be
                // notified of changes throught the parent chain anymore.
                assertFalse(b1.isTransactionBytesValid());
                
                // b2 should have it's cache invalidated because it has changed.
                assertFalse(b2.isTransactionBytesValid());
                
                bos.reset();
                bsRef.serialize(bRef2, bos);
                byte[] source = bos.toByteArray();
                // confirm altered block matches altered ref block.
                serDeser(bs, b2, source, null, null);
            }
            
            // does unaltered block still match ref block?
            bos.reset();
            bsRef.serialize(bRef, bos);
            serDeser(bs, b1, bos.toByteArray(), null, null);

            // how about if we refresh it?
            bRef = (UldBlock) bsRef.deserialize(ByteBuffer.wrap(blockBytes));
            bos.reset();
            bsRef.serialize(bRef, bos);
            serDeser(bs, b1, bos.toByteArray(), null, null);
        }
    }
    
    public void testTransaction(NetworkParameters params, byte[] txBytes, boolean isChild, boolean retain) throws Exception {

        // reference serializer to produce comparison serialization output after changes to
        // message structure.
        MessageSerializer bsRef = params.getSerializer(false);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();

        UlordSerializer bs = params.getSerializer(retain);
        UldTransaction t1;
        UldTransaction tRef;
        t1 = (UldTransaction) bs.deserialize(ByteBuffer.wrap(txBytes));
        tRef = (UldTransaction) bsRef.deserialize(ByteBuffer.wrap(txBytes));

        // verify our reference UlordSerializer produces matching byte array.
        bos.reset();
        bsRef.serialize(tRef, bos);
        assertTrue(Arrays.equals(bos.toByteArray(), txBytes));

        // check and retain status survive both before and after a serialization
        assertEquals(retain, t1.isCached());

        serDeser(bs, t1, txBytes, null, null);

        assertEquals(retain, t1.isCached());

        // compare to ref tx
        bos.reset();
        bsRef.serialize(tRef, bos);
        serDeser(bs, t1, bos.toByteArray(), null, null);
        
        // retrieve a value from a child
        t1.getInputs();
        if (t1.getInputs().size() > 0) {
            TransactionInput tin = t1.getInputs().get(0);
            assertEquals(retain, tin.isCached());
            
            // does it still match ref tx?
            serDeser(bs, t1, bos.toByteArray(), null, null);
        }
        
        // refresh tx
        t1 = (UldTransaction) bs.deserialize(ByteBuffer.wrap(txBytes));
        tRef = (UldTransaction) bsRef.deserialize(ByteBuffer.wrap(txBytes));
        
        // add an input
        if (t1.getInputs().size() > 0) {

            t1.addInput(t1.getInputs().get(0));

            // replicate on reference tx
            tRef.addInput(tRef.getInputs().get(0));

            assertFalse(t1.isCached());

            bos.reset();
            bsRef.serialize(tRef, bos);
            byte[] source = bos.toByteArray();
            //confirm we still match the reference tx.
            serDeser(bs, t1, source, null, null);
        }
    }
    
    private void serDeser(MessageSerializer bs, Message message, byte[] sourceBytes, byte[] containedBytes, byte[] containingBytes) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bs.serialize(message, bos);
        byte[] b1 = bos.toByteArray();
        
        Message m2 = bs.deserialize(ByteBuffer.wrap(b1));

        assertEquals(message, m2);

        bos.reset();
        bs.serialize(m2, bos);
        byte[] b2 = bos.toByteArray(); 
        assertTrue(Arrays.equals(b1, b2));

        if (sourceBytes != null) {
            assertTrue(arrayContains(sourceBytes, b1));
            
            assertTrue(arrayContains(sourceBytes, b2));
        }

        if (containedBytes != null) {
            assertTrue(arrayContains(b1, containedBytes));
        }
        if (containingBytes != null) {
            assertTrue(arrayContains(containingBytes, b1));
        }
    }
    
    public static boolean arrayContains(byte[] sup, byte[] sub) {
        if (sup.length < sub.length)
            return false;       
        
        String superstring = Utils.HEX.encode(sup);
        String substring = Utils.HEX.encode(sub);
        
        int ind = superstring.indexOf(substring);
        
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < superstring.indexOf(substring); i++)
            sb.append(" ");
        
        //System.out.println(superstring);
        //System.out.println(sb.append(substring).toString());
        //System.out.println();
        return ind > -1;
    }
}
