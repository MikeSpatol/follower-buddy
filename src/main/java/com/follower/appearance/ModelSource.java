package com.follower.appearance;

public enum ModelSource
{
	/** Try the cache dump; if a piece is missing, capture from the local player. */
	DUMP_THEN_CAPTURE("Dump, then capture"),
	/** Cache dump only. Missing data means no follower — useful while debugging a dump. */
	DUMP_ONLY("Dump only"),
	/** Never touch the dump. No data file needed; follower can't animate. */
	CAPTURE_ONLY("Capture only");

	private final String label;

	ModelSource(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
