/*
 * Copyright 2012 Google Inc.
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

import com.google.common.base.*;
import co.usc.ulordj.store.*;
import co.usc.ulordj.utils.*;
import co.usc.ulordj.wallet.Wallet;
import org.slf4j.*;

import javax.annotation.*;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

import static com.google.common.base.Preconditions.*;

/**
 * <p>An AbstractBlockChain holds a series of {@link UldBlock} objects, links them together, and knows how to verify that
 * the chain follows the rules of the {@link NetworkParameters} for this chain.</p>
 *
 * <p>It can be connected to a {@link Wallet}, and also {@link TransactionReceivedInBlockListener}s that can receive transactions and
 * notifications of re-organizations.</p>
 *
 * <p>An AbstractBlockChain implementation must be connected to a {@link UldBlockStore} implementation. The chain object
 * by itself doesn't store any data, that's delegated to the store. Which store you use is a decision best made by
 * reading the getting started guide, but briefly, fully validating block chains need fully validating stores. In
 * the lightweight SPV mode, a {@link co.usc.ulordj.store.SPVBlockStore} is the right choice.</p>
 *
 * <p>This class implements an abstract class which makes it simple to create a BlockChain that does/doesn't do full
 * verification.  It verifies headers and is implements most of what is required to implement SPV mode, but
 * also provides callback hooks which can be used to do full verification.</p>
 *
 * <p>There are two subclasses of AbstractBlockChain that are useful: {@link UldBlockChain}, which is the simplest
 * class and implements <i>simplified payment verification</i>. This is a lightweight and efficient mode that does
 * not verify the contents of blocks, just their headers. A {@link FullPrunedBlockChain} paired with a
 * {@link co.usc.ulordj.store.H2FullPrunedBlockStore} implements full verification, which is equivalent to
 * Bitcoin Core. To learn more about the alternative security models, please consult the articles on the
 * website.</p>
 *
 * <b>Theory</b>
 *
 * <p>The 'chain' is actually a tree although in normal operation it operates mostly as a list of {@link UldBlock}s.
 * When multiple new head blocks are found simultaneously, there are multiple stories of the economy competing to become
 * the one true consensus. This can happen naturally when two miners solve a block within a few seconds of each other,
 * or it can happen when the chain is under attack.</p>
 *
 * <p>A reference to the head block of the best known chain is stored. If you can reach the genesis block by repeatedly
 * walking through the prevBlock pointers, then we say this is a full chain. If you cannot reach the genesis block
 * we say it is an orphan chain. Orphan chains can occur when blocks are solved and received during the initial block
 * chain download, or if we connect to a peer that doesn't send us blocks in order.</p>
 *
 * <p>A reorganize occurs when the blocks that make up the best known chain changes. Note that simply adding a
 * new block to the top of the best chain isn't as reorganize, but that a reorganize is always triggered by adding
 * a new block that connects to some other (non best head) block. By "best" we mean the chain representing the largest
 * amount of work done.</p>
 *
 * <p>Every so often the block chain passes a difficulty transition point. At that time, all the blocks in the last
 * 2016 blocks are examined and a new difficulty target is calculated from them.</p>
 */
public abstract class UldAbstractBlockChain {
    private static final Logger log = LoggerFactory.getLogger(UldAbstractBlockChain.class);

    protected final ReentrantLock lock = Threading.lock("Ulordj blockchain");

    /** Keeps a map of block hashes to StoredBlocks. */
    private final UldBlockStore blockStore;

    /**
     * Tracks the top of the best known chain.<p>
     *
     * Following this one down to the genesis block produces the story of the economy from the creation of Bitcoin
     * until the present day. The chain head can change if a new set of blocks is received that results in a chain of
     * greater work than the one obtained by following this one down. In that case a reorganize is triggered,
     * potentially invalidating transactions in our wallet.
     */
    protected StoredBlock chainHead;

    // TODO: Scrap this and use a proper read/write for all of the block chain objects.
    // The chainHead field is read/written synchronized with this object rather than BlockChain. However writing is
    // also guaranteed to happen whilst BlockChain is synchronized (see setChainHead). The goal of this is to let
    // clients quickly access the chain head even whilst the block chain is downloading and thus the BlockChain is
    // locked most of the time.
    private final Object chainHeadLock = new Object();

    protected final NetworkParameters params;

    // Holds a block header and, optionally, a list of tx hashes or block's transactions
    class OrphanBlock {
        final UldBlock block;
        final FilteredBlock filteredBlock;
        final List<Sha256Hash> filteredTxHashes;
        final Map<Sha256Hash, UldTransaction> filteredTxn;
        OrphanBlock(UldBlock block, @Nullable List<Sha256Hash> filteredTxHashes, @Nullable Map<Sha256Hash, UldTransaction> filteredTxn, FilteredBlock filteredBlock) {
            final boolean filtered = filteredTxHashes != null && filteredTxn != null;
            Preconditions.checkArgument((block.transactions == null && filtered)
                                        || (block.transactions != null && !filtered));
            this.block = block;
            this.filteredTxHashes = filteredTxHashes;
            this.filteredTxn = filteredTxn;
            this.filteredBlock = filteredBlock;
        }
        public Boolean hasFilteredBlock() {
            return filteredBlock != null;
        }
    }
    // Holds blocks that we have received but can't plug into the chain yet, eg because they were created whilst we
    // were downloading the block chain.
    private final LinkedHashMap<Sha256Hash, OrphanBlock> orphanBlocks = new LinkedHashMap<Sha256Hash, OrphanBlock>();

    /** False positive estimation uses a double exponential moving average. */
    public static final double FP_ESTIMATOR_ALPHA = 0.0001;
    /** False positive estimation uses a double exponential moving average. */
    public static final double FP_ESTIMATOR_BETA = 0.01;

    private double falsePositiveRate;
    private double falsePositiveTrend;
    private double previousFalsePositiveRate;

    private final VersionTally versionTally;

    /** See {@link #UldAbstractBlockChain(Context, UldBlockStore)} */
    public UldAbstractBlockChain(NetworkParameters params,
                                 UldBlockStore blockStore) throws BlockStoreException {
        this(Context.getOrCreate(params), blockStore);
    }

    /**
     * Constructs a BlockChain connected to the given list of listeners (eg, wallets) and a store.
     */
    public UldAbstractBlockChain(Context context,
                                 UldBlockStore blockStore) throws BlockStoreException {
        this.blockStore = blockStore;
        chainHead = blockStore.getChainHead();
        log.info("chain head is at height {}:\n{}", chainHead.getHeight(), chainHead.getHeader());
        this.params = context.getParams();

        this.versionTally = new VersionTally(context.getParams());
        this.versionTally.initialize(blockStore, chainHead);
    }

    /**
     * Returns the {@link UldBlockStore} the chain was constructed with. You can use this to iterate over the chain.
     */
    public UldBlockStore getBlockStore() {
        return blockStore;
    }
    
    /**
     * Adds/updates the given {@link UldBlock} with the block store.
     * This version is used when the transactions have not been verified.
     * @param storedPrev The {@link StoredBlock} which immediately precedes block.
     * @param block The {@link UldBlock} to add/update.
     * @return the newly created {@link StoredBlock}
     */
    protected abstract StoredBlock addToBlockStore(StoredBlock storedPrev, UldBlock block)
            throws BlockStoreException, VerificationException;
    
    /**
     * Rollback the block store to a given height. This is currently only supported by {@link UldBlockChain} instances.
     * 
     * @throws BlockStoreException
     *             if the operation fails or is unsupported.
     */
    protected abstract void rollbackBlockStore(int height) throws BlockStoreException;

    /**
     * Called before setting chain head in memory.
     * Should write the new head to block store and then commit any database transactions
     * that were started by disconnectTransactions/connectTransactions.
     */
    protected abstract void doSetChainHead(StoredBlock chainHead) throws BlockStoreException;
    
    /**
     * Called if we (possibly) previously called disconnectTransaction/connectTransactions,
     * but will not be calling preSetChainHead as a block failed verification.
     * Can be used to abort database transactions that were started by
     * disconnectTransactions/connectTransactions.
     */
    protected abstract void notSettingChainHead() throws BlockStoreException;
    
    /**
     * For a standard BlockChain, this should return blockStore.get(hash),
     * for a FullPrunedBlockChain blockStore.getOnceUndoableStoredBlock(hash)
     */
    protected abstract StoredBlock getStoredBlockInCurrentScope(Sha256Hash hash) throws BlockStoreException;

    /**
     * Processes a received block and tries to add it to the chain. If there's something wrong with the block an
     * exception is thrown. If the block is OK but cannot be connected to the chain at this time, returns false.
     * If the block can be connected to the chain, returns true.
     * Accessing block's transactions in another thread while this method runs may result in undefined behavior.
     */
    public boolean add(UldBlock block) throws VerificationException {
        return addBlock(block).success();
    }
    
    /**
     * Processes a received block and tries to add it to the chain. If there's something wrong with the block an
     * exception is thrown. If the block is OK but cannot be connected to the chain at this time, returns false.
     * If the block can be connected to the chain, returns true.
     */
    public boolean add(FilteredBlock block) throws VerificationException {
        return addBlock(block).success();
    }
    
    /**
     * Same as add(Block block) method, but returns an BlockchainAddResult that informs the global result plus a list 
     * of blocks added during the execution of the add process.
     */
    public BlockchainAddResult addBlock(UldBlock block) throws VerificationException {
        return runAddProcces(block, true, null, null, null);
    }
    
    /**
     * Same as add(FilteredBlock block) method, but returns an BlockchainAddResult that informs the global result plus a list 
     * of blocks added during the execution of the add process.
     */
    public BlockchainAddResult addBlock(FilteredBlock block) throws VerificationException  {
        return runAddProcces(block.getBlockHeader(), true, block.getTransactionHashes(), block.getAssociatedTransactions(), block);
    }
    
    /**
     * This code was duplicated on add(Block) and in add(FilteredBlock), as the original comment says. The way to handle exceptions should be improved
     */
    private BlockchainAddResult runAddProcces(UldBlock block, boolean tryConnecting,
                                              @Nullable List<Sha256Hash> filteredTxHashList, @Nullable Map<Sha256Hash, UldTransaction> filteredTxn, FilteredBlock filteredBlock) throws VerificationException{
        try {
            // The block has a list of hashes of transactions that matched the Bloom filter, and a list of associated
            // Transaction objects. There may be fewer Transaction objects than hashes, this is expected. It can happen
            // in the case where we were already around to witness the initial broadcast, so we downloaded the
            // transaction and sent it to the wallet before this point (the wallet may have thrown it away if it was
            // a false positive, as expected in any Bloom filtering scheme). The filteredTxn list here will usually
            // only be full of data when we are catching up to the head of the chain and thus haven't witnessed any
            // of the transactions.
            return add(block, tryConnecting, filteredTxHashList, filteredTxn, filteredBlock);
        } catch (BlockStoreException e) {
            // TODO: Figure out a better way to propagate this exception to the user.
            throw new RuntimeException(e);
        } catch (VerificationException e) {
            try {
                notSettingChainHead();
            } catch (BlockStoreException e1) {
                throw new RuntimeException(e1);
            }
            throw new VerificationException("Could not verify block " + block.getHash().toString() + "\n" +
                    block.toString(), e);
        }
    }

    /**
     * Whether or not we are maintaining a set of unspent outputs and are verifying all transactions.
     * Also indicates that all calls to add() should provide a block containing transactions
     */
    protected abstract boolean shouldVerifyTransactions();
    

    // filteredTxHashList contains all transactions, filteredTxn just a subset
    private BlockchainAddResult add(UldBlock block, boolean tryConnecting,
                                    @Nullable List<Sha256Hash> filteredTxHashList, @Nullable Map<Sha256Hash, UldTransaction> filteredTxn, FilteredBlock filteredBlock)
            throws BlockStoreException, VerificationException {
        // TODO: Use read/write locks to ensure that during chain download properties are still low latency.
        BlockchainAddResult result = new BlockchainAddResult();
        try {
            // Quick check for duplicates to avoid an expensive check further down (in findSplit). This can happen a lot
            // when connecting orphan transactions due to the dumb brute force algorithm we use.
            if (block.equals(getChainHead().getHeader())) {
                result.setSuccess(Boolean.TRUE);
                return result;
            }
            if (tryConnecting && orphanBlocks.containsKey(block.getHash())) {
                result.setSuccess(Boolean.FALSE);
                return result;
            }

            // If we want to verify transactions (ie we are running with full blocks), verify that block has transactions
            if (shouldVerifyTransactions() && block.transactions == null)
                throw new VerificationException("Got a block header while running in full-block mode");

            // Check for already-seen block.
            if (blockStore.get(block.getHash()) != null) {
                result.setSuccess(Boolean.TRUE);
                return result;
            }

            final StoredBlock storedPrev;

            // Prove the block is internally valid: hash is lower than target, etc. This only checks the block contents
            // if there is a tx sending or receiving coins using an address in one of our wallets. And those transactions
            // are only lightly verified: presence in a valid connecting block is taken as proof of validity. See the
            // article here for more details: https://bitcoinj.github.io/security-model
            try {
                block.verifyHeader();
                storedPrev = getStoredBlockInCurrentScope(block.getPrevBlockHash());
            } catch (VerificationException e) {
                log.error("Failed to verify block: ", e);
                log.error(block.getHashAsString());
                throw e;
            }

            // Try linking it to a place in the currently known blocks.

            if (storedPrev == null) {
                // We can't find the previous block. Probably we are still in the process of downloading the chain and a
                // block was solved whilst we were doing it. We put it to one side and try to connect it later when we
                // have more blocks.
                checkState(tryConnecting, "bug in tryConnectingOrphans");
                log.warn("Block does not connect: {} prev {}", block.getHashAsString(), block.getPrevBlockHash());
                orphanBlocks.put(block.getHash(), new OrphanBlock(block, filteredTxHashList, filteredTxn, filteredBlock));
                result.setSuccess(Boolean.FALSE);
                return result;
            } else {
                // It connects to somewhere on the chain. Not necessarily the top of the best known chain.
                params.checkDifficultyTransitions(storedPrev, block, blockStore);
                //checkDifficultyTransitions_Old(storedPrev, block);
                connectBlock(block, storedPrev, shouldVerifyTransactions(), filteredTxHashList, filteredTxn);
            }
            
            if (tryConnecting) {
                List<OrphanBlock> orphans = tryConnectingOrphans();
                for(OrphanBlock ob : orphans) {
                    result.addConnectedOrphan(ob.block);
                    if(ob.hasFilteredBlock())
                        result.addConnectedFilteredOrphan(ob.filteredBlock);
                }
            }
            result.setSuccess(Boolean.TRUE);
            return result;
        } finally {
        }
    }

    /**
     * Throws an exception if the blocks difficulty is not correct.
     */
    private void checkDifficultyTransitions_Old(StoredBlock storedPrev, UldBlock nextBlock) throws BlockStoreException, VerificationException {
        //checkState(lock.isHeldByCurrentThread());

        int DiffMode = 1;
        if (params.getId().equals(NetworkParameters.ID_TESTNET)) {
            if (storedPrev.getHeight()+1 >= 4001) { DiffMode = 4; }
        }
        else {
            if (storedPrev.getHeight()+1 >= 68589) { DiffMode = 4; }
            else if (storedPrev.getHeight()+1 >= 34140) { DiffMode = 3; }
            else if (storedPrev.getHeight()+1 >= 15200) { DiffMode = 2; }
        }

        if      (DiffMode == 1) { checkDifficultyTransitions_V1(storedPrev, nextBlock); return; }
        else if (DiffMode == 2) { checkDifficultyTransitions_V2(storedPrev, nextBlock); return; }
        else if (DiffMode == 3) { UlordGravityWave(storedPrev, nextBlock);              return; }
        else if (DiffMode == 4) { UlordGravityWave3(storedPrev, nextBlock);             return; }

        UlordGravityWave3(storedPrev, nextBlock);

        return;
    }

    private void checkDifficultyTransitions_V1(StoredBlock storedPrev, UldBlock nextBlock) throws BlockStoreException, VerificationException {
        //checkState(lock.isHeldByCurrentThread());
        UldBlock prev = storedPrev.getHeader();

        // Is this supposed to be a difficulty transition point?
        if ((storedPrev.getHeight() + 1) % params.getInterval() != 0) {

            // TODO: Refactor this hack after 0.5 is released and we stop supporting deserialization compatibility.
            // This should be a method of the NetworkParameters, which should in turn be using singletons and a subclass
            // for each network type. Then each network can define its own difficulty transition rules.
            if (params.getId().equals(NetworkParameters.ID_TESTNET)) {
                checkTestnetDifficulty(storedPrev, prev, nextBlock);
                return;
            }

            // No ... so check the difficulty didn't actually change.
            if (nextBlock.getDifficultyTarget() != prev.getDifficultyTarget())
                throw new VerificationException("Unexpected change in difficulty at height " + storedPrev.getHeight() +
                        ": " + Long.toHexString(nextBlock.getDifficultyTarget()) + " vs " +
                        Long.toHexString(prev.getDifficultyTarget()));
            return;
        }

        // We need to find a block far back in the chain. It's OK that this is expensive because it only occurs every
        // two weeks after the initial block chain download.
        long now = System.currentTimeMillis();
        StoredBlock cursor = blockStore.get(prev.getHash());

        int blockstogoback = params.getInterval() - 1;
        if(storedPrev.getHeight()+1 != params.getInterval())
            blockstogoback = params.getInterval();

        for (int i = 0; i < blockstogoback; i++) {
            if (cursor == null) {
                // This should never happen. If it does, it means we are following an incorrect or busted chain.
                throw new VerificationException(
                        "Difficulty transition point but we did not find a way back to the genesis block.");
            }
            cursor = blockStore.get(cursor.getHeader().getPrevBlockHash());
        }
        long elapsed = System.currentTimeMillis() - now;
        if (elapsed > 50)
            log.info("Difficulty transition traversal took {}msec", elapsed);

        UldBlock blockIntervalAgo = cursor.getHeader();
        int timespan = (int) (prev.getTimeSeconds() - blockIntervalAgo.getTimeSeconds());
        // Limit the adjustment step.
        final int targetTimespan = params.getTargetTimespan();
        if (timespan < targetTimespan / 4)
            timespan = targetTimespan / 4;
        if (timespan > targetTimespan * 4)
            timespan = targetTimespan * 4;

        BigInteger newTarget = Utils.decodeCompactBits(prev.getDifficultyTarget());
        newTarget = newTarget.multiply(BigInteger.valueOf(timespan));
        newTarget = newTarget.divide(BigInteger.valueOf(targetTimespan));

        if (newTarget.compareTo(params.getMaxTarget()) > 0) {
            log.info("Difficulty hit proof of work limit: {}", newTarget.toString(16));
            newTarget = params.getMaxTarget();
        }

        int accuracyBytes = (int) (nextBlock.getDifficultyTarget() >>> 24) - 3;
        long receivedTargetCompact = nextBlock.getDifficultyTarget();

        // The calculated difficulty is to a higher precision than received, so reduce here.
        BigInteger mask = BigInteger.valueOf(0xFFFFFFL).shiftLeft(accuracyBytes * 8);
        newTarget = newTarget.and(mask);
        long newTargetCompact = Utils.encodeCompactBits(newTarget);

//        if (newTargetCompact != receivedTargetCompact)
//            throw new VerificationException("Network provided difficulty bits do not match what was calculated: " +
//                    newTargetCompact + " vs " + receivedTargetCompact);
    }

    private void checkDifficultyTransitions_V2(StoredBlock storedPrev, UldBlock nextBlock) throws BlockStoreException, VerificationException {
        final long      	BlocksTargetSpacing			= (long)(2.5 * 60); // 10 minutes
        int         		TimeDaySeconds				= 60 * 60 * 24;
        long				PastSecondsMin				= TimeDaySeconds / 40;
        long				PastSecondsMax				= TimeDaySeconds * 7;
        long				PastBlocksMin				= PastSecondsMin / BlocksTargetSpacing;   //? blocks
        long				PastBlocksMax				= PastSecondsMax / BlocksTargetSpacing;   //? blocks

        //if(!fgw.isNativeLibraryLoaded())
        //long start = System.currentTimeMillis();
        KimotoGravityWell(storedPrev, nextBlock, BlocksTargetSpacing, PastBlocksMin, PastBlocksMax);
        //long end1 = System.currentTimeMillis();
        //if(kgw.isNativeLibraryLoaded())
        //else
        //    KimotoGravityWell_N(storedPrev, nextBlock, BlocksTargetSpacing, PastBlocksMin, PastBlocksMax);
        //long end2 = System.currentTimeMillis();
        //if(kgw.isNativeLibraryLoaded())
        //    KimotoGravityWell_N2(storedPrev, nextBlock, BlocksTargetSpacing, PastBlocksMin, PastBlocksMax);
        /*long end3 = System.currentTimeMillis();

        long java = end1 - start;
        long n1 = end2 - end1;
        long n2 = end3 - end2;
        if(i > 20)
        {
            j += java;
            N += n1;
            N2 += n2;
            if(i != 0 && ((i % 10) == 0))
             //log.info("KGW 10 blocks: J={}; N={} -%.0f%; N2={} -%.0f%", java, n1, ((double)(java-n1))/java*100, n2, ((double)(java-n2))/java*100);
                 log.info("KGW {} blocks: J={}; N={} -{}%; N2={} -{}%", i-20, j, N, ((double)(j-N))/j*100, N2, ((double)(j-N2))/j*100);
        }
        ++i;*/
    }

    private void KimotoGravityWell(StoredBlock storedPrev, UldBlock nextBlock, long TargetBlocksSpacingSeconds, long PastBlocksMin, long PastBlocksMax)  throws BlockStoreException, VerificationException {
        /* current difficulty formula, megacoin - kimoto gravity well */
        //const CBlockIndex  *BlockLastSolved				= pindexLast;
        //const CBlockIndex  *BlockReading				= pindexLast;
        //const CBlockHeader *BlockCreating				= pblock;
        StoredBlock         BlockLastSolved             = storedPrev;
        StoredBlock         BlockReading                = storedPrev;
        UldBlock               BlockCreating               = nextBlock;

        BlockCreating				= BlockCreating;
        long				PastBlocksMass				= 0;
        long				PastRateActualSeconds		= 0;
        long				PastRateTargetSeconds		= 0;
        double				PastRateAdjustmentRatio		= 1f;
        BigInteger			PastDifficultyAverage = BigInteger.valueOf(0);
        BigInteger			PastDifficultyAveragePrev = BigInteger.valueOf(0);;
        double				EventHorizonDeviation;
        double				EventHorizonDeviationFast;
        double				EventHorizonDeviationSlow;

        long start = System.currentTimeMillis();

        if (BlockLastSolved == null || BlockLastSolved.getHeight() == 0 || (long)BlockLastSolved.getHeight() < PastBlocksMin)
        { verifyDifficulty(params.getMaxTarget(), storedPrev, nextBlock); }

        int i = 0;
        long LatestBlockTime = BlockLastSolved.getHeader().getTimeSeconds();

        for (i = 1; BlockReading != null && BlockReading.getHeight() > 0; i++) {
            if (PastBlocksMax > 0 && i > PastBlocksMax) { break; }
            PastBlocksMass++;

            if (i == 1)	{ PastDifficultyAverage = BlockReading.getHeader().getDifficultyTargetAsInteger(); }
            else		{ PastDifficultyAverage = ((BlockReading.getHeader().getDifficultyTargetAsInteger().subtract(PastDifficultyAveragePrev)).divide(BigInteger.valueOf(i)).add(PastDifficultyAveragePrev)); }
            PastDifficultyAveragePrev = PastDifficultyAverage;


            if (BlockReading.getHeight() > 646120 && LatestBlockTime < BlockReading.getHeader().getTimeSeconds()) {
                //eliminates the ability to go back in time
                LatestBlockTime = BlockReading.getHeader().getTimeSeconds();
            }

            PastRateActualSeconds			= BlockLastSolved.getHeader().getTimeSeconds() - BlockReading.getHeader().getTimeSeconds();
            PastRateTargetSeconds			= TargetBlocksSpacingSeconds * PastBlocksMass;
            PastRateAdjustmentRatio			= 1.0f;
            if (BlockReading.getHeight() > 646120){
                //this should slow down the upward difficulty change
                if (PastRateActualSeconds < 5) { PastRateActualSeconds = 5; }
            }
            else {
                if (PastRateActualSeconds < 0) { PastRateActualSeconds = 0; }
            }
            if (PastRateActualSeconds != 0 && PastRateTargetSeconds != 0) {
                PastRateAdjustmentRatio			= (double)PastRateTargetSeconds / PastRateActualSeconds;
            }
            EventHorizonDeviation			= 1 + (0.7084 * java.lang.Math.pow((Double.valueOf(PastBlocksMass)/Double.valueOf(28.2)), -1.228));
            EventHorizonDeviationFast		= EventHorizonDeviation;
            EventHorizonDeviationSlow		= 1 / EventHorizonDeviation;

            if (PastBlocksMass >= PastBlocksMin) {
                if ((PastRateAdjustmentRatio <= EventHorizonDeviationSlow) || (PastRateAdjustmentRatio >= EventHorizonDeviationFast))
                {
                    /*assert(BlockReading)*/;
                    break;
                }
            }
            StoredBlock BlockReadingPrev = blockStore.get(BlockReading.getHeader().getPrevBlockHash());
            if (BlockReadingPrev == null)
            {
                //assert(BlockReading);
                //Since we are using the checkpoint system, there may not be enough blocks to do this diff adjust, so skip until we do
                //break;
                return;
            }
            BlockReading = BlockReadingPrev;
        }

        /*CBigNum bnNew(PastDifficultyAverage);
        if (PastRateActualSeconds != 0 && PastRateTargetSeconds != 0) {
            bnNew *= PastRateActualSeconds;
            bnNew /= PastRateTargetSeconds;
        } */
        //log.info("KGW-J, {}, {}, {}", storedPrev.getHeight(), i, System.currentTimeMillis() - start);
        BigInteger newDifficulty = PastDifficultyAverage;
        if (PastRateActualSeconds != 0 && PastRateTargetSeconds != 0) {
            newDifficulty = newDifficulty.multiply(BigInteger.valueOf(PastRateActualSeconds));
            newDifficulty = newDifficulty.divide(BigInteger.valueOf(PastRateTargetSeconds));
        }

        if (newDifficulty.compareTo(params.getMaxTarget()) > 0) {
            log.info("Difficulty hit proof of work limit: {}", newDifficulty.toString(16));
            newDifficulty = params.getMaxTarget();
        }


        //log.info("KGW-j Difficulty Calculated: {}", newDifficulty.toString(16));
        verifyDifficulty(newDifficulty, storedPrev, nextBlock);

    }

    private void verifyDifficulty(BigInteger calcDiff, StoredBlock storedPrev, UldBlock nextBlock) {
        if (calcDiff.compareTo(params.getMaxTarget()) > 0) {
            log.info("Difficulty hit proof of work limit: {}", calcDiff.toString(16));
            calcDiff = params.getMaxTarget();
        }
        int accuracyBytes = (int) (nextBlock.getDifficultyTarget() >>> 24) - 3;
        BigInteger receivedDifficulty = nextBlock.getDifficultyTargetAsInteger();

        // The calculated difficulty is to a higher precision than received, so reduce here.
        BigInteger mask = BigInteger.valueOf(0xFFFFFFL).shiftLeft(accuracyBytes * 8);
        calcDiff = calcDiff.and(mask);
        if(params.getId().compareTo(params.ID_TESTNET) == 0)
        {
//            if (calcDiff.compareTo(receivedDifficulty) != 0)
//                throw new VerificationException("Network provided difficulty bits do not match what was calculated: " +
//                        receivedDifficulty.toString(16) + " vs " + calcDiff.toString(16));
        }
        else
        {
            int height = storedPrev.getHeight() + 1;
            ///if(System.getProperty("os.name").toLowerCase().contains("windows"))
            //{
            if(height <= 68589)
            {
                long nBitsNext = nextBlock.getDifficultyTarget();

                long calcDiffBits = (accuracyBytes+3) << 24;
                calcDiffBits |= calcDiff.shiftRight(accuracyBytes*8).longValue();

                double n1 = ConvertBitsToDouble(calcDiffBits);
                double n2 = ConvertBitsToDouble(nBitsNext);

                if(java.lang.Math.abs(n1-n2) > n1*0.2)
                    throw new VerificationException("Network provided difficulty bits do not match what was calculated: " +
                            receivedDifficulty.toString(16) + " vs " + calcDiff.toString(16));
            }
            else
            {
                if (calcDiff.compareTo(receivedDifficulty) != 0)
                    throw new VerificationException("Network provided difficulty bits do not match what was calculated: " +
                            receivedDifficulty.toString(16) + " vs " + calcDiff.toString(16));
            }

            /*
            if(height >= 34140)
                {
                    long nBitsNext = nextBlock.getDifficultyTarget();

                    long calcDiffBits = (accuracyBytes+3) << 24;
                    calcDiffBits |= calcDiff.shiftRight(accuracyBytes*8).longValue();

                    double n1 = ConvertBitsToDouble(calcDiffBits);
                    double n2 = ConvertBitsToDouble(nBitsNext);

                    if(height <= 45000) {
                        if(java.lang.Math.abs(n1-n2) > n1*0.2)
                            throw new VerificationException("Network provided difficulty bits do not match what was calculated: " +
                                    receivedDifficulty.toString(16) + " vs " + calcDiff.toString(16));


                    }
                    else if(java.lang.Math.abs(n1-n2) > n1*0.005)
                        throw new VerificationException("Network provided difficulty bits do not match what was calculated: " +
                                receivedDifficulty.toString(16) + " vs " + calcDiff.toString(16));
                }
                else
                {
                    if (calcDiff.compareTo(receivedDifficulty) != 0)
                        throw new VerificationException("Network provided difficulty bits do not match what was calculated: " +
                                receivedDifficulty.toString(16) + " vs " + calcDiff.toString(16));
                }
            */
            //}
            /*else
            {

            if(height >= 34140 && height <= 45000)
            {
                long nBitsNext = nextBlock.getDifficultyTarget();

                long calcDiffBits = (accuracyBytes+3) << 24;
                calcDiffBits |= calcDiff.shiftRight(accuracyBytes*8).longValue();

                double n1 = ConvertBitsToDouble(calcDiffBits);
                double n2 = ConvertBitsToDouble(nBitsNext);

                if(java.lang.Math.abs(n1-n2) > n1*0.2)
                    throw new VerificationException("Network provided difficulty bits do not match what was calculated: " +
                            receivedDifficulty.toString(16) + " vs " + calcDiff.toString(16));

            }
            else
            {
                if (calcDiff.compareTo(receivedDifficulty) != 0)
                    throw new VerificationException("Network provided difficulty bits do not match what was calculated: " +
                            receivedDifficulty.toString(16) + " vs " + calcDiff.toString(16));
            }

            }*/
        }
    }

    static double ConvertBitsToDouble(long nBits){
        long nShift = (nBits >> 24) & 0xff;

        double dDiff =
                (double)0x0000ffff / (double)(nBits & 0x00ffffff);

        while (nShift < 29)
        {
            dDiff *= 256.0;
            nShift++;
        }
        while (nShift > 29)
        {
            dDiff /= 256.0;
            nShift--;
        }

        return dDiff;
    }

    private void checkTestnetDifficulty(StoredBlock storedPrev, UldBlock prev, UldBlock next) throws VerificationException, BlockStoreException {
        //checkState(lock.isHeldByCurrentThread());
        // After 15th February 2012 the rules on the testnet change to avoid people running up the difficulty
        // and then leaving, making it too hard to mine a block. On non-difficulty transition points, easy
        // blocks are allowed if there has been a span of 20 minutes without one.
        final long timeDelta = next.getTimeSeconds() - prev.getTimeSeconds();
        // There is an integer underflow bug in bitcoin-qt that means mindiff blocks are accepted when time
        // goes backwards.
        //if (timeDelta > params.TARGET_SPACING * 2) {
        //    if (next.getDifficultyTargetAsInteger().equals(params.getMaxTarget()))
        //        return;
        //    else throw new VerificationException("Unexpected change in difficulty");
        //}
        if (timeDelta >=0 && timeDelta <= params.TARGET_SPACING * 2) {
            // Walk backwards until we find a block that doesn't have the easiest proof of work, then check
            // that difficulty is equal to that one.
            StoredBlock cursor = storedPrev;
            while (!cursor.getHeader().equals(params.getGenesisBlock()) &&
                    cursor.getHeight() % params.getInterval() != 0 &&
                    cursor.getHeader().getDifficultyTargetAsInteger().equals(params.getMaxTarget()))
                cursor = cursor.getPrev(blockStore);
            BigInteger cursorTarget = cursor.getHeader().getDifficultyTargetAsInteger();
            BigInteger newTarget = next.getDifficultyTargetAsInteger();
//            if (!cursorTarget.equals(newTarget))
//                throw new VerificationException("Testnet block transition that is not allowed: " +
//                        Long.toHexString(cursor.getHeader().getDifficultyTarget()) + " vs " +
//                        Long.toHexString(next.getDifficultyTarget()));
        }
    }

    private void UlordGravityWave(StoredBlock storedPrev, UldBlock nextBlock) {
        /* current difficulty formula, limecoin - DarkGravity*/
        StoredBlock BlockLastSolved = storedPrev;
        StoredBlock BlockReading = storedPrev;
        UldBlock BlockCreating = nextBlock;
        //BlockCreating = BlockCreating;
        long nBlockTimeAverage = 0;
        long nBlockTimeAveragePrev = 0;
        long nBlockTimeCount = 0;
        long nBlockTimeSum2 = 0;
        long nBlockTimeCount2 = 0;
        long LastBlockTime = 0;
        long PastBlocksMin = 14;
        long PastBlocksMax = 140;
        long CountBlocks = 0;
        BigInteger PastDifficultyAverage = BigInteger.valueOf(0);
        BigInteger PastDifficultyAveragePrev = BigInteger.valueOf(0);

        //if (BlockLastSolved == NULL || BlockLastSolved->nHeight == 0 || BlockLastSolved->nHeight < PastBlocksMin) { return bnProofOfWorkLimit.GetCompact(); }
        if (BlockLastSolved == null || BlockLastSolved.getHeight() == 0 || (long)BlockLastSolved.getHeight() < PastBlocksMin)
        { verifyDifficulty(params.getMaxTarget(), storedPrev, nextBlock); }

        for (int i = 1; BlockReading != null && BlockReading.getHeight() > 0; i++) {
            if (PastBlocksMax > 0 && i > PastBlocksMax) {
                break;
            }
            CountBlocks++;

            if(CountBlocks <= PastBlocksMin) {
                if (CountBlocks == 1) { PastDifficultyAverage = BlockReading.getHeader().getDifficultyTargetAsInteger(); }
                else {
                    //PastDifficultyAverage = ((CBigNum().SetCompact(BlockReading->nBits) - PastDifficultyAveragePrev) / CountBlocks) + PastDifficultyAveragePrev;
                    PastDifficultyAverage = BlockReading.getHeader().getDifficultyTargetAsInteger().subtract(PastDifficultyAveragePrev).divide(BigInteger.valueOf(CountBlocks)).add(PastDifficultyAveragePrev);
                }
                PastDifficultyAveragePrev = PastDifficultyAverage;
            }

            if(LastBlockTime > 0) {
                long Diff = (LastBlockTime - BlockReading.getHeader().getTimeSeconds());
                //if(Diff < 0)
                //   Diff = 0;
                if(nBlockTimeCount <= PastBlocksMin) {
                    nBlockTimeCount++;

                    if (nBlockTimeCount == 1) { nBlockTimeAverage = Diff; }
                    else { nBlockTimeAverage = ((Diff - nBlockTimeAveragePrev) / nBlockTimeCount) + nBlockTimeAveragePrev; }
                    nBlockTimeAveragePrev = nBlockTimeAverage;
                }
                nBlockTimeCount2++;
                nBlockTimeSum2 += Diff;
            }
            LastBlockTime = BlockReading.getHeader().getTimeSeconds();

            //if (BlockReading->pprev == NULL)
            try {
                StoredBlock BlockReadingPrev = blockStore.get(BlockReading.getHeader().getPrevBlockHash());
                if (BlockReadingPrev == null) {
                    //assert(BlockReading); break;
                    return;
                }
                BlockReading = BlockReadingPrev;
            } catch(BlockStoreException x) {
                return;
            }
        }

        BigInteger bnNew = PastDifficultyAverage;
        if (nBlockTimeCount != 0 && nBlockTimeCount2 != 0) {
            double SmartAverage = ((((double)nBlockTimeAverage)*0.7)+(((double)nBlockTimeSum2 / (double)nBlockTimeCount2)*0.3));
            if(SmartAverage < 1) SmartAverage = 1;
            double Shift = params.TARGET_SPACING/SmartAverage;

            double fActualTimespan = (((double)CountBlocks*(double)params.TARGET_SPACING)/Shift);
            double fTargetTimespan = ((double)CountBlocks*params.TARGET_SPACING);
            if (fActualTimespan < fTargetTimespan/3)
                fActualTimespan = fTargetTimespan/3;
            if (fActualTimespan > fTargetTimespan*3)
                fActualTimespan = fTargetTimespan*3;

            long nActualTimespan = (long)fActualTimespan;
            long nTargetTimespan = (long)fTargetTimespan;

            // Retarget
            bnNew = bnNew.multiply(BigInteger.valueOf(nActualTimespan));
            bnNew = bnNew.divide(BigInteger.valueOf(nTargetTimespan));
        }
        verifyDifficulty(bnNew, storedPrev, nextBlock);

        /*if (bnNew > bnProofOfWorkLimit){
            bnNew = bnProofOfWorkLimit;
        }

        return bnNew.GetCompact();*/
    }

    private void UlordGravityWave3(StoredBlock storedPrev, UldBlock nextBlock) {
        /* current difficulty formula, darkcoin - DarkGravity v3, written by Evan Duffield - evan@darkcoin.io */
        StoredBlock BlockLastSolved = storedPrev;
        StoredBlock BlockReading = storedPrev;
        UldBlock BlockCreating = nextBlock;
        BlockCreating = BlockCreating;
        long nActualTimespan = 0;
        long LastBlockTime = 0;
        long PastBlocksMin = 24;
        long PastBlocksMax = 24;
        long CountBlocks = 0;
        BigInteger PastDifficultyAverage = BigInteger.ZERO;
        BigInteger PastDifficultyAveragePrev = BigInteger.ZERO;

        if (BlockLastSolved == null || BlockLastSolved.getHeight() == 0 || BlockLastSolved.getHeight() < PastBlocksMin) {
            verifyDifficulty(params.getMaxTarget(), storedPrev, nextBlock);
            return;
        }

        for (int i = 1; BlockReading != null && BlockReading.getHeight() > 0; i++) {
            if (PastBlocksMax > 0 && i > PastBlocksMax) { break; }
            CountBlocks++;

            if(CountBlocks <= PastBlocksMin) {
                if (CountBlocks == 1) { PastDifficultyAverage = BlockReading.getHeader().getDifficultyTargetAsInteger(); }
                else { PastDifficultyAverage = ((PastDifficultyAveragePrev.multiply(BigInteger.valueOf(CountBlocks)).add(BlockReading.getHeader().getDifficultyTargetAsInteger()).divide(BigInteger.valueOf(CountBlocks + 1)))); }
                PastDifficultyAveragePrev = PastDifficultyAverage;
            }

            if(LastBlockTime > 0) {
                long Diff = (LastBlockTime - BlockReading.getHeader().getTimeSeconds());
                nActualTimespan += Diff;
            }
            LastBlockTime = BlockReading.getHeader().getTimeSeconds();

            try {
                StoredBlock BlockReadingPrev = blockStore.get(BlockReading.getHeader().getPrevBlockHash());
                if (BlockReadingPrev == null)
                {
                    //assert(BlockReading); break;
                    return;
                }
                BlockReading = BlockReadingPrev;
            } catch(BlockStoreException x) {
                return;
            }
        }

        BigInteger bnNew= PastDifficultyAverage;

        long nTargetTimespan = CountBlocks*params.TARGET_SPACING;//nTargetSpacing;

        if (nActualTimespan < nTargetTimespan/3)
            nActualTimespan = nTargetTimespan/3;
        if (nActualTimespan > nTargetTimespan*3)
            nActualTimespan = nTargetTimespan*3;

        // Retarget
        bnNew = bnNew.multiply(BigInteger.valueOf(nActualTimespan));
        bnNew = bnNew.divide(BigInteger.valueOf(nTargetTimespan));
        verifyDifficulty(bnNew, storedPrev, nextBlock);

    }

    /**
     * Returns the hashes of the currently stored orphan blocks and then deletes them from this objects storage.
     * Used by Peer when a filter exhaustion event has occurred and thus any orphan blocks that have been downloaded
     * might be inaccurate/incomplete.
     */
    public Set<Sha256Hash> drainOrphanBlocks() {
        try {
            Set<Sha256Hash> hashes = new HashSet<Sha256Hash>(orphanBlocks.keySet());
            orphanBlocks.clear();
            return hashes;
        } finally {
        }
    }

    // expensiveChecks enables checks that require looking at blocks further back in the chain
    // than the previous one when connecting (eg median timestamp check)
    // It could be exposed, but for now we just set it to shouldVerifyTransactions()
    private void connectBlock(final UldBlock block, StoredBlock storedPrev, boolean expensiveChecks,
                              @Nullable final List<Sha256Hash> filteredTxHashList,
                              @Nullable final Map<Sha256Hash, UldTransaction> filteredTxn) throws BlockStoreException, VerificationException {
        boolean filtered = filteredTxHashList != null && filteredTxn != null;
        // Check that we aren't connecting a block that fails a checkpoint check
        if (!params.passesCheckpoint(storedPrev.getHeight() + 1, block.getHash()))
            throw new VerificationException("Block failed checkpoint lockin at " + (storedPrev.getHeight() + 1));
        if (shouldVerifyTransactions()) {
            checkNotNull(block.transactions);
            for (UldTransaction tx : block.transactions)
                if (!tx.isFinal(storedPrev.getHeight() + 1, block.getTimeSeconds()))
                   throw new VerificationException("Block contains non-final transaction");
        }
        
        StoredBlock head = getChainHead();
        if (storedPrev.equals(head)) {
            if (filtered && filteredTxn.size() > 0)  {
                log.debug("Block {} connects to top of best chain with {} transaction(s) of which we were sent {}",
                        block.getHashAsString(), filteredTxHashList.size(), filteredTxn.size());
                for (Sha256Hash hash : filteredTxHashList) log.debug("  matched tx {}", hash);
            }
            if (expensiveChecks && block.getTimeSeconds() <= getMedianTimestampOfRecentBlocks(head, blockStore))
                throw new VerificationException("Block's timestamp is too early");

            // BIP 66 & 65: Enforce block version 3/4 once they are a supermajority of blocks
            // NOTE: This requires 1,000 blocks since the last checkpoint (on main
            // net, less on test) in order to be applied. It is also limited to
            // stopping addition of new v2/3 blocks to the tip of the chain.
            if (block.getVersion() == UldBlock.BLOCK_VERSION_BIP34
                || block.getVersion() == UldBlock.BLOCK_VERSION_BIP66) {
                final Integer count = versionTally.getCountAtOrAbove(block.getVersion() + 1);
                if (count != null
                    && count >= params.getMajorityRejectBlockOutdated()) {
                    throw new VerificationException.BlockVersionOutOfDate(block.getVersion());
                }
            }

            // This block connects to the best known block, it is a normal continuation of the system.
            StoredBlock newStoredBlock = addToBlockStore(storedPrev,
                    block.transactions == null ? block : block.cloneAsHeader());
            versionTally.add(block.getVersion());
            setChainHead(newStoredBlock);
            log.debug("Chain is now {} blocks high, running listeners", newStoredBlock.getHeight());
        } else {
            // This block connects to somewhere other than the top of the best known chain. We treat these differently.
            //
            // Note that we send the transactions to the wallet FIRST, even if we're about to re-organize this block
            // to become the new best chain head. This simplifies handling of the re-org in the Wallet class.
            StoredBlock newBlock = storedPrev.build(block);
            boolean haveNewBestChain = newBlock.moreWorkThan(head);
            if (haveNewBestChain) {
                log.info("Block is causing a re-organize");
            } else {
                StoredBlock splitPoint = findSplit(newBlock, head, blockStore);
                if (splitPoint != null && splitPoint.equals(newBlock)) {
                    // newStoredBlock is a part of the same chain, there's no fork. This happens when we receive a block
                    // that we already saw and linked into the chain previously, which isn't the chain head.
                    // Re-processing it is confusing for the wallet so just skip.
                    log.warn("Saw duplicated block in main chain at height {}: {}",
                            newBlock.getHeight(), newBlock.getHeader().getHash());
                    return;
                }
                if (splitPoint == null) {
                    // This should absolutely never happen
                    // (lets not write the full block to disk to keep any bugs which allow this to happen
                    //  from writing unreasonable amounts of data to disk)
                    throw new VerificationException("Block forks the chain but splitPoint is null");
                } else {
                    // We aren't actually spending any transactions (yet) because we are on a fork
                    addToBlockStore(storedPrev, block);
                    int splitPointHeight = splitPoint.getHeight();
                    String splitPointHash = splitPoint.getHeader().getHashAsString();
                    log.info("Block forks the chain at height {}/block {}, but it did not cause a reorganize:\n{}",
                            splitPointHeight, splitPointHash, newBlock.getHeader().getHashAsString());
                }
            }
            
            if (haveNewBestChain)
                handleNewBestChain(storedPrev, newBlock, block, expensiveChecks);
        }
    }

    /**
     * Gets the median timestamp of the last 11 blocks
     */
    private static long getMedianTimestampOfRecentBlocks(StoredBlock storedBlock,
                                                         UldBlockStore store) throws BlockStoreException {
        long[] timestamps = new long[11];
        int unused = 9;
        timestamps[10] = storedBlock.getHeader().getTimeSeconds();
        while (unused >= 0 && (storedBlock = storedBlock.getPrev(store)) != null)
            timestamps[unused--] = storedBlock.getHeader().getTimeSeconds();
        
        Arrays.sort(timestamps, unused+1, 11);
        return timestamps[unused + (11-unused)/2];
    }
    
    /**
     * Called as part of connecting a block when the new block results in a different chain having higher total work.
     * 
     * if (shouldVerifyTransactions)
     *     Either newChainHead needs to be in the block store as a FullStoredBlock, or (block != null && block.transactions != null)
     */
    private void handleNewBestChain(StoredBlock storedPrev, StoredBlock newChainHead, UldBlock block, boolean expensiveChecks)
            throws BlockStoreException, VerificationException {
        // This chain has overtaken the one we currently believe is best. Reorganize is required.
        //
        // Firstly, calculate the block at which the chain diverged. We only need to examine the
        // chain from beyond this block to find differences.
        StoredBlock head = getChainHead();
        final StoredBlock splitPoint = findSplit(newChainHead, head, blockStore);
        log.info("Re-organize after split at height {}", splitPoint.getHeight());
        log.info("Old chain head: {}", head.getHeader().getHashAsString());
        log.info("New chain head: {}", newChainHead.getHeader().getHashAsString());
        log.info("Split at block: {}", splitPoint.getHeader().getHashAsString());
        // Then build a list of all blocks in the old part of the chain and the new part.
        final LinkedList<StoredBlock> oldBlocks = getPartialChain(head, splitPoint, blockStore);
        final LinkedList<StoredBlock> newBlocks = getPartialChain(newChainHead, splitPoint, blockStore);
        // Disconnect each transaction in the previous main chain that is no longer in the new main chain
        StoredBlock storedNewHead = splitPoint;
        if (shouldVerifyTransactions()) {
        } else {
            // (Finally) write block to block store
            storedNewHead = addToBlockStore(storedPrev, newChainHead.getHeader());
        }
        // Update the pointer to the best known block.
        setChainHead(storedNewHead);
    }

    /**
     * Returns the set of contiguous blocks between 'higher' and 'lower'. Higher is included, lower is not.
     */
    private static LinkedList<StoredBlock> getPartialChain(StoredBlock higher, StoredBlock lower, UldBlockStore store) throws BlockStoreException {
        checkArgument(higher.getHeight() > lower.getHeight(), "higher and lower are reversed");
        LinkedList<StoredBlock> results = new LinkedList<StoredBlock>();
        StoredBlock cursor = higher;
        while (true) {
            results.add(cursor);
            cursor = checkNotNull(cursor.getPrev(store), "Ran off the end of the chain");
            if (cursor.equals(lower)) break;
        }
        return results;
    }

    /**
     * Locates the point in the chain at which newStoredBlock and chainHead diverge. Returns null if no split point was
     * found (ie they are not part of the same chain). Returns newChainHead or chainHead if they don't actually diverge
     * but are part of the same chain.
     */
    private static StoredBlock findSplit(StoredBlock newChainHead, StoredBlock oldChainHead,
                                         UldBlockStore store) throws BlockStoreException {
        StoredBlock currentChainCursor = oldChainHead;
        StoredBlock newChainCursor = newChainHead;
        // Loop until we find the block both chains have in common. Example:
        //
        //    A -> B -> C -> D
        //         \--> E -> F -> G
        //
        // findSplit will return block B. oldChainHead = D and newChainHead = G.
        while (!currentChainCursor.equals(newChainCursor)) {
            if (currentChainCursor.getHeight() > newChainCursor.getHeight()) {
                currentChainCursor = currentChainCursor.getPrev(store);
                checkNotNull(currentChainCursor, "Attempt to follow an orphan chain");
            } else {
                newChainCursor = newChainCursor.getPrev(store);
                checkNotNull(newChainCursor, "Attempt to follow an orphan chain");
            }
        }
        return currentChainCursor;
    }

    /**
     * @return the height of the best known chain, convenience for <tt>getChainHead().getHeight()</tt>.
     */
    public final int getBestChainHeight() {
        return getChainHead().getHeight();
    }

    public enum NewBlockType {
        BEST_CHAIN,
        SIDE_CHAIN
    }

    protected void setChainHead(StoredBlock chainHead) throws BlockStoreException {
        doSetChainHead(chainHead);
        synchronized (chainHeadLock) {
            this.chainHead = chainHead;
        }
    }

    /**
     * For each block in orphanBlocks, see if we can now fit it on top of the chain and if so, do so.
     */
    private List<OrphanBlock> tryConnectingOrphans() throws VerificationException, BlockStoreException {
        // For each block in our orphan list, try and fit it onto the head of the chain. If we succeed remove it
        // from the list and keep going. If we changed the head of the list at the end of the round try again until
        // we can't fit anything else on the top.
        //
        // This algorithm is kind of crappy, we should do a topo-sort then just connect them in order, but for small
        // numbers of orphan blocks it does OK.
        List<OrphanBlock> orphansAdded = new ArrayList<OrphanBlock>();
        int blocksConnectedThisRound;
        do {
            blocksConnectedThisRound = 0;
            Iterator<OrphanBlock> iter = orphanBlocks.values().iterator();
            while (iter.hasNext()) {
                OrphanBlock orphanBlock = iter.next();
                // Look up the blocks previous.
                StoredBlock prev = getStoredBlockInCurrentScope(orphanBlock.block.getPrevBlockHash());
                if (prev == null) {
                    // This is still an unconnected/orphan block.
                    log.debug("Orphan block {} is not connectable right now", orphanBlock.block.getHash());
                    continue;
                }
                // Otherwise we can connect it now.
                // False here ensures we don't recurse infinitely downwards when connecting huge chains.
                log.info("Connected orphan {}", orphanBlock.block.getHash());
                add(orphanBlock.block, false, orphanBlock.filteredTxHashes, orphanBlock.filteredTxn, orphanBlock.filteredBlock);
                orphansAdded.add(orphanBlock);
                iter.remove();
                blocksConnectedThisRound++;
            }
            if (blocksConnectedThisRound > 0) {
                log.info("Connected {} orphan blocks.", blocksConnectedThisRound);
            }
        } while (blocksConnectedThisRound > 0);
        return orphansAdded;
    }

    /**
     * Returns the block at the head of the current best chain. This is the block which represents the greatest
     * amount of cumulative work done.
     */
    public StoredBlock getChainHead() {
        synchronized (chainHeadLock) {
            return chainHead;
        }
    }

    /**
     * An orphan block is one that does not connect to the chain anywhere (ie we can't find its parent, therefore
     * it's an orphan). Typically this occurs when we are downloading the chain and didn't reach the head yet, and/or
     * if a block is solved whilst we are downloading. It's possible that we see a small amount of orphan blocks which
     * chain together, this method tries walking backwards through the known orphan blocks to find the bottom-most.
     *
     * @return from or one of froms parents, or null if "from" does not identify an orphan block
     */
    @Nullable
    public UldBlock getOrphanRoot(Sha256Hash from) {
        try {
            OrphanBlock cursor = orphanBlocks.get(from);
            if (cursor == null)
                return null;
            OrphanBlock tmp;
            while ((tmp = orphanBlocks.get(cursor.block.getPrevBlockHash())) != null) {
                cursor = tmp;
            }
            return cursor.block;
        } finally {
        }
    }

    /** Returns true if the given block is currently in the orphan blocks list. */
    public boolean isOrphan(Sha256Hash block) {
        try {
            return orphanBlocks.containsKey(block);
        } finally {
        }
    }

    /**
     * Returns an estimate of when the given block will be reached, assuming a perfect 10 minute average for each
     * block. This is useful for turning transaction lock times into human readable times. Note that a height in
     * the past will still be estimated, even though the time of solving is actually known (we won't scan backwards
     * through the chain to obtain the right answer).
     */
    public Date estimateBlockTime(int height) {
        synchronized (chainHeadLock) {
            long offset = height - chainHead.getHeight();
            long headTime = chainHead.getHeader().getTimeSeconds();
            long estimated = (headTime * 1000) + (1000L * 60L * 10L * offset);
            return new Date(estimated);
        }
    }

    /**
     * The false positive rate is the average over all blockchain transactions of:
     *
     * - 1.0 if the transaction was false-positive (was irrelevant to all listeners)
     * - 0.0 if the transaction was relevant or filtered out
     */
    public double getFalsePositiveRate() {
        return falsePositiveRate;
    }

    /*
     * We completed handling of a filtered block. Update false-positive estimate based
     * on the total number of transactions in the original block.
     *
     * count includes filtered transactions, transactions that were passed in and were relevant
     * and transactions that were false positives (i.e. includes all transactions in the block).
     */
    protected void trackFilteredTransactions(int count) {
        // Track non-false-positives in batch.  Each non-false-positive counts as
        // 0.0 towards the estimate.
        //
        // This is slightly off because we are applying false positive tracking before non-FP tracking,
        // which counts FP as if they came at the beginning of the block.  Assuming uniform FP
        // spread in a block, this will somewhat underestimate the FP rate (5% for 1000 tx block).
        double alphaDecay = Math.pow(1 - FP_ESTIMATOR_ALPHA, count);

        // new_rate = alpha_decay * new_rate
        falsePositiveRate = alphaDecay * falsePositiveRate;

        double betaDecay = Math.pow(1 - FP_ESTIMATOR_BETA, count);

        // trend = beta * (new_rate - old_rate) + beta_decay * trend
        falsePositiveTrend =
                FP_ESTIMATOR_BETA * count * (falsePositiveRate - previousFalsePositiveRate) +
                betaDecay * falsePositiveTrend;

        // new_rate += alpha_decay * trend
        falsePositiveRate += alphaDecay * falsePositiveTrend;

        // Stash new_rate in old_rate
        previousFalsePositiveRate = falsePositiveRate;
    }

    /* Irrelevant transactions were received.  Update false-positive estimate. */
    void trackFalsePositives(int count) {
        // Track false positives in batch by adding alpha to the false positive estimate once per count.
        // Each false positive counts as 1.0 towards the estimate.
        falsePositiveRate += FP_ESTIMATOR_ALPHA * count;
        if (count > 0)
            log.debug("{} false positives, current rate = {} trend = {}", count, falsePositiveRate, falsePositiveTrend);
    }

    /** Resets estimates of false positives. Used when the filter is sent to the peer. */
    public void resetFalsePositiveEstimate() {
        falsePositiveRate = 0;
        falsePositiveTrend = 0;
        previousFalsePositiveRate = 0;
    }

    protected VersionTally getVersionTally() {
        return versionTally;
    }
}
