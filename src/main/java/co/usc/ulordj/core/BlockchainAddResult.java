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

	private List<UldBlock> orphansBlocksConnected = new ArrayList<UldBlock>();
	private List<FilteredBlock> orphansFilteredBlocksConnected = new ArrayList<FilteredBlock>();



	public void addConnectedOrphan(UldBlock block) {
		orphansBlocksConnected.add(block);
	}

	public void addConnectedOrphans(List<UldBlock> blocks) {
		orphansBlocksConnected.addAll(blocks);
	}

	public void addConnectedFilteredOrphan(FilteredBlock block) {
		orphansFilteredBlocksConnected.add(block);
	}

	public void addFilteredOrphans(List<FilteredBlock> blocks) {
		orphansFilteredBlocksConnected.addAll(blocks);
	}

	public List<UldBlock> getOrphansBlockConnected() {
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
