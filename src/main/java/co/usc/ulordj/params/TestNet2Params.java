/*
 * Copyright 2013 Google Inc.
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

import co.usc.ulordj.core.Utils;

import java.math.BigInteger;

import static com.google.common.base.Preconditions.checkState;

/**
 * Parameters for the old version 2 testnet. This is not useful to you - it exists only because some unit tests are
 * based on it.
 */
public class TestNet2Params extends AbstractUlordNetParams {
    public static final int TESTNET_MAJORITY_WINDOW = 100;
    public static final int TESTNET_MAJORITY_REJECT_BLOCK_OUTDATED = 75;
    public static final int TESTNET_MAJORITY_ENFORCE_BLOCK_UPGRADE = 51;

    public TestNet2Params() {
        // This network parameter is not used.
        super(ID_TESTNET);
        packetMagic = 0xfabfb5daL;
        port = 19888;
        addressHeader = 125;
        p2shHeader = 130;
        acceptableAddressCodes = new int[] { addressHeader, p2shHeader };
        interval = INTERVAL;
        targetTimespan = TARGET_TIMESPAN;
        maxTarget = new BigInteger("000fffffff000000000000000000000000000000000000000000000000000000", 16);
        dumpedPrivateKeyHeader = 239;
        genesisBlock.setTime(1520308246L);
        genesisBlock.setDifficultyTarget(521142271L);
        //genesisBlock.setNonce(439);
        spendableCoinbaseDepth = 100;
        subsidyDecreaseBlockCount = 840960;
        String genesisHash = genesisBlock.getHashAsString();
        checkState(genesisHash.equals("11a853c1fbdc86145b695268ab469e2104b57629270b4aee00c7848c67e44f57"));
        //checkState(genesisHash.equals("00000007199508e34a9ff81e6ec0c477a4cccff2a4767a8eee39c11db367b008"));
        dnsSeeds = null;
        addrSeeds = null;
        bip32HeaderPub = 0x043587CF;    // Ulord BIP32 pubkeys start with 'xpub' (Bitcoin defaults)
        bip32HeaderPriv = 0x04358394;   // Ulord BIP32 prvkeys start with 'xprv' (Bitcoin defaults)
        bip44HeaderCoin = 0x80000001;   // Ulord BIP44 coin type is '247'

        majorityEnforceBlockUpgrade = TESTNET_MAJORITY_ENFORCE_BLOCK_UPGRADE;
        majorityRejectBlockOutdated = TESTNET_MAJORITY_REJECT_BLOCK_OUTDATED;
        majorityWindow = TESTNET_MAJORITY_WINDOW;
    }

    private static TestNet2Params instance;
    public static synchronized TestNet2Params get() {
        if (instance == null) {
            instance = new TestNet2Params();
        }
        return instance;
    }

    @Override
    public String getPaymentProtocolId() {
        return null;
    }
}
