/*
 * Copyright 2013 Google Inc.
 * Copyright 2015 Andreas Schildbach
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

import static com.google.common.base.Preconditions.*;

/**
 * Parameters for the main production network on which people trade goods and services.
 */
public class MainNetParams extends AbstractUlordNetParams {
    public static final int MAINNET_MAJORITY_WINDOW = 1000;
    public static final int MAINNET_MAJORITY_REJECT_BLOCK_OUTDATED = 950;
    public static final int MAINNET_MAJORITY_ENFORCE_BLOCK_UPGRADE = 750;

    public MainNetParams() {
        // Genesis hash is 0000083331b8aa57aaae020d79aabe4136ebea6ce29be3a50fcaa2a55777e79c
        super(ID_MAINNET);
        interval = INTERVAL;
        targetTimespan = TARGET_TIMESPAN;
        maxTarget = new BigInteger("000009b173000000000000000000000000000000000000000000000000000000", 16);
        dumpedPrivateKeyHeader = 128;   // Ulord private keys start with '5' or 'K' or 'L'(as in Bitcoin)
        addressHeader = 68;     // Ulord addresses start with 'U'
        p2shHeader = 63;        // Ulord script addresses start with 'S'
        acceptableAddressCodes = new int[] { addressHeader, p2shHeader };
        port = 9888;
        packetMagic = 0xb3016fb1L;
        bip32HeaderPub = 0x0488B21E; //The 4 byte header that serializes in base58 to "xpub".
        bip32HeaderPriv = 0x0488ADE4; //The 4 byte header that serializes in base58 to "xprv"
        bip44HeaderCoin = 0x800000f7; // Ulord BIP44 coin type '247'

        majorityEnforceBlockUpgrade = MAINNET_MAJORITY_ENFORCE_BLOCK_UPGRADE;
        majorityRejectBlockOutdated = MAINNET_MAJORITY_REJECT_BLOCK_OUTDATED;
        majorityWindow = MAINNET_MAJORITY_WINDOW;

        genesisBlock.setDifficultyTarget(503951731L);
        genesisBlock.setTime(1526947274L);
        genesisBlock.setNonce(new BigInteger("0000e865ca57d789ba58154ed7f29decd569ce91289d870bf54f824dea09971c", 16));

        subsidyDecreaseBlockCount = 840960;
        spendableCoinbaseDepth = 100;

        nPowMaxAdjustDown = 32;
        nPowMaxAdjustUp = 48;
        minActualTimespan = (averagingWindowTimespan * (100 - nPowMaxAdjustUp))/100;
        maxActualTimespan = (averagingWindowTimespan * (100 + nPowMaxAdjustDown))/100;

        String genesisHash = genesisBlock.getHashAsString();
        checkState(genesisHash.equals("0000079b37c3c290dc81e95bca28aa7df5636145ae35ebee86e10cc3cce96fb2"), genesisHash);

        // This contains (at a minimum) the blocks which are not BIP30 compliant. BIP30 changed how duplicate
        // transactions are handled. Duplicated transactions could occur in the case where a coinbase had the same
        // extraNonce and the same outputs but appeared at different heights, and greatly complicated re-org handling.
        // Having these here simplifies block connection logic considerably.
        //checkpoints.put(91722, Sha256Hash.wrap("00000000000271a2dc26e7667f8419f2e15416dc6955e5a6c6cdf3f2574dd08e"));
        //checkpoints.put(91812, Sha256Hash.wrap("00000000000af0aed4792b1acee3d966af36cf5def14935db8de83d6f9306f2f"));
        //checkpoints.put(91842, Sha256Hash.wrap("00000000000a4d0a398161ffc163c503763b1f4360639393e0e4c8e300e0caec"));
        //checkpoints.put(91880, Sha256Hash.wrap("00000000000743f190a18c5577a3c2d2a1f610ae9601ac046a38084ccb7cd721"));
        //checkpoints.put(200000, Sha256Hash.wrap("000000000000034a7dedef4a161fa058a2d67a173a90155f3a2fe6fc132e0ebf"));
    }

    private static MainNetParams instance;
    public static synchronized MainNetParams get() {
        if (instance == null) {
            instance = new MainNetParams();
        }
        return instance;
    }

    @Override
    public String getPaymentProtocolId() {
        return PAYMENT_PROTOCOL_ID_MAINNET;
    }
}
