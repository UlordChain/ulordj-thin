/*
 * Copyright 2013 Google Inc.
 * Copyright 2015 Andreas Schildbach
 * Copyright 2016-2018 Ulord Dev team.
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
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import co.usc.ulordj.core.*;
import co.usc.ulordj.utils.MonetaryFormat;
import co.usc.ulordj.store.UldBlockStore;
import co.usc.ulordj.store.BlockStoreException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Stopwatch;

import co.usc.ulordj.core.UlordSerializer;

/**
 * Parameters for Bitcoin-like networks.
 */
public abstract class AbstractUlordNetParams extends NetworkParameters {
    /**
     * Scheme part for Ulord URIs.
     */
    public static final String ULORD_SCHEME = "ulord";

    private static final Logger log = LoggerFactory.getLogger(AbstractUlordNetParams.class);



    public AbstractUlordNetParams(String id) {
        super(id);
    }

    /**
     * Checks if we are at a difficulty transition point.
     * @param storedPrev The previous stored block
     * @return If this is a difficulty transition point
     */
    protected boolean isDifficultyTransitionPoint(StoredBlock storedPrev) {
        return ((storedPrev.getHeight() + 1) % this.getInterval()) == 0;
    }


    // TODO: This function requires further improvements
    // Currently this function can only verify blocks only if the blocks starts from genesis. We need to find a way
    // so that the blocks can be verified from any given block for checkpoints functionality. One way is to start verifying the blocks
    // once it has at least 17 blocks on top of a given block, this way computing average difficulty of 17 blocks and
    // median time of 11 blocks won't be a problem
    @Override
    public void checkDifficultyTransitions(final StoredBlock storedPrev, final UldBlock nextBlock,
    	final UldBlockStore blockStore) throws VerificationException, BlockStoreException {

        // Disable validation for RegTest
        if(this instanceof RegTestParams)
            return;

        UldBlock prev = storedPrev.getHeader();
        // Find the first block in the averaging interval
        StoredBlock cursor = blockStore.get(nextBlock.getPrevBlockHash());
        BigInteger nBitsTotal = BigInteger.ZERO;
        for(int i = 0; !cursor.getHeader().getHash().equals(this.genesisBlock.getHash())  && i < this.N_POW_AVERAGING_WINDOW; ++i) {
            //BigInteger nBitsTemp = cursor.getHeader().getDifficultyTargetAsInteger();
            //nBitsTotal = nBitsTotal.add(nBitsTemp);
            nBitsTotal = nBitsTotal.add(cursor.getHeader().getDifficultyTargetAsInteger());
            cursor = blockStore.get(cursor.getHeader().getPrevBlockHash());
        }

        if(cursor.getHeader().getHash().equals(genesisBlock.getHash()))
        {
            // Check if the difficulty didn't change
            if(nextBlock.getDifficultyTarget() != prev.getDifficultyTarget())
                throw new VerificationException("Difficulty did not match");
            return;
        }

        // Find the average
        BigInteger nBitsAvg = nBitsTotal.divide(BigInteger.valueOf(this.N_POW_AVERAGING_WINDOW));

        long prevBlockTimeSpan = getMedianTimestampOfRecentBlocks(storedPrev, blockStore);
        long firstBlockTimeSpan = getMedianTimestampOfRecentBlocks(cursor, blockStore);
        long timespan = (prevBlockTimeSpan - firstBlockTimeSpan);//cursor.getHeader().getTimeSeconds());
        timespan = this.averagingWindowTimespan + (timespan - this.averagingWindowTimespan) / 4;

        if(timespan < this.minActualTimespan)
            timespan = minActualTimespan;
        if(timespan > this.maxActualTimespan)
            timespan = maxActualTimespan;

        BigInteger expectedTarget = nBitsAvg;
        expectedTarget = expectedTarget.divide(BigInteger.valueOf(averagingWindowTimespan));
        expectedTarget = expectedTarget.multiply(BigInteger.valueOf(timespan));

        if(expectedTarget.compareTo(this.getMaxTarget()) > 0) {
            expectedTarget = this.getMaxTarget();
            log.info("Difficulty hit proof of work limit: {}", expectedTarget.toString(16));
        }

        long receivedTargetCompact = nextBlock.getDifficultyTarget();
        long expectedTargetCompact = Utils.encodeCompactBits(expectedTarget);

        if(expectedTargetCompact != receivedTargetCompact)
        {
            System.out.println("Network provided difficulty bits do not match what was calculated: " +
                    Long.toHexString(expectedTargetCompact) + " vs " + Long.toHexString(receivedTargetCompact));
            throw new VerificationException("Network provided difficulty bits do not match what was calculated: " +
                    Long.toHexString(expectedTargetCompact) + " vs " + Long.toHexString(receivedTargetCompact));
        }

    }

    /**
     * Gets the median timestamp of the last 11 blocks
     */
    private long getMedianTimestampOfRecentBlocks(StoredBlock storedBlock,
                                                  UldBlockStore store) throws BlockStoreException {
        long[] timestamps = new long[11];
        int unused = 9;
        timestamps[10] = storedBlock.getHeader().getTimeSeconds();
        if(storedBlock.getPrev(store) != null){
            while (unused >= 0 && !((storedBlock = storedBlock.getPrev(store)).getHeader().getHash()).equals(this.genesisBlock.getHash()) ){
                timestamps[unused--] = storedBlock.getHeader().getTimeSeconds();
            	}
        }

        Arrays.sort(timestamps, unused+1, 11);
        return timestamps[unused + (11-unused)/2];
    }

    @Override
    public Coin getMaxMoney() {
        return MAX_MONEY;
    }

    @Override
    public Coin getMinNonDustOutput() {
        return UldTransaction.MIN_NONDUST_OUTPUT;
    }

    @Override
    public MonetaryFormat getMonetaryFormat() {
        return new MonetaryFormat();
    }

    @Override
    public int getProtocolVersionNum(final ProtocolVersion version) {
        return version.getUlordProtocolVersion();
    }

    @Override
    public UlordSerializer getSerializer(boolean parseRetain) {
        return new UlordSerializer(this, parseRetain);
    }

    @Override
    public String getUriScheme() {
        return ULORD_SCHEME;
    }

    @Override
    public boolean hasMaxMoney() {
        return true;
    }
}
