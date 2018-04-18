/**
 * 
 */
package co.usc.ulordj.core;

import java.util.ArrayList;
import java.util.List;

/**
 * @author mario
 *
 */
public class BlockchainAddResult {

	private Boolean success = Boolean.FALSE;

	private List<BtcBlock> orphansBlocksConnected = new ArrayList<BtcBlock>();
	private List<FilteredBlock> orphansFilteredBlocksConnected = new ArrayList<FilteredBlock>();



	public void addConnectedOrphan(BtcBlock block) {
		orphansBlocksConnected.add(block);
	}

	public void addConnectedOrphans(List<BtcBlock> blocks) {
		orphansBlocksConnected.addAll(blocks);
	}

	public void addConnectedFilteredOrphan(FilteredBlock block) {
		orphansFilteredBlocksConnected.add(block);
	}

	public void addFilteredOrphans(List<FilteredBlock> blocks) {
		orphansFilteredBlocksConnected.addAll(blocks);
	}

	public List<BtcBlock> getOrphansBlockConnected() {
		return orphansBlocksConnected;
	}
	
	public List<FilteredBlock> getFilteredOrphansConnected() {
		return orphansFilteredBlocksConnected;
	}

	public void setSuccess(Boolean success) {
		this.success = success;
	}

	public Boolean success() {
		return success;
	}

}
