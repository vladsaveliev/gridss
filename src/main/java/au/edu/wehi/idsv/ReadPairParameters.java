package au.edu.wehi.idsv;


public class ReadPairParameters {
	/**
	 * Minimum MAPQ of local read anchor to considered as evidence
	 */
	public int minLocalMapq = 5;
	public AdapterHelper adapters = new SoftClipParameters().adapters;
}