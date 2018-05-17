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

    @Override
    public void checkDifficultyTransitions(final StoredBlock storedPrev, final UldBlock nextBlock,
    	final UldBlockStore blockStore) throws VerificationException, BlockStoreException {
        UldBlock prev = storedPrev.getHeader();

        // Return if the previous block of prev is genesis
        if(prev.getPrevBlockHash().compareTo(this.genesisBlock.getHash()) == 0)
            return;

        // We need one block before the prev block
        StoredBlock cursor = blockStore.get(prev.getPrevBlockHash());

        UldBlock blockBeforePrevBlock = cursor.getHeader();
        int timespan = (int) (prev.getTimeSeconds() - blockBeforePrevBlock.getTimeSeconds());
        timespan = this.averagingWindowTimespan + (timespan - this.averagingWindowTimespan)/4;

        if(timespan < this.minActualTimespan)
            timespan = minActualTimespan;
        if(timespan > this.maxActualTimespan)
            timespan = maxActualTimespan;

        BigInteger newTarget = Utils.decodeCompactBits(nextBlock.getDifficultyTarget());
        newTarget = newTarget.divide(BigInteger.valueOf(averagingWindowTimespan));
        newTarget = newTarget.multiply(BigInteger.valueOf(timespan));

        if(newTarget.compareTo(this.getMaxTarget()) > 0) {
            newTarget = this.getMaxTarget();
            log.info("Difficulty hit proof of work limit: {}", newTarget.toString(16));
        }

        int accuracyByts = (int) (nextBlock.getDifficultyTarget() >>> 24) - 3;
        long receivedTargetCompact = nextBlock.getDifficultyTarget();

        BigInteger mask = BigInteger.valueOf(0xFFFFFFL).shiftLeft(accuracyByts * 8);
        newTarget = newTarget.and(mask);
        long newTargetCompact = Utils.encodeCompactBits(newTarget);

        if(newTargetCompact != receivedTargetCompact)
            throw new VerificationException("Network provided difficulty bits do not match what was calculated: " +
                        Long.toHexString(newTargetCompact) + " vs " + Long.toHexString(receivedTargetCompact));

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
