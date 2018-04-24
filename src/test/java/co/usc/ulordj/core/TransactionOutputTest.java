/*
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

import co.usc.ulordj.params.UnitTestParams;
import co.usc.ulordj.params.MainNetParams;
import co.usc.ulordj.script.Script;
import co.usc.ulordj.script.ScriptBuilder;
import org.junit.Test;

import static org.junit.Assert.*;

public class TransactionOutputTest {

    protected static final NetworkParameters PARAMS = UnitTestParams.get();

    @Test
    public void testP2SHOutputScript() throws Exception {
        String P2SHAddressString = "35b9vsyH1KoFT5a5KtrKusaCcPLkiSo1tU";
        Address P2SHAddress = Address.fromBase58(MainNetParams.get(), P2SHAddressString);
        Script script = ScriptBuilder.createOutputScript(P2SHAddress);
        UldTransaction tx = new UldTransaction(MainNetParams.get());
        tx.addOutput(Coin.COIN, script);
        assertEquals(P2SHAddressString, tx.getOutput(0).getAddressFromP2SH(MainNetParams.get()).toString());
    }

    @Test
    public void getAddressTests() throws Exception {
        UldTransaction tx = new UldTransaction(MainNetParams.get());
        tx.addOutput(Coin.CENT, ScriptBuilder.createOpReturnScript("hello world!".getBytes()));
        assertNull(tx.getOutput(0).getAddressFromP2SH(PARAMS));
        assertNull(tx.getOutput(0).getAddressFromP2PKHScript(PARAMS));
    }

    @Test
    public void getMinNonDustValue() throws Exception {
        TransactionOutput payToAddressOutput = new TransactionOutput(PARAMS, null, Coin.COIN, new UldECKey().toAddress(PARAMS));
        assertEquals(UldTransaction.MIN_NONDUST_OUTPUT, payToAddressOutput.getMinNonDustValue());
    }
}
