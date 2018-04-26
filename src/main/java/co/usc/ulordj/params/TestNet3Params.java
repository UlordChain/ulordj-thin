/*
 * Copyright 2013 Google Inc.
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

package co.usc.ulordj.params;

import java.math.BigInteger;
import java.util.Date;

import co.usc.ulordj.core.UldBlock;
import co.usc.ulordj.core.NetworkParameters;
import co.usc.ulordj.core.StoredBlock;
import co.usc.ulordj.core.Utils;
import co.usc.ulordj.core.VerificationException;
import co.usc.ulordj.store.UldBlockStore;
import co.usc.ulordj.store.BlockStoreException;
import co.usc.ulordj.core.Sha256Hash;
import static com.google.common.base.Preconditions.checkState;

/**
 * Parameters for the testnet, a separate public instance of ulord that has relaxed rules suitable for development
 * and testing of applications and new ulord versions.
 */
public class TestNet3Params extends AbstractUlordNetParams {
    public TestNet3Params() {
        super(ID_TESTNET);
        //id = ID_TESTNET;
        // Genesis hash is 000e0979b2a26db104fb4d8c2c8d572919a56662cecdcadc3d0583ac8d548e23
        packetMagic = 0xC2E6CEF3;
        interval = INTERVAL;
        targetTimespan = TARGET_TIMESPAN;
        maxTarget = Utils.decodeCompactBits(503951731L);
        port = 19888;           // Ulord Testnet port
        addressHeader = 125;    // Ulord Testnet script address start with 's'
        p2shHeader = 130;       // Ulord Testnet addresses start with 'u'
        acceptableAddressCodes = new int[] { addressHeader, p2shHeader };
        dumpedPrivateKeyHeader = 239;   // Ulord Testnet private keys start with either '9' or 'c' (same as bitcoin)
        //genesisBlock.setTime(1520308246L);
        genesisBlock.setTime(1524057440L);
        genesisBlock.setDifficultyTarget(521142271L);
        genesisBlock.setNonce(new BigInteger("000020f00dd1af082323e02e1f5b1d866d777abbcf63ba720d35dcf585840073", 16)); //"a12949fc4a1735c8cbd6444bf9b4aea61300bc7aee9fec741af5a8c2fe386216"

        spendableCoinbaseDepth = 100;
        subsidyDecreaseBlockCount = 840960;

        String genesisHash = genesisBlock.getHashAsString();

        checkState(genesisHash.equals("000f378be841f44e75346eebd931b13041f0dee561af6a80cfea6669c1bfec03"));

        dnsSeeds = new String[] {
                "testnet-seed1.ulord.one",
                "testnet-seed1.ulord.io",
                "testnet-seed1.fcash.cc",
                "node.ulord.one",
        };
        addrSeeds = null;
        bip32HeaderPub = 0x043587CF;
        bip32HeaderPriv = 0x04358394;
        bip44HeaderCoin = 0x80000001;

        majorityEnforceBlockUpgrade = TestNet2Params.TESTNET_MAJORITY_ENFORCE_BLOCK_UPGRADE;
        majorityRejectBlockOutdated = TestNet2Params.TESTNET_MAJORITY_REJECT_BLOCK_OUTDATED;
        majorityWindow = TestNet2Params.TESTNET_MAJORITY_WINDOW;
    }

    private static TestNet3Params instance;
    public static synchronized TestNet3Params get() {
        if (instance == null) {
            instance = new TestNet3Params();
        }
        return instance;
    }

    @Override
    public String getPaymentProtocolId() {
        return PAYMENT_PROTOCOL_ID_TESTNET;
    }

    // February 16th 2012
    private static final Date testnetDiffDate = new Date(1329264000000L);

    @Override
    public void checkDifficultyTransitions(final StoredBlock storedPrev, final UldBlock nextBlock,
        final UldBlockStore blockStore) throws VerificationException, BlockStoreException {
        if (!isDifficultyTransitionPoint(storedPrev) && nextBlock.getTime().after(testnetDiffDate)) {
            UldBlock prev = storedPrev.getHeader();

            // After 15th February 2012 the rules on the testnet change to avoid people running up the difficulty
            // and then leaving, making it too hard to mine a block. On non-difficulty transition points, easy
            // blocks are allowed if there has been a span of 20 minutes without one.
            final long timeDelta = nextBlock.getTimeSeconds() - prev.getTimeSeconds();
            // There is an integer underflow bug in bitcoin-qt that means mindiff blocks are accepted when time
            // goes backwards.
            if (timeDelta >= 0 && timeDelta <= NetworkParameters.TARGET_SPACING * 2) {
        	// Walk backwards until we find a block that doesn't have the easiest proof of work, then check
        	// that difficulty is equal to that one.
        	StoredBlock cursor = storedPrev;
        	while (!cursor.getHeader().equals(getGenesisBlock()) &&
                       cursor.getHeight() % getInterval() != 0 &&
                       cursor.getHeader().getDifficultyTargetAsInteger().equals(getMaxTarget()))
                    cursor = cursor.getPrev(blockStore);
        	BigInteger cursorTarget = cursor.getHeader().getDifficultyTargetAsInteger();
        	BigInteger newTarget = nextBlock.getDifficultyTargetAsInteger();
        	if (!cursorTarget.equals(newTarget))
                    throw new VerificationException("Testnet block transition that is not allowed: " +
                	Long.toHexString(cursor.getHeader().getDifficultyTarget()) + " vs " +
                	Long.toHexString(nextBlock.getDifficultyTarget()));
            }
        } else {
            super.checkDifficultyTransitions(storedPrev, nextBlock, blockStore);
        }
    }
}
