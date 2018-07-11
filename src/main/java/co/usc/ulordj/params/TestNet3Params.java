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

import co.usc.ulordj.core.NetworkParameters;
import co.usc.ulordj.core.UldBlock;
import co.usc.ulordj.core.StoredBlock;
import co.usc.ulordj.core.VerificationException;
import co.usc.ulordj.store.UldBlockStore;
import co.usc.ulordj.store.BlockStoreException;
import com.google.common.base.Stopwatch;

import static com.google.common.base.Preconditions.checkState;

/**
 * Parameters for the testnet, a separate public instance of ulord that has relaxed rules suitable for development
 * and testing of applications and new ulord versions.
 */
public class TestNet3Params extends AbstractUlordNetParams {
    public TestNet3Params() {
        super(ID_TESTNET);
        // Genesis hash is 000f378be841f44e75346eebd931b13041f0dee561af6a80cfea6669c1bfec03
        packetMagic = 0xC2E6CEF3;
        interval = INTERVAL;
        targetTimespan = TARGET_TIMESPAN;
        maxTarget = new BigInteger("000fffffff000000000000000000000000000000000000000000000000000000", 16);
        port = 19888;           // Ulord Testnet port
        addressHeader = 130;    // Ulord Testnet addresses start with 'u' - PUBKEY_ADDRESS
        p2shHeader = 125;       // Ulord Testnet script address start with 's' - SCRIPT_ADDRESS
        acceptableAddressCodes = new int[] { addressHeader, p2shHeader };
        dumpedPrivateKeyHeader = 239;   // Ulord Testnet private keys start with either '9' or 'c' (same as bitcoin)
        //genesisBlock.setTime(1520308246L);
        genesisBlock.setTime(1524057440L);
        genesisBlock.setDifficultyTarget(521142271L);
        genesisBlock.setNonce(new BigInteger("000020f00dd1af082323e02e1f5b1d866d777abbcf63ba720d35dcf585840073", 16));

        nPowMaxAdjustDown = 32;
        nPowMaxAdjustUp = 48;
        minActualTimespan = (averagingWindowTimespan * (100 - nPowMaxAdjustUp))/100;
        maxActualTimespan = (averagingWindowTimespan * (100 + nPowMaxAdjustDown))/100;

        spendableCoinbaseDepth = 100;   // consensus.h COINBASE_MATURITY
        subsidyDecreaseBlockCount = 840960;

        String genesisHash = genesisBlock.getHashAsString();

        checkState(genesisHash.equals("000f378be841f44e75346eebd931b13041f0dee561af6a80cfea6669c1bfec03"));

        dnsSeeds = new String[] {
                "testnet-seed1.ulord.one",
                "testnet-seed1.ulord.io",
                "testnet-seed1.fcash.cc",
                "node.ulord.one",
                "10.221.153.180",
                "119.27.188.44"
        };
        addrSeeds = null;
        bip32HeaderPub = 0x043587CF;    // Ulord BIP32 pubkeys start with 'xpub' (Bitcoin defaults)
        bip32HeaderPriv = 0x04358394;   // Ulord BIP32 prvkeys start with 'xprv' (Bitcoin defaults)
        bip44HeaderCoin = 0x80000001;   // Ulord BIP44 coin type is '247'

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
}
