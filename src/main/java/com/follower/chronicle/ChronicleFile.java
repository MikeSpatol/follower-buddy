package com.follower.chronicle;

import java.util.List;

/** The root of {@code default-chronicle.json}: a version and the pages. */
public class ChronicleFile
{
	public static final int SUPPORTED_VERSION = 1;

	public int version = SUPPORTED_VERSION;
	public List<ChronicleEntry> entries;
}
