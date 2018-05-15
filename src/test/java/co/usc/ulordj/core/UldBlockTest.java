/*
 * Copyright 2011 Google Inc.
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

import com.google.common.io.ByteStreams;

import co.usc.ulordj.params.MainNetParams;
import co.usc.ulordj.params.TestNet3Params;
import co.usc.ulordj.params.UnitTestParams;
import co.usc.ulordj.script.ScriptOpCodes;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.security.interfaces.ECKey;
import java.util.Arrays;
import java.util.EnumSet;

import static co.usc.ulordj.core.Utils.HEX;
import static org.junit.Assert.*;

public class UldBlockTest {
    //private static final NetworkParameters PARAMS = TestNet2Params.get();
    private static final NetworkParameters PARAMS = TestNet3Params.get();

    public static final byte[] blockBytes;

    static {
        // Block 00000000a6e5eb79dcec11897af55e90cd571a4335383a3ccfbc12ec81085935
        // One with lots of transactions in, so a good test of the merkle tree hashing.
        blockBytes = HEX.decode("00000020035e1f326d6666a05051104cc554aed79870d21af3423014edfaf416e8010000eb7458144e4a8e02cba446c61118adbe78173f543974fa3e79eeb82db5a4f9a600000000000000000000000000000000000000000000000000000000000000007a43f15ac6b4021ef9170080feca044a3b35c04026cb9eceb824ab3af3d1738650c750585c77d5570201000000010000000000000000000000000000000000000000000000000000000000000000ffffffff2102cf2b047a43f15a182f746573746e65742d706f6f6c2e756c6f72642e6f6e652f0000000002c01cee9b020000001976a914ee66199f1a7de9397ac19c465240aa2e7f7d7a5488ac402cbf06000000001976a914788541a7f20b86328ceb935e9a284a35ef58259788ac0000000001000000014b333cafff4f05e5c394495434091439ee402443fecfaff26047027fd8a9926e010000006b483045022100a30f1813593f0c9237d75fec9a254b6e6cba0a5b13042831f3aa749d81ff2f0f02205a1c32c5fa16e9039fdec081fbc606e7343cacee29d784f80cf9233c8061f8f201210387019a798cafd1210c3288b3c2ef67d0579b513e0baf5fe2b253c03cd41cbf91ffffffff0200c2eb0b000000001976a914c0e17e7fa243b68035cb44ecf3afd3a28fdc6d6988acc0a95029170000001976a914943f17b48d37f1da48e9850510ba96aa699260fd88ac00000000");
    }

    @Before
    public void setUp() throws Exception {
        Context context = new Context(PARAMS);
    }

    @Test
    public void testReadUint256() throws Exception {
        byte[] testBytes = Sha256Hash.hexStringToByteArray("FFFF0F1F");
        BigInteger data = Utils.readUint256(testBytes,0);
        assertEquals("521142271", data.toString());
    }

    @Test
    public void testUint256ToByteStreamLE() throws Exception {
        BigInteger value = new BigInteger("000020f00dd1af082323e02e1f5b1d866d777abbcf63ba720d35dcf585840073", 16);
        ByteArrayOutputStream stream = new UnsafeByteArrayOutputStream(value.bitLength());
        Utils.uint256ToByteStreamLE(value, stream);
        System.out.println(value.toString());
    }

    @Test
    public void testWork() throws Exception {
        BigInteger work = PARAMS.getGenesisBlock().getWork();
        // This number is printed by Ulord Core at startup as the calculated value of chainWork on testnet:
        //
        // SetBestChain: new best=000f378be841f44e75346eebd931b13041f0dee561af6a80cfea6669c1bfec03  height=0  work=4096
        assertEquals(BigInteger.valueOf(4096L), work);
    }

    @Test
    public void testBlockVerification() throws Exception {
        UldBlock block = PARAMS.getDefaultSerializer().makeBlock(blockBytes);
        block.verify(UldBlock.BLOCK_HEIGHT_GENESIS, EnumSet.noneOf(UldBlock.VerifyFlag.class));
        assertEquals("0000005fec80847c2f821b5454233deae31e25bc13bc1180680e7391b155e5b3", block.getHashAsString());
    }
    
    @SuppressWarnings("deprecation")
    @Test
    public void testDate() throws Exception {
        UldBlock block = PARAMS.getDefaultSerializer().makeBlock(blockBytes);
        assertEquals("8 May 2018 06:28:10 GMT", block.getTime().toGMTString()); // 8 May 2018 06:28:10 GMT GMT+8
    }

    @Test
    public void testProofOfWork() throws Exception {
        // This params accepts any difficulty target.
        NetworkParameters params = UnitTestParams.get();
        UldBlock block = params.getDefaultSerializer().makeBlock(blockBytes);
        //System.out.println(block.toString());
        block.setNonce(new BigInteger("12346"));
        try {
            block.verify(UldBlock.BLOCK_HEIGHT_GENESIS, EnumSet.noneOf(UldBlock.VerifyFlag.class));
            fail();
        } catch (VerificationException e) {
            // Expected.
        }
        // Blocks contain their own difficulty target. The BlockChain verification mechanism is what stops real blocks
        // from containing artificially weak difficulties.
        block.setDifficultyTarget(UldBlock.EASIEST_DIFFICULTY_TARGET);
        // Now it should pass.
        block.verify(UldBlock.BLOCK_HEIGHT_GENESIS, EnumSet.noneOf(UldBlock.VerifyFlag.class));
        // Break the nonce again at the lower difficulty level so we can try solving for it.
        block.setNonce(new BigInteger("1"));
        try {
            block.verify(UldBlock.BLOCK_HEIGHT_GENESIS, EnumSet.noneOf(UldBlock.VerifyFlag.class));
            fail();
        } catch (VerificationException e) {
            // Expected to fail as the nonce is no longer correct.
        }
        // Should find an acceptable nonce.
        block.solve();
        block.verify(UldBlock.BLOCK_HEIGHT_GENESIS, EnumSet.noneOf(UldBlock.VerifyFlag.class));
        assertEquals(new BigInteger("5"), block.getNonce());
    }

    @Test
    public void testBadTransactions() throws Exception {
        UldBlock block = PARAMS.getDefaultSerializer().makeBlock(blockBytes);
        // Re-arrange so the coinbase transaction is not first.
        UldTransaction tx1 = block.transactions.get(0);
        UldTransaction tx2 = block.transactions.get(1);
        block.transactions.set(0, tx2);
        block.transactions.set(1, tx1);
        try {
            block.verify(UldBlock.BLOCK_HEIGHT_GENESIS, EnumSet.noneOf(UldBlock.VerifyFlag.class));
            fail();
        } catch (VerificationException e) {
            // We should get here.
        }
    }

    @Test
    public void testHeaderParse() throws Exception {
        UldBlock block = PARAMS.getDefaultSerializer().makeBlock(blockBytes);
        UldBlock header = block.cloneAsHeader();
        UldBlock reparsed = PARAMS.getDefaultSerializer().makeBlock(header.ulordSerialize());
        assertEquals(reparsed, header);
    }

    @Test
    public void testUlordSerialization() throws Exception {
        // We have to be able to reserialize everything exactly as we found it for hashing to work. This test also
        // proves that transaction serialization works, along with all its subobjects like scripts and in/outpoints.
        //
        // NB: This tests the bitcoin serialization protocol.
        UldBlock block = PARAMS.getDefaultSerializer().makeBlock(blockBytes);
        System.out.println(Sha256Hash.bytesToHex(blockBytes));
        System.out.println(Sha256Hash.bytesToHex(block.ulordSerialize()));
        assertTrue(Arrays.equals(blockBytes, block.ulordSerialize()));
    }
    
    @Test
    public void testUpdateLength() {
        NetworkParameters params = UnitTestParams.get();
        UldBlock block = params.getGenesisBlock().createNextBlockWithCoinbase(UldBlock.BLOCK_VERSION_GENESIS, new UldECKey().getPubKey(), UldBlock.BLOCK_HEIGHT_GENESIS);
        assertEquals(block.ulordSerialize().length, block.length);
        final int origBlockLen = block.length;
        UldTransaction tx = new UldTransaction(params);
        // this is broken until the transaction has > 1 input + output (which is required anyway...)
        //assertTrue(tx.length == tx.ulordSerialize().length && tx.length == 8);
        byte[] outputScript = new byte[10];
        Arrays.fill(outputScript, (byte) ScriptOpCodes.OP_FALSE);
        tx.addOutput(new TransactionOutput(params, null, Coin.SATOSHI, outputScript));
        tx.addInput(new TransactionInput(params, null, new byte[] {(byte) ScriptOpCodes.OP_FALSE},
                new TransactionOutPoint(params, 0, Sha256Hash.of(new byte[] { 1 }))));
        int origTxLength = 8 + 2 + 8 + 1 + 10 + 40 + 1 + 1;
        assertEquals(tx.unsafeUlordSerialize().length, tx.length);
        assertEquals(origTxLength, tx.length);
        block.addTransaction(tx);
        assertEquals(block.unsafeUlordSerialize().length, block.length);
        assertEquals(origBlockLen + tx.length, block.length);
        block.getTransactions().get(1).getInputs().get(0).setScriptBytes(new byte[] {(byte) ScriptOpCodes.OP_FALSE, (byte) ScriptOpCodes.OP_FALSE});
        assertEquals(block.length, origBlockLen + tx.length);
        assertEquals(tx.length, origTxLength + 1);
        block.getTransactions().get(1).getInputs().get(0).clearScriptBytes();
        assertEquals(block.length, block.unsafeUlordSerialize().length);
        assertEquals(block.length, origBlockLen + tx.length);
        assertEquals(tx.length, origTxLength - 1);
        block.getTransactions().get(1).addInput(new TransactionInput(params, null, new byte[] {(byte) ScriptOpCodes.OP_FALSE},
                new TransactionOutPoint(params, 0, Sha256Hash.of(new byte[] { 1 }))));
        assertEquals(block.length, origBlockLen + tx.length);
        assertEquals(tx.length, origTxLength + 41); // - 1 + 40 + 1 + 1
    }

    @Test
    public void testCoinbaseHeightTestnet() throws Exception {
        // TODO: Create a .dat file for this test
        // Testnet block 11215 (hash 0000005fec80847c2f821b5454233deae31e25bc13bc1180680e7391b155e5b3)
        // contains a coinbase transaction whose height is two bytes, which is
        // shorter than we see in most other cases.
        UldBlock block = TestNet3Params.get().getDefaultSerializer().makeBlock(
            ByteStreams.toByteArray(getClass().getResourceAsStream("block_testnet11215.dat")));

        // Check block.
        assertEquals("0000005fec80847c2f821b5454233deae31e25bc13bc1180680e7391b155e5b3", block.getHashAsString());
        block.verify(11215, EnumSet.of(UldBlock.VerifyFlag.HEIGHT_IN_COINBASE));


        // Testnet block 1001 (hash 0000021bb15ff80345d788f3796f1aba35dd70fc2b1fc1d1269391bea0c280dd)
        // contains a coinbase transaction whose height is three bytes, but could
        // fit in two bytes. This test primarily ensures script encoding checks
        // are applied correctly.

        UldBlock block1 = TestNet3Params.get().getDefaultSerializer().makeBlock(
            ByteStreams.toByteArray(getClass().getResourceAsStream("block_testnet1001.dat")));

        // Check block.
        assertEquals("0000021bb15ff80345d788f3796f1aba35dd70fc2b1fc1d1269391bea0c280dd", block1.getHashAsString());
        block1.verify(1001, EnumSet.of(UldBlock.VerifyFlag.HEIGHT_IN_COINBASE));
    }


    @Test
    public void isBIPs() throws Exception {
//        final MainNetParams mainnet = MainNetParams.get();
//        final UldBlock genesis = mainnet.getGenesisBlock();
//        assertFalse(genesis.isBIP34());
//        assertFalse(genesis.isBIP66());
//        assertFalse(genesis.isBIP65());
//
//        // 227835/00000000000001aa077d7aa84c532a4d69bdbff519609d1da0835261b7a74eb6: last version 1 block
//        final UldBlock block227835 = mainnet.getDefaultSerializer()
//                .makeBlock(ByteStreams.toByteArray(getClass().getResourceAsStream("block227835.dat")));
//        assertFalse(block227835.isBIP34());
//        assertFalse(block227835.isBIP66());
//        assertFalse(block227835.isBIP65());
//
//        // 227836/00000000000000d0dfd4c9d588d325dce4f32c1b31b7c0064cba7025a9b9adcc: version 2 block
//        final UldBlock block227836 = mainnet.getDefaultSerializer()
//                .makeBlock(ByteStreams.toByteArray(getClass().getResourceAsStream("block227836.dat")));
//        assertTrue(block227836.isBIP34());
//        assertFalse(block227836.isBIP66());
//        assertFalse(block227836.isBIP65());
//
//        // 363703/0000000000000000011b2a4cb91b63886ffe0d2263fd17ac5a9b902a219e0a14: version 3 block
//        final UldBlock block363703 = mainnet.getDefaultSerializer()
//                .makeBlock(ByteStreams.toByteArray(getClass().getResourceAsStream("block363703.dat")));
//        assertTrue(block363703.isBIP34());
//        assertTrue(block363703.isBIP66());
//        assertFalse(block363703.isBIP65());
//
//        // 383616/00000000000000000aab6a2b34e979b09ca185584bd1aecf204f24d150ff55e9: version 4 block
//        final UldBlock block383616 = mainnet.getDefaultSerializer()
//                .makeBlock(ByteStreams.toByteArray(getClass().getResourceAsStream("block383616.dat")));
//        assertTrue(block383616.isBIP34());
//        assertTrue(block383616.isBIP66());
//        assertTrue(block383616.isBIP65());
//
//        // 370661/00000000000000001416a613602d73bbe5c79170fd8f39d509896b829cf9021e: voted for BIP101
//        final UldBlock block370661 = mainnet.getDefaultSerializer()
//                .makeBlock(ByteStreams.toByteArray(getClass().getResourceAsStream("block370661.dat")));
//        assertTrue(block370661.isBIP34());
//        assertTrue(block370661.isBIP66());
//        assertTrue(block370661.isBIP65());
    }

    @Test
    public void verifyBlockHeader() {
        //UldBlock block = new UldBlock(PARAMS, Sha256Hash.hexStringToByteArray("00000020050fbd12443f15673ed34818fd907283a9daa9c75d8b5f65894c5432400100006cae35ec3ca92e365af79f71777609b5ac4b4d71d7eeb8f76979288327b59b2500000000000000000000000000000000000000000000000000000000000000009047f55a7579011e45eaf22dc74bbe0a362808cb6ee378cdf280978b2fca8f6382317a9f9767a6e2"));
        /*  Block Number 12978
        {
            "hash": "000000caa0de82a7fdf68334ac4b61ae516161c44f668a82e4fd38bc06a6b0c3",
            "confirmations": 2172,
            "height": 12978,
            "version": 536870912,
            "merkleroot": "259bb52783287969f7b8eed7714d4bacb5097677719ff75a362ea93cec35ae6c",
            "nameclaimroot": "0000000000000000000000000000000000000000000000000000000000000000",
            "time": 1526024080,
            "mediantime": 1526022611,
            "nonce": "e2a667979f7a3182638fca2f8b9780f2cd78e36ecb0828360abe4bc72df2ea45",
            "bits": "1e017975",
            "difficulty": 0.002649267753469456,
            "chainwork": "0000000000000000000000000000000000000000000000000000000e5bb8a171",
            "previousblockhash": "0000014032544c89655f8b5dc7a9daa9837290fd1848d33e67153f4412bd0f05",
            "nextblockhash": "0000000918eda46e070a88731c0f8b32892af58c06726b8925aec35825c79a28"
        } */

        UldBlock block = new UldBlock(PARAMS, Sha256Hash.hexStringToByteArray("00000020050fbd12443f15673ed34818fd907283a9daa9c75d8b5f65894c5432400100006cae35ec3ca92e365af79f71777609b5ac4b4d71d7eeb8f76979288327b59b2500000000000000000000000000000000000000000000000000000000000000009047f55a7579011e45eaf22dc74bbe0a362808cb6ee378cdf280978b2fca8f6382317a9f9767a6e2"));

        System.out.println("Is block valid: " + block.isHeaderBytesValid());
        System.out.println(block.toString());

    }


    @Test
    public void testReadWrite() {
        byte[] blockBytes = HEX.decode("000000202d41b7147b772270d51478689401a8dfb6f200995b4f581082719e583003000039a9f34dc2d754d4f9362925d2fcb183fbd99ba1b3e164f663b7479a4a1929e90000000000000000000000000000000000000000000000000000000000000000c5dad95a17a4071e9b0000205f7d538980edbf9e181439c21a559b2653268c4767c7fac6be8172bd0101000000010000000000000000000000000000000000000000000000000000000000000000ffffffff2202e90304c5dad95a192f746573746e65742d706f6f6c322e756c6f72642e6f6e652f000000000250b6989a020000001976a9141098a6ed76a601874aac92b38207621be56f8e7088ac70b9bb06000000001976a914788541a7f20b86328ceb935e9a284a35ef58259788ac00000000");
        writeBlockToDat(blockBytes, "block_testnet1001.dat");
    }

    public void writeBlockToDat(byte[] data, String filename)
    {
        try {
            OutputStream out = new FileOutputStream(filename);
            // write a byte sequence
            out.write(data);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
