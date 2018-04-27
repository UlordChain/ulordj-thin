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

package co.usc.ulordj.wallet;

import co.usc.ulordj.core.*;
import com.google.common.collect.*;
import net.jcip.annotations.*;
import co.usc.ulordj.core.UldECKey;
import co.usc.ulordj.script.*;
import co.usc.ulordj.signers.*;
import org.slf4j.*;

import javax.annotation.*;
import java.util.*;

import static com.google.common.base.Preconditions.*;

// To do list:
//
// - Take all wallet-relevant data out of Transaction and put it into WalletTransaction. Make Transaction immutable.
// - Only store relevant transaction outputs, don't bother storing the rest of the data. Big RAM saving.
// - Split block chain and tx output tracking into a superclass that doesn't have any key or spending related code.
// - Simplify how transactions are tracked and stored: in particular, have the wallet maintain positioning information
//   for transactions independent of the transactions themselves, so the timeline can be walked without having to
//   process and sort every single transaction.
// - Split data persistence out into a backend class and make the wallet transactional, so we can store a wallet
//   in a database not just in RAM.
// - Make clearing of transactions able to only rewind the wallet a certain distance instead of all blocks.
// - Make it scale:
//     - eliminate all the algorithms with quadratic complexity (or worse)
//     - don't require everything to be held in RAM at once
//     - consider allowing eviction of no longer re-orgable transactions or keys that were used up
//
// Finally, find more ways to break the class up and decompose it. Currently every time we move code out, other code
// fills up the lines saved!

/**
 * <p>A Wallet stores keys and a record of transactions that send and receive value from those keys. Using these,
 * it is able to create new transactions that spend the recorded transactions, and this is the fundamental operation
 * of the Bitcoin protocol.</p>
 *
 * <p>To learn more about this class, read <b><a href="https://bitcoinj.github.io/working-with-the-wallet">
 *     working with the wallet.</a></b></p>
 *
 * <p>To fill up a Wallet with transactions, you need to use it in combination with a {@link UldBlockChain} and various
 * other objects, see the <a href="https://bitcoinj.github.io/getting-started">Getting started</a> tutorial
 * on the website to learn more about how to set everything up.</p>
 *
 * <p>Wallets can be serialized using protocol buffers. You need to save the wallet whenever it changes, there is an
 * auto-save feature that simplifies this for you although you're still responsible for manually triggering a save when
 * your app is about to quit because the auto-save feature waits a moment before actually committing to disk to avoid IO
 * thrashing when the wallet is changing very fast (eg due to a block chain sync). See
 * {@link Wallet#autosaveToFile(java.io.File, long, java.util.concurrent.TimeUnit, co.usc.ulordj.wallet.WalletFiles.Listener)}
 * for more information about this.</p>
 */
public class Wallet
    implements KeyBag, TransactionBag {
    private static final Logger log = LoggerFactory.getLogger(Wallet.class);


    // A list of scripts watched by this wallet.
    @GuardedBy("keyChainGroupLock") private Set<Script> watchedScripts;

    protected final Context context;
    protected final NetworkParameters params;

    @Nullable private Sha256Hash lastBlockSeenHash;
    private int lastBlockSeenHeight;
    private long lastBlockSeenTimeSecs;

    // If a TX hash appears in this set then notifyNewBestBlock will ignore it, as its confidence was already set up
    // in receive() via Transaction.setBlockAppearance(). As the BlockChain always calls notifyNewBestBlock even if
    // it sent transactions to the wallet, without this we'd double count.
    private HashSet<Sha256Hash> ignoreNextNewBlock;

    private boolean insideReorg;

    protected CoinSelector coinSelector = null;

    // Objects that perform transaction signing. Applied subsequently one after another
    @GuardedBy("lock") private List<TransactionSigner> signers;

    // If this is set then the wallet selects spendable candidate outputs from a UTXO provider.
    @Nullable private volatile UTXOProvider vUTXOProvider;

    /**
     * Creates a new, empty wallet with a randomly chosen seed and no transactions. Make sure to provide for sufficient
     * backup! Any keys will be derived from the seed. If you want to restore a wallet from disk instead, see
     * {@link #loadFromFile}.
     */
    public Wallet(NetworkParameters params) {
        this(Context.getOrCreate(params));
    }

    /**
     * Creates a new, empty wallet with a randomly chosen seed and no transactions. Make sure to provide for sufficient
     * backup! Any keys will be derived from the seed. If you want to restore a wallet from disk instead, see
     * {@link #loadFromFile}.
     */
    public Wallet(Context context) {
        this.context = context;
        this.params = context.getParams();
        watchedScripts = Sets.newHashSet();
        // Use a linked hash map to ensure ordering of event listeners is correct.
        createTransientState();
    }

    private void createTransientState() {
        ignoreNextNewBlock = new HashSet<Sha256Hash>();
    }

    public NetworkParameters getNetworkParameters() {
        return params;
    }

    /******************************************************************************************************************/

    //region Key Management

    /**
     * Returns a snapshot of the watched scripts. This view is not live.
     */
    public List<Script> getWatchedScripts() {
        try {
            return new ArrayList<Script>(watchedScripts);
        } finally {
        }
    }

    /**
     * Return true if we are watching this address.
     */
    public boolean isAddressWatched(Address address) {
        Script script = ScriptBuilder.createOutputScript(address);
        return isWatchedScript(script);
    }

    /**
     * Same as {@link #addWatchedAddress(Address, long)} with the current time as the creation time.
     */
    public boolean addWatchedAddress(final Address address) {
        long now = Utils.currentTimeMillis() / 1000;
        return addWatchedAddresses(Lists.newArrayList(address), now) == 1;
    }

    /**
     * Adds the given address to the wallet to be watched. Outputs can be retrieved by {@link #getWatchedOutputs(boolean)}.
     *
     * @param creationTime creation time in seconds since the epoch, for scanning the blockchain
     * @return whether the address was added successfully (not already present)
     */
    public boolean addWatchedAddress(final Address address, long creationTime) {
        return addWatchedAddresses(Lists.newArrayList(address), creationTime) == 1;
    }

    /**
     * Adds the given address to the wallet to be watched. Outputs can be retrieved
     * by {@link #getWatchedOutputs(boolean)}.
     *
     * @return how many addresses were added successfully
     */
    public int addWatchedAddresses(final List<Address> addresses, long creationTime) {
        List<Script> scripts = Lists.newArrayList();

        for (Address address : addresses) {
            Script script = ScriptBuilder.createOutputScript(address);
            script.setCreationTimeSeconds(creationTime);
            scripts.add(script);
        }

        return addWatchedScripts(scripts);
    }

    /**
     * Adds the given output scripts to the wallet to be watched. Outputs can be retrieved by {@link #getWatchedOutputs(boolean)}.
     * If a script is already being watched, the object is replaced with the one in the given list. As {@link Script}
     * equality is defined in terms of program bytes only this lets you update metadata such as creation time. Note that
     * you should be careful not to add scripts with a creation time of zero (the default!) because otherwise it will
     * disable the important wallet checkpointing optimisation.
     *
     * @return how many scripts were added successfully
     */
    public int addWatchedScripts(final List<Script> scripts) {
        int added = 0;
        try {
            for (final Script script : scripts) {
                // Script.equals/hashCode() only takes into account the program bytes, so this step lets the user replace
                // a script in the wallet with an incorrect creation time.
                if (watchedScripts.contains(script))
                    watchedScripts.remove(script);
                if (script.getCreationTimeSeconds() == 0)
                    log.warn("Adding a script to the wallet with a creation time of zero, this will disable the checkpointing optimization!    {}", script);
                watchedScripts.add(script);
                added++;
            }
        } finally {
        }
        return added;
    }

    /**
     * Removes the given output scripts from the wallet that were being watched.
     *
     * @return true if successful
     */
    public boolean removeWatchedAddress(final Address address) {
        return removeWatchedAddresses(ImmutableList.of(address));
    }

    /**
     * Removes the given output scripts from the wallet that were being watched.
     *
     * @return true if successful
     */
    public boolean removeWatchedAddresses(final List<Address> addresses) {
        List<Script> scripts = Lists.newArrayList();

        for (Address address : addresses) {
            Script script = ScriptBuilder.createOutputScript(address);
            scripts.add(script);
        }

        return removeWatchedScripts(scripts);
    }

    /**
     * Removes the given output scripts from the wallet that were being watched.
     *
     * @return true if successful
     */
    public boolean removeWatchedScripts(final List<Script> scripts) {
        try {
            for (final Script script : scripts) {
                if (!watchedScripts.contains(script))
                    continue;

                watchedScripts.remove(script);
            }

            return true;
        } finally {
        }
    }

    /**
     * Returns all addresses watched by this wallet.
     */
    public List<Address> getWatchedAddresses() {
        try {
            List<Address> addresses = new LinkedList<Address>();
            for (Script script : watchedScripts)
                if (script.isSentToAddress())
                    addresses.add(script.getToAddress(params));
            return addresses;
        } finally {
        }
    }


    /**
     * Locates a keypair from the basicKeyChain given the hash of the public key. This is needed when finding out which
     * key we need to use to redeem a transaction output.
     *
     * @return UldECKey object or null if no such key was found.
     */
    @Override
    @Nullable
    public UldECKey findKeyFromPubHash(byte[] pubkeyHash) {
        return null;
    }

    /** {@inheritDoc} */
    @Override
    public boolean isPubKeyHashMine(byte[] pubkeyHash) {
        return findKeyFromPubHash(pubkeyHash) != null;
    }

    /** {@inheritDoc} */
    @Override
    public boolean isWatchedScript(Script script) {
        try {
            return watchedScripts.contains(script);
        } finally {
        }
    }

    /**
     * Locates a keypair from the basicKeyChain given the raw public key bytes.
     * @return UldECKey or null if no such key was found.
     */
    @Override
    @Nullable
    public UldECKey findKeyFromPubKey(byte[] pubkey) {
        return null;
    }

    /** {@inheritDoc} */
    @Override
    public boolean isPubKeyMine(byte[] pubkey) {
        return findKeyFromPubKey(pubkey) != null;
    }

    /**
     * Locates a redeem data (redeem script and keys) from the keyChainGroup given the hash of the script.
     * Returns RedeemData object or null if no such data was found.
     */
    @Nullable
    @Override
    public RedeemData findRedeemDataFromScriptHash(byte[] payToScriptHash) {
        // RSK: Method should be overriden by subclasses
        throw new UnsupportedOperationException();
    }

    /** {@inheritDoc} */
    @Override
    public boolean isPayToScriptHashMine(byte[] payToScriptHash) {
        return findRedeemDataFromScriptHash(payToScriptHash) != null;
    }


    //endregion

    /******************************************************************************************************************/


    /** Returns the parameters this wallet was created with. */
    public NetworkParameters getParams() {
        return params;
    }

    /** Returns the API context that this wallet was created with. */
    public Context getContext() {
        return context;
    }




    //endregion

    /******************************************************************************************************************/


    /******************************************************************************************************************/

    //region Vending transactions and other internal state


    @Override
    public String toString() {
        return toString(false, true, true, null);
    }


    /**
     * Formats the wallet as a human readable piece of text. Intended for debugging, the format is not meant to be
     * stable or human readable.
     * @param includePrivateKeys Whether raw private key data should be included.
     * @param includeTransactions Whether to print transaction data.
     * @param includeExtensions Whether to print extension data.
     * @param chain If set, will be used to estimate lock times for block timelocked transactions.
     */
    public String toString(boolean includePrivateKeys, boolean includeTransactions, boolean includeExtensions,
                           @Nullable UldAbstractBlockChain chain) {
        try {
            StringBuilder builder = new StringBuilder();
            Coin estimatedBalance = getBalance(BalanceType.ESTIMATED);
            Coin availableBalance = getBalance(BalanceType.AVAILABLE_SPENDABLE);
            builder.append("Wallet containing ").append(estimatedBalance.toFriendlyString()).append(" (spendable: ")
                    .append(availableBalance.toFriendlyString()).append(") in:\n");
            if (!watchedScripts.isEmpty()) {
                builder.append("\nWatched scripts:\n");
                for (Script script : watchedScripts) {
                    builder.append("  ").append(script).append("\n");
                }
            }
            return builder.toString();
        } finally {
        }
    }


    //endregion

    /******************************************************************************************************************/

    //region Balance and balance futures

    /**
     * <p>It's possible to calculate a wallets balance from multiple points of view. This enum selects which
     * {@link #getBalance(BalanceType)} should use.</p>
     *
     * <p>Consider a real-world example: you buy a snack costing $5 but you only have a $10 bill. At the start you have
     * $10 viewed from every possible angle. After you order the snack you hand over your $10 bill. From the
     * perspective of your wallet you have zero dollars (AVAILABLE). But you know in a few seconds the shopkeeper
     * will give you back $5 change so most people in practice would say they have $5 (ESTIMATED).</p>
     *
     * <p>The fact that the wallet can track transactions which are not spendable by itself ("watching wallets") adds
     * another type of balance to the mix. Although the wallet won't do this by default, advanced use cases that
     * override the relevancy checks can end up with a mix of spendable and unspendable transactions.</p>
     */
    public enum BalanceType {
        /**
         * Balance calculated assuming all pending transactions are in fact included into the best chain by miners.
         * This includes the value of immature coinbase transactions.
         */
        ESTIMATED,

        /**
         * Balance that could be safely used to create new spends, if we had all the needed private keys. This is
         * whatever the default coin selector would make available, which by default means transaction outputs with at
         * least 1 confirmation and pending transactions created by our own wallet which have been propagated across
         * the network. Whether we <i>actually</i> have the private keys or not is irrelevant for this balance type.
         */
        AVAILABLE,

        /** Same as ESTIMATED but only for outputs we have the private keys for and can sign ourselves. */
        ESTIMATED_SPENDABLE,
        /** Same as AVAILABLE but only for outputs we have the private keys for and can sign ourselves. */
        AVAILABLE_SPENDABLE
    }

    /** @deprecated Use {@link #getBalance()} instead as including watched balances is now the default behaviour */
    @Deprecated
    public Coin getWatchedBalance() {
        return getBalance();
    }

    /** @deprecated Use {@link #getBalance(CoinSelector)} instead as including watched balances is now the default behaviour */
    @Deprecated
    public Coin getWatchedBalance(CoinSelector selector) {
        return getBalance(selector);
    }

    /**
     * Returns the AVAILABLE balance of this wallet. See {@link BalanceType#AVAILABLE} for details on what this
     * means.
     */
    public Coin getBalance() {
        return getBalance(BalanceType.AVAILABLE);
    }

    /**
     * Returns the balance of this wallet as calculated by the provided balanceType.
     */
    public Coin getBalance(BalanceType balanceType) {
        try {
            if (balanceType == BalanceType.AVAILABLE || balanceType == BalanceType.AVAILABLE_SPENDABLE) {
                List<TransactionOutput> candidates = calculateAllSpendCandidates(true, balanceType == BalanceType.AVAILABLE_SPENDABLE);
                CoinSelection selection = coinSelector.select(NetworkParameters.MAX_MONEY, candidates);
                return selection.valueGathered;
            } else if (balanceType == BalanceType.ESTIMATED || balanceType == BalanceType.ESTIMATED_SPENDABLE) {
                List<TransactionOutput> all = calculateAllSpendCandidates(false, balanceType == BalanceType.ESTIMATED_SPENDABLE);
                Coin value = Coin.ZERO;
                for (TransactionOutput out : all) value = value.add(out.getValue());
                return value;
            } else {
                throw new AssertionError("Unknown balance type");  // Unreachable.
            }
        } finally {
        }
    }

    /**
     * Returns the balance that would be considered spendable by the given coin selector, including watched outputs
     * (i.e. balance includes outputs we don't have the private keys for). Just asks it to select as many coins as
     * possible and returns the total.
     */
    public Coin getBalance(CoinSelector selector) {
        try {
            checkNotNull(selector);
            List<TransactionOutput> candidates = calculateAllSpendCandidates(true, false);
            CoinSelection selection = selector.select(params.getMaxMoney(), candidates);
            return selection.valueGathered;
        } finally {
        }
    }

    //endregion

    /******************************************************************************************************************/

    //region Creating and sending transactions

    /**
     * Enumerates possible resolutions for missing signatures.
     */
    public enum MissingSigsMode {
        /** Input script will have OP_0 instead of missing signatures */
        USE_OP_ZERO,
        /**
         * Missing signatures will be replaced by dummy sigs. This is useful when you'd like to know the fee for
         * a transaction without knowing the user's password, as fee depends on size.
         */
        USE_DUMMY_SIG,
        /**
         * If signature is missing, {@link co.usc.ulordj.signers.TransactionSigner.MissingSignatureException}
         * will be thrown for P2SH and {@link UldECKey.MissingPrivateKeyException} for other tx types.
         */
        THROW
    }

    /**
     * Class of exceptions thrown in {@link Wallet#completeTx(SendRequest)}.
     */
    public static class CompletionException extends RuntimeException {}
    /**
     * Thrown if the resultant transaction would violate the dust rules (an output that's too small to be worthwhile).
     */
    public static class DustySendRequested extends CompletionException {}
    /**
     * Thrown if there is more than one OP_RETURN output for the resultant transaction.
     */
    public static class MultipleOpReturnRequested extends CompletionException {}
    /**
     * Thrown when we were trying to empty the wallet, and the total amount of money we were trying to empty after
     * being reduced for the fee was smaller than the min payment. Note that the missing field will be null in this
     * case.
     */
    public static class CouldNotAdjustDownwards extends CompletionException {}
    /**
     * Thrown if the resultant transaction is too big for Bitcoin to process. Try breaking up the amounts of value.
     */
    public static class ExceededMaxTransactionSize extends CompletionException {}

    /**
     * Given a spend request containing an incomplete transaction, makes it valid by adding outputs and signed inputs
     * according to the instructions in the request. The transaction in the request is modified by this method.
     *
     * @param req a SendRequest that contains the incomplete transaction and details for how to make it valid.
     * @throws InsufficientMoneyException if the request could not be completed due to not enough balance.
     * @throws IllegalArgumentException if you try and complete the same SendRequest twice
     * @throws DustySendRequested if the resultant transaction would violate the dust rules.
     * @throws CouldNotAdjustDownwards if emptying the wallet was requested and the output can't be shrunk for fees without violating a protocol rule.
     * @throws ExceededMaxTransactionSize if the resultant transaction is too big for Bitcoin to process.
     * @throws MultipleOpReturnRequested if there is more than one OP_RETURN output for the resultant transaction.
     */
    public void completeTx(SendRequest req) throws InsufficientMoneyException {
        try {
            checkArgument(!req.completed, "Given SendRequest has already been completed.");
            // Calculate the amount of value we need to import.
            Coin value = Coin.ZERO;
            for (TransactionOutput output : req.tx.getOutputs()) {
                value = value.add(output.getValue());
            }

            log.info("Completing send tx with {} outputs totalling {} and a fee of {}/kB", req.tx.getOutputs().size(),
                    value.toFriendlyString(), req.feePerKb.toFriendlyString());

            // If any inputs have already been added, we don't need to get their value from wallet
            Coin totalInput = Coin.ZERO;
            for (TransactionInput input : req.tx.getInputs())
                if (input.getConnectedOutput() != null)
                    totalInput = totalInput.add(input.getConnectedOutput().getValue());
                else
                    log.warn("SendRequest transaction already has inputs but we don't know how much they are worth - they will be added to fee.");
            value = value.subtract(totalInput);

            List<TransactionInput> originalInputs = new ArrayList<TransactionInput>(req.tx.getInputs());

            // Check for dusty sends and the OP_RETURN limit.
            if (req.ensureMinRequiredFee && !req.emptyWallet) { // Min fee checking is handled later for emptyWallet.
                int opReturnCount = 0;
                for (TransactionOutput output : req.tx.getOutputs()) {
                    if (output.isDust())
                        throw new DustySendRequested();
                    if (output.getScriptPubKey().isOpReturn())
                        ++opReturnCount;
                }
                if (opReturnCount > 1) // Only 1 OP_RETURN per transaction allowed.
                    throw new MultipleOpReturnRequested();
            }

            // Calculate a list of ALL potential candidates for spending and then ask a coin selector to provide us
            // with the actual outputs that'll be used to gather the required amount of value. In this way, users
            // can customize coin selection policies. The call below will ignore immature coinbases and outputs
            // we don't have the keys for.
            List<TransactionOutput> candidates = calculateAllSpendCandidates(true, req.missingSigsMode == MissingSigsMode.THROW);

            CoinSelection bestCoinSelection;
            TransactionOutput bestChangeOutput = null;
            List<Coin> updatedOutputValues = null;
            if (!req.emptyWallet) {
                // This can throw InsufficientMoneyException.
                FeeCalculation feeCalculation = calculateFee(req, value, originalInputs, req.ensureMinRequiredFee, candidates);
                bestCoinSelection = feeCalculation.bestCoinSelection;
                bestChangeOutput = feeCalculation.bestChangeOutput;
                updatedOutputValues = feeCalculation.updatedOutputValues;
            } else {
                // We're being asked to empty the wallet. What this means is ensuring "tx" has only a single output
                // of the total value we can currently spend as determined by the selector, and then subtracting the fee.
                checkState(req.tx.getOutputs().size() == 1, "Empty wallet TX must have a single output only.");
                CoinSelector selector = req.coinSelector == null ? coinSelector : req.coinSelector;
                bestCoinSelection = selector.select(params.getMaxMoney(), candidates);
                candidates = null;  // Selector took ownership and might have changed candidates. Don't access again.
                req.tx.getOutput(0).setValue(bestCoinSelection.valueGathered);
                log.info("  emptying {}", bestCoinSelection.valueGathered.toFriendlyString());
            }

            for (TransactionOutput output : bestCoinSelection.gathered)
                req.tx.addInput(output);

            if (req.emptyWallet) {
                final Coin feePerKb = req.feePerKb == null ? Coin.ZERO : req.feePerKb;
                if (!adjustOutputDownwardsForFee(req.tx, bestCoinSelection, feePerKb, req.ensureMinRequiredFee))
                    throw new CouldNotAdjustDownwards();
            }

            if (updatedOutputValues != null) {
                for (int i = 0; i < updatedOutputValues.size(); i++) {
                    req.tx.getOutput(i).setValue(updatedOutputValues.get(i));
                }
            }

            if (bestChangeOutput != null) {
                req.tx.addOutput(bestChangeOutput);
                log.info("  with {} change", bestChangeOutput.getValue().toFriendlyString());
            }

            // Now shuffle the outputs to obfuscate which is the change.
            if (req.shuffleOutputs)
                req.tx.shuffleOutputs();

            // Now sign the inputs, thus proving that we are entitled to redeem the connected outputs.
            if (req.signInputs)
                signTransaction(req);

            // Check size.
            final int size = req.tx.unsafeUlordSerialize().length;
            if (size > UldTransaction.MAX_STANDARD_TX_SIZE)
                throw new ExceededMaxTransactionSize();

            // Label the transaction as being a user requested payment. This can be used to render GUI wallet
            // transaction lists more appropriately, especially when the wallet starts to generate transactions itself
            // for internal purposes.
            req.tx.setPurpose(UldTransaction.Purpose.USER_PAYMENT);
            // Record the exchange rate that was valid when the transaction was completed.
            req.tx.setMemo(req.memo);
            req.completed = true;
            log.info("  completed: {}", req.tx);
        } finally {
        }
    }

    /**
     * <p>Given a send request containing transaction, attempts to sign it's inputs. This method expects transaction
     * to have all necessary inputs connected or they will be ignored.</p>
     * <p>Actual signing is done by pluggable {@link #signers} and it's not guaranteed that
     * transaction will be complete in the end.</p>
     */
    public void signTransaction(SendRequest req) {
        try {
            UldTransaction tx = req.tx;
            List<TransactionInput> inputs = tx.getInputs();
            List<TransactionOutput> outputs = tx.getOutputs();
            checkState(inputs.size() > 0);
            checkState(outputs.size() > 0);

            int numInputs = tx.getInputs().size();
            for (int i = 0; i < numInputs; i++) {
                TransactionInput txIn = tx.getInput(i);
                if (txIn.getConnectedOutput() == null) {
                    // Missing connected output, assuming already signed.
                    continue;
                }

                try {
                    // We assume if its already signed, its hopefully got a SIGHASH type that will not invalidate when
                    // we sign missing pieces (to check this would require either assuming any signatures are signing
                    // standard output types or a way to get processed signatures out of script execution)
                    txIn.getScriptSig().correctlySpends(tx, i, txIn.getConnectedOutput().getScriptPubKey());
                    log.warn("Input {} already correctly spends output, assuming SIGHASH type used will be safe and skipping signing.", i);
                    continue;
                } catch (ScriptException e) {
                    log.debug("Input contained an incorrect signature", e);
                    // Expected.
                }

                Script scriptPubKey = txIn.getConnectedOutput().getScriptPubKey();
                RedeemData redeemData = txIn.getConnectedRedeemData(this);
                checkNotNull(redeemData, "Transaction exists in wallet that we cannot redeem: %s", txIn.getOutpoint().getHash());
                txIn.setScriptSig(scriptPubKey.createEmptyInputScript(redeemData.keys.get(0), redeemData.redeemScript));
            }

            TransactionSigner.ProposedTransaction proposal = new TransactionSigner.ProposedTransaction(tx);

            // resolve missing sigs if any
            new MissingSigResolutionSigner(req.missingSigsMode).signInputs(proposal, this);
        } finally {
        }
    }

    /** Reduce the value of the first output of a transaction to pay the given feePerKb as appropriate for its size. */
    private boolean adjustOutputDownwardsForFee(UldTransaction tx, CoinSelection coinSelection, Coin feePerKb,
                                                boolean ensureMinRequiredFee) {
        final int size = tx.unsafeUlordSerialize().length + estimateBytesForSigning(coinSelection);
        Coin fee = feePerKb.multiply(size).divide(1000);
        if (ensureMinRequiredFee && fee.compareTo(UldTransaction.REFERENCE_DEFAULT_MIN_TX_FEE) < 0)
            fee = UldTransaction.REFERENCE_DEFAULT_MIN_TX_FEE;
        TransactionOutput output = tx.getOutput(0);
        output.setValue(output.getValue().subtract(fee));
        return !output.isDust();
    }

    /**
     * Returns a list of the outputs that can potentially be spent, i.e. that we have the keys for and are unspent
     * according to our knowledge of the block chain.
     */
    public List<TransactionOutput> calculateAllSpendCandidates() {
        return calculateAllSpendCandidates(true, true);
    }

    /** @deprecated Use {@link #calculateAllSpendCandidates(boolean, boolean)} or the zero-parameter form instead. */
    @Deprecated
    public List<TransactionOutput> calculateAllSpendCandidates(boolean excludeImmatureCoinbases) {
        return calculateAllSpendCandidates(excludeImmatureCoinbases, true);
    }

    /**
     * Returns a list of all outputs that are being tracked by this wallet either from the {@link UTXOProvider}
     * (in this case the existence or not of private keys is ignored), or the wallets internal storage (the default)
     * taking into account the flags.
     *
     * @param excludeImmatureCoinbases Whether to ignore coinbase outputs that we will be able to spend in future once they mature.
     * @param excludeUnsignable Whether to ignore outputs that we are tracking but don't have the keys to sign for.
     */
    public List<TransactionOutput> calculateAllSpendCandidates(boolean excludeImmatureCoinbases, boolean excludeUnsignable) {
        try {
            List<TransactionOutput> candidates;
            if (vUTXOProvider == null) {
                candidates = new ArrayList<TransactionOutput>();
            } else {
                candidates = calculateAllSpendCandidatesFromUTXOProvider(excludeImmatureCoinbases);
            }
            return candidates;
        } finally {
        }
    }



    /**
     * Returns the spendable candidates from the {@link UTXOProvider} based on keys that the wallet contains.
     * @return The list of candidates.
     */
    protected LinkedList<TransactionOutput> calculateAllSpendCandidatesFromUTXOProvider(boolean excludeImmatureCoinbases) {
        UTXOProvider utxoProvider = checkNotNull(vUTXOProvider, "No UTXO provider has been set");
        LinkedList<TransactionOutput> candidates = Lists.newLinkedList();
        try {
            int chainHeight = utxoProvider.getChainHeadHeight();
            for (UTXO output : getStoredOutputsFromUTXOProvider()) {
                boolean coinbase = output.isCoinbase();
                int depth = chainHeight - output.getHeight() + 1; // the current depth of the output (1 = same as head).
                // Do not try and spend coinbases that were mined too recently, the protocol forbids it.
                if (!excludeImmatureCoinbases || !coinbase || depth >= params.getSpendableCoinbaseDepth()) {
                    candidates.add(new FreeStandingTransactionOutput(params, output, chainHeight));
                }
            }
        } catch (UTXOProviderException e) {
            throw new RuntimeException("UTXO provider error", e);
        }
        return candidates;
    }

    /**
     * Get all the {@link UTXO}'s from the {@link UTXOProvider} based on keys that the
     * wallet contains.
     * @return The list of stored outputs.
     */
    protected List<UTXO> getStoredOutputsFromUTXOProvider() throws UTXOProviderException {
        UTXOProvider utxoProvider = checkNotNull(vUTXOProvider, "No UTXO provider has been set");
        List<UTXO> candidates = new ArrayList<UTXO>();
        List<Address> addresses = new ArrayList<Address>();
        candidates.addAll(utxoProvider.getOpenTransactionOutputs(addresses));
        return candidates;
    }

    /** Returns the {@link CoinSelector} object which controls which outputs can be spent by this wallet. */
    public CoinSelector getCoinSelector() {
        try {
            return coinSelector;
        } finally {
        }
    }

    /**
     * A coin selector is responsible for choosing which outputs to spend when creating transactions. The default
     * selector implements a policy of spending transactions that appeared in the best chain and pending transactions
     * that were created by this wallet, but not others. You can override the coin selector for any given send
     * operation by changing {@link SendRequest#coinSelector}.
     */
    public void setCoinSelector(CoinSelector coinSelector) {
        try {
            this.coinSelector = checkNotNull(coinSelector);
        } finally {
        }
    }

    /**
     * Get the {@link UTXOProvider}.
     * @return The UTXO provider.
     */
    @Nullable public UTXOProvider getUTXOProvider() {
        try {
            return vUTXOProvider;
        } finally {
        }
    }

    /**
     * Set the {@link UTXOProvider}.
     *
     * <p>The wallet will query the provider for spendable candidates, i.e. outputs controlled exclusively
     * by private keys contained in the wallet.</p>
     *
     * <p>Note that the associated provider must be reattached after a wallet is loaded from disk.
     * The association is not serialized.</p>
     */
    public void setUTXOProvider(@Nullable UTXOProvider provider) {
        try {
            checkArgument(provider == null || provider.getParams().equals(params));
            this.vUTXOProvider = provider;
        } finally {
        }
    }

    //endregion

    /******************************************************************************************************************/

    /**
     * A custom {@link TransactionOutput} that is free standing. This contains all the information
     * required for spending without actually having all the linked data (i.e parent tx).
     *
     */
    private class FreeStandingTransactionOutput extends TransactionOutput {
        private UTXO output;
        private int chainHeight;

        /**
         * Construct a free standing Transaction Output.
         * @param params The network parameters.
         * @param output The stored output (free standing).
         */
        public FreeStandingTransactionOutput(NetworkParameters params, UTXO output, int chainHeight) {
            super(params, null, output.getValue(), output.getScript().getProgram());
            this.output = output;
            this.chainHeight = chainHeight;
        }

        /**
         * Get the {@link UTXO}.
         * @return The stored output.
         */
        public UTXO getUTXO() {
            return output;
        }

        @Override
        public int getIndex() {
            return (int) output.getIndex();
        }

        @Override
        public Sha256Hash getParentTransactionHash() {
            return output.getHash();
        }
    }

    /******************************************************************************************************************/

    /******************************************************************************************************************/

    private static class FeeCalculation {
        // Selected UTXOs to spend
        public CoinSelection bestCoinSelection;
        // Change output (may be null if no change)
        public TransactionOutput bestChangeOutput;
        // List of output values adjusted downwards when recipients pay fees (may be null if no adjustment needed).
        public List<Coin> updatedOutputValues;
    }

    //region Fee calculation code

    public FeeCalculation calculateFee(SendRequest req, Coin value, List<TransactionInput> originalInputs,
                                       boolean needAtLeastReferenceFee, List<TransactionOutput> candidates) throws InsufficientMoneyException {
        FeeCalculation result;
        Coin fee = Coin.ZERO;
        while (true) {
            result = new FeeCalculation();
            UldTransaction tx = new UldTransaction(params);
            addSuppliedInputs(tx, req.tx.getInputs());

            Coin valueNeeded = value;
            if (!req.recipientsPayFees) {
                valueNeeded = valueNeeded.add(fee);
            }
            if (req.recipientsPayFees) {
                result.updatedOutputValues = new ArrayList<Coin>();
            }
            for (int i = 0; i < req.tx.getOutputs().size(); i++) {
                TransactionOutput output = new TransactionOutput(params, tx,
                        req.tx.getOutputs().get(i).ulordSerialize(), 0);
                if (req.recipientsPayFees) {
                    // Subtract fee equally from each selected recipient
                    output.setValue(output.getValue().subtract(fee.divide(req.tx.getOutputs().size())));
                    // first receiver pays the remainder not divisible by output count
                    if (i == 0) {
                        output.setValue(
                                output.getValue().subtract(fee.divideAndRemainder(req.tx.getOutputs().size())[1])); // Subtract fee equally from each selected recipient
                    }
                    result.updatedOutputValues.add(output.getValue());
                    if (output.getMinNonDustValue().isGreaterThan(output.getValue())) {
                        throw new CouldNotAdjustDownwards();
                    }
                }
                tx.addOutput(output);
            }
            CoinSelector selector = req.coinSelector == null ? coinSelector : req.coinSelector;
            // selector is allowed to modify candidates list.
            CoinSelection selection = selector.select(valueNeeded, new LinkedList<TransactionOutput>(candidates));
            result.bestCoinSelection = selection;
            // Can we afford this?
            if (selection.valueGathered.compareTo(valueNeeded) < 0) {
                Coin valueMissing = valueNeeded.subtract(selection.valueGathered);
                throw new InsufficientMoneyException(valueMissing);
            }
            Coin change = selection.valueGathered.subtract(valueNeeded);
            if (change.isGreaterThan(Coin.ZERO)) {
                // The value of the inputs is greater than what we want to send. Just like in real life then,
                // we need to take back some coins ... this is called "change". Add another output that sends the change
                // back to us. The address comes either from the request or currentChangeAddress() as a default.
                Address changeAddress = req.changeAddress;
                TransactionOutput changeOutput = new TransactionOutput(params, tx, change, changeAddress);
                if (req.recipientsPayFees && changeOutput.isDust()) {
                    // We do not move dust-change to fees, because the sender would end up paying more than requested.
                    // This would be against the purpose of the all-inclusive feature.
                    // So instead we raise the change and deduct from the first recipient.
                    Coin missingToNotBeDust = changeOutput.getMinNonDustValue().subtract(changeOutput.getValue());
                    changeOutput.setValue(changeOutput.getValue().add(missingToNotBeDust));
                    TransactionOutput firstOutput = tx.getOutputs().get(0);
                    firstOutput.setValue(firstOutput.getValue().subtract(missingToNotBeDust));
                    result.updatedOutputValues.set(0, firstOutput.getValue());
                    if (firstOutput.isDust()) {
                        throw new CouldNotAdjustDownwards();
                    }
                }
                if (changeOutput.isDust()) {
                    // Never create dust outputs; if we would, just
                    // add the dust to the fee.
                    // Oscar comment: This seems like a way to make the condition below "if
                    // (!fee.isLessThan(feeNeeded))" to become true.
                    // This is a non-easy to understand way to do that.
                    // Maybe there are other effects I am missing
                    fee = fee.add(changeOutput.getValue());
                } else {
                    tx.addOutput(changeOutput);
                    result.bestChangeOutput = changeOutput;
                }
            }

            for (TransactionOutput selectedOutput : selection.gathered) {
                TransactionInput input = tx.addInput(selectedOutput);
                // If the scriptBytes don't default to none, our size calculations will be thrown off.
                checkState(input.getScriptBytes().length == 0);
            }

            int size = tx.unsafeUlordSerialize().length;
            size += estimateBytesForSigning(selection);

            Coin feePerKb = req.feePerKb;
            if (needAtLeastReferenceFee && feePerKb.compareTo(UldTransaction.REFERENCE_DEFAULT_MIN_TX_FEE) < 0) {
                feePerKb = UldTransaction.REFERENCE_DEFAULT_MIN_TX_FEE;
            }
            Coin feeNeeded = feePerKb.multiply(size).divide(1000);

            if (!fee.isLessThan(feeNeeded)) {
                // Done, enough fee included.
                break;
            }

            // Include more fee and try again.
            fee = feeNeeded;
        }
        return result;

    }

    private void addSuppliedInputs(UldTransaction tx, List<TransactionInput> originalInputs) {
        for (TransactionInput input : originalInputs)
            tx.addInput(new TransactionInput(params, tx, input.ulordSerialize()));
    }

    private int estimateBytesForSigning(CoinSelection selection) {
        int size = 0;
        for (TransactionOutput output : selection.gathered) {
            try {
                Script script = output.getScriptPubKey();
                UldECKey key = null;
                Script redeemScript = null;
                if (script.isSentToAddress()) {
                    key = findKeyFromPubHash(script.getPubKeyHash());
                    checkNotNull(key, "Coin selection includes unspendable outputs");
                } else if (script.isPayToScriptHash()) {
                    redeemScript = findRedeemDataFromScriptHash(script.getPubKeyHash()).redeemScript;
                    checkNotNull(redeemScript, "Coin selection includes unspendable outputs");
                }
                size += script.getNumberOfBytesRequiredToSpend(key, redeemScript);
            } catch (ScriptException e) {
                // If this happens it means an output script in a wallet tx could not be understood. That should never
                // happen, if it does it means the wallet has got into an inconsistent state.
                throw new IllegalStateException(e);
            }
        }
        return size;
    }

    //endregion

    /******************************************************************************************************************/

}
