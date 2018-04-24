/*
 * Copyright 2011 Google Inc.
 * Copyright 2016 Andreas Schildbach
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

package co.usc.ulordj.testing;

import co.usc.ulordj.core.*;
import co.usc.ulordj.crypto.TransactionSignature;
import co.usc.ulordj.script.ScriptBuilder;
import co.usc.ulordj.store.UldBlockStore;
import co.usc.ulordj.store.BlockStoreException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Random;

import static co.usc.ulordj.core.Coin.*;
import static com.google.common.base.Preconditions.checkState;

public class FakeTxBuilder {
    /** Create a fake transaction, without change. */
    public static UldTransaction createFakeTx(final NetworkParameters params) {
        return createFakeTxWithoutChangeAddress(params, Coin.COIN, new UldECKey().toAddress(params));
    }

    /** Create a fake transaction, without change. */
    public static UldTransaction createFakeTxWithoutChange(final NetworkParameters params, final TransactionOutput output) {
        UldTransaction prevTx = FakeTxBuilder.createFakeTx(params, Coin.COIN, new UldECKey().toAddress(params));
        UldTransaction tx = new UldTransaction(params);
        tx.addOutput(output);
        tx.addInput(prevTx.getOutput(0));
        return tx;
    }

    /** Create a fake coinbase transaction. */
    public static UldTransaction createFakeCoinbaseTx(final NetworkParameters params) {
        TransactionOutPoint outpoint = new TransactionOutPoint(params, -1, Sha256Hash.ZERO_HASH);
        TransactionInput input = new TransactionInput(params, null, new byte[0], outpoint);
        UldTransaction tx = new UldTransaction(params);
        tx.addInput(input);
        TransactionOutput outputToMe = new TransactionOutput(params, tx, Coin.FIFTY_COINS,
                new UldECKey().toAddress(params));
        tx.addOutput(outputToMe);

        checkState(tx.isCoinBase());
        return tx;
    }

    /**
     * Create a fake TX of sufficient realism to exercise the unit tests. Two outputs, one to us, one to somewhere
     * else to simulate change. There is one random input.
     */
    public static UldTransaction createFakeTxWithChangeAddress(NetworkParameters params, Coin value, Address to, Address changeOutput) {
        UldTransaction t = new UldTransaction(params);
        TransactionOutput outputToMe = new TransactionOutput(params, t, value, to);
        t.addOutput(outputToMe);
        TransactionOutput change = new TransactionOutput(params, t, valueOf(1, 11), changeOutput);
        t.addOutput(change);
        // Make a previous tx simply to send us sufficient coins. This prev tx is not really valid but it doesn't
        // matter for our purposes.
        UldTransaction prevTx = new UldTransaction(params);
        TransactionOutput prevOut = new TransactionOutput(params, prevTx, value, to);
        prevTx.addOutput(prevOut);
        // Connect it.
        t.addInput(prevOut).setScriptSig(ScriptBuilder.createInputScript(TransactionSignature.dummy()));
        // Fake signature.
        // Serialize/deserialize to ensure internal state is stripped, as if it had been read from the wire.
        return roundTripTransaction(params, t);
    }

    /**
     * Create a fake TX for unit tests, for use with unit tests that need greater control. One outputs, 2 random inputs,
     * split randomly to create randomness.
     */
    public static UldTransaction createFakeTxWithoutChangeAddress(NetworkParameters params, Coin value, Address to) {
        UldTransaction t = new UldTransaction(params);
        TransactionOutput outputToMe = new TransactionOutput(params, t, value, to);
        t.addOutput(outputToMe);

        // Make a random split in the output value so we get a distinct hash when we call this multiple times with same args
        long split = new Random().nextLong();
        if (split < 0) { split *= -1; }
        if (split == 0) { split = 15; }
        while (split > value.getValue()) {
            split /= 2;
        }

        // Make a previous tx simply to send us sufficient coins. This prev tx is not really valid but it doesn't
        // matter for our purposes.
        UldTransaction prevTx1 = new UldTransaction(params);
        TransactionOutput prevOut1 = new TransactionOutput(params, prevTx1, Coin.valueOf(split), to);
        prevTx1.addOutput(prevOut1);
        // Connect it.
        t.addInput(prevOut1).setScriptSig(ScriptBuilder.createInputScript(TransactionSignature.dummy()));
        // Fake signature.

        // Do it again
        UldTransaction prevTx2 = new UldTransaction(params);
        TransactionOutput prevOut2 = new TransactionOutput(params, prevTx2, Coin.valueOf(value.getValue() - split), to);
        prevTx2.addOutput(prevOut2);
        t.addInput(prevOut2).setScriptSig(ScriptBuilder.createInputScript(TransactionSignature.dummy()));

        // Serialize/deserialize to ensure internal state is stripped, as if it had been read from the wire.
        return roundTripTransaction(params, t);
    }

    /**
     * Create a fake TX of sufficient realism to exercise the unit tests. Two outputs, one to us, one to somewhere
     * else to simulate change. There is one random input.
     */
    public static UldTransaction createFakeTx(NetworkParameters params, Coin value, Address to) {
        return createFakeTxWithChangeAddress(params, value, to, new UldECKey().toAddress(params));
    }

    /**
     * Create a fake TX of sufficient realism to exercise the unit tests. Two outputs, one to us, one to somewhere
     * else to simulate change. There is one random input.
     */
    public static UldTransaction createFakeTx(NetworkParameters params, Coin value, UldECKey to) {
        UldTransaction t = new UldTransaction(params);
        TransactionOutput outputToMe = new TransactionOutput(params, t, value, to);
        t.addOutput(outputToMe);
        TransactionOutput change = new TransactionOutput(params, t, valueOf(1, 11), new UldECKey());
        t.addOutput(change);
        // Make a previous tx simply to send us sufficient coins. This prev tx is not really valid but it doesn't
        // matter for our purposes.
        UldTransaction prevTx = new UldTransaction(params);
        TransactionOutput prevOut = new TransactionOutput(params, prevTx, value, to);
        prevTx.addOutput(prevOut);
        // Connect it.
        t.addInput(prevOut);
        // Serialize/deserialize to ensure internal state is stripped, as if it had been read from the wire.
        return roundTripTransaction(params, t);
    }

    /**
     * Transaction[0] is a feeder transaction, supplying BTC to Transaction[1]
     */
    public static UldTransaction[] createFakeTx(NetworkParameters params, Coin value,
                                                Address to, Address from) {
        // Create fake TXes of sufficient realism to exercise the unit tests. This transaction send BTC from the
        // from address, to the to address with to one to somewhere else to simulate change.
        UldTransaction t = new UldTransaction(params);
        TransactionOutput outputToMe = new TransactionOutput(params, t, value, to);
        t.addOutput(outputToMe);
        TransactionOutput change = new TransactionOutput(params, t, valueOf(1, 11), new UldECKey().toAddress(params));
        t.addOutput(change);
        // Make a feeder tx that sends to the from address specified. This feeder tx is not really valid but it doesn't
        // matter for our purposes.
        UldTransaction feederTx = new UldTransaction(params);
        TransactionOutput feederOut = new TransactionOutput(params, feederTx, value, from);
        feederTx.addOutput(feederOut);

        // make a previous tx that sends from the feeder to the from address
        UldTransaction prevTx = new UldTransaction(params);
        TransactionOutput prevOut = new TransactionOutput(params, prevTx, value, to);
        prevTx.addOutput(prevOut);

        // Connect up the txes
        prevTx.addInput(feederOut);
        t.addInput(prevOut);

        // roundtrip the tx so that they are just like they would be from the wire
        return new UldTransaction[]{roundTripTransaction(params, prevTx), roundTripTransaction(params,t)};
    }

    /**
     * Roundtrip a transaction so that it appears as if it has just come from the wire
     */
    public static UldTransaction roundTripTransaction(NetworkParameters params, UldTransaction tx) {
        try {
            MessageSerializer bs = params.getDefaultSerializer();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bs.serialize(tx, bos);
            return (UldTransaction) bs.deserialize(ByteBuffer.wrap(bos.toByteArray()));
        } catch (IOException e) {
            throw new RuntimeException(e);   // Should not happen.
        }
    }

    public static class DoubleSpends {
        public UldTransaction t1, t2, prevTx;
    }

    /**
     * Creates two transactions that spend the same (fake) output. t1 spends to "to". t2 spends somewhere else.
     * The fake output goes to the same address as t2.
     */
    public static DoubleSpends createFakeDoubleSpendTxns(NetworkParameters params, Address to) {
        DoubleSpends doubleSpends = new DoubleSpends();
        Coin value = COIN;
        Address someBadGuy = new UldECKey().toAddress(params);

        doubleSpends.prevTx = new UldTransaction(params);
        TransactionOutput prevOut = new TransactionOutput(params, doubleSpends.prevTx, value, someBadGuy);
        doubleSpends.prevTx.addOutput(prevOut);

        doubleSpends.t1 = new UldTransaction(params);
        TransactionOutput o1 = new TransactionOutput(params, doubleSpends.t1, value, to);
        doubleSpends.t1.addOutput(o1);
        doubleSpends.t1.addInput(prevOut);

        doubleSpends.t2 = new UldTransaction(params);
        doubleSpends.t2.addInput(prevOut);
        TransactionOutput o2 = new TransactionOutput(params, doubleSpends.t2, value, someBadGuy);
        doubleSpends.t2.addOutput(o2);

        try {
            doubleSpends.t1 = params.getDefaultSerializer().makeTransaction(doubleSpends.t1.bitcoinSerialize());
            doubleSpends.t2 = params.getDefaultSerializer().makeTransaction(doubleSpends.t2.bitcoinSerialize());
        } catch (ProtocolException e) {
            throw new RuntimeException(e);
        }
        return doubleSpends;
    }

    public static class BlockPair {
        public StoredBlock storedBlock;
        public UldBlock block;
    }

    /** Emulates receiving a valid block that builds on top of the chain. */
    public static BlockPair createFakeBlock(UldBlockStore blockStore, long version,
                                            long timeSeconds, UldTransaction... transactions) {
        return createFakeBlock(blockStore, version, timeSeconds, 0, transactions);
    }

    /** Emulates receiving a valid block */
    public static BlockPair createFakeBlock(UldBlockStore blockStore, StoredBlock previousStoredBlock, long version,
                                            long timeSeconds, int height,
                                            UldTransaction... transactions) {
        try {
            UldBlock previousBlock = previousStoredBlock.getHeader();
            Address to = new UldECKey().toAddress(previousBlock.getParams());
            UldBlock b = previousBlock.createNextBlock(to, version, timeSeconds, height);
            // Coinbase tx was already added.
            for (UldTransaction tx : transactions) {
                b.addTransaction(tx);
            }
            b.solve();
            BlockPair pair = new BlockPair();
            pair.block = b;
            pair.storedBlock = previousStoredBlock.build(b);
            blockStore.put(pair.storedBlock);
            blockStore.setChainHead(pair.storedBlock);
            return pair;
        } catch (VerificationException e) {
            throw new RuntimeException(e);  // Cannot happen.
        } catch (BlockStoreException e) {
            throw new RuntimeException(e);  // Cannot happen.
        }
    }

    public static BlockPair createFakeBlock(UldBlockStore blockStore, StoredBlock previousStoredBlock, int height, UldTransaction... transactions) {
        return createFakeBlock(blockStore, previousStoredBlock, UldBlock.BLOCK_VERSION_BIP66, Utils.currentTimeSeconds(), height, transactions);
    }

    /** Emulates receiving a valid block that builds on top of the chain. */
    public static BlockPair createFakeBlock(UldBlockStore blockStore, long version, long timeSeconds, int height, UldTransaction... transactions) {
        try {
            return createFakeBlock(blockStore, blockStore.getChainHead(), version, timeSeconds, height, transactions);
        } catch (BlockStoreException e) {
            throw new RuntimeException(e);  // Cannot happen.
        }
    }

    /** Emulates receiving a valid block that builds on top of the chain. */
    public static BlockPair createFakeBlock(UldBlockStore blockStore, int height,
                                            UldTransaction... transactions) {
        return createFakeBlock(blockStore, UldBlock.BLOCK_VERSION_GENESIS, Utils.currentTimeSeconds(), height, transactions);
    }

    /** Emulates receiving a valid block that builds on top of the chain. */
    public static BlockPair createFakeBlock(UldBlockStore blockStore, UldTransaction... transactions) {
        return createFakeBlock(blockStore, UldBlock.BLOCK_VERSION_GENESIS, Utils.currentTimeSeconds(), 0, transactions);
    }

    public static UldBlock makeSolvedTestBlock(UldBlockStore blockStore, Address coinsTo) throws BlockStoreException {
        UldBlock b = blockStore.getChainHead().getHeader().createNextBlock(coinsTo);
        b.solve();
        return b;
    }

    public static UldBlock makeSolvedTestBlock(UldBlock prev, UldTransaction... transactions) throws BlockStoreException {
        Address to = new UldECKey().toAddress(prev.getParams());
        UldBlock b = prev.createNextBlock(to);
        // Coinbase tx already exists.
        for (UldTransaction tx : transactions) {
            b.addTransaction(tx);
        }
        b.solve();
        return b;
    }

    public static UldBlock makeSolvedTestBlock(UldBlock prev, Address to, UldTransaction... transactions) throws BlockStoreException {
        UldBlock b = prev.createNextBlock(to);
        // Coinbase tx already exists.
        for (UldTransaction tx : transactions) {
            b.addTransaction(tx);
        }
        b.solve();
        return b;
    }
}
