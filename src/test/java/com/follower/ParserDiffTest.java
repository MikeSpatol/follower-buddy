package com.follower;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.nio.BufferUnderflowException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;
import net.runelite.api.Client;
import net.runelite.api.IndexDataBase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link LiveCacheParser} against the opcode switches it replaced (kept in
 * {@code appearance.before}).
 *
 * <p>Random definitions of every kind of opcode, with unknown opcodes,
 * cut-off definitions and flipped bytes among them, must give the same
 * entries, the same non-selectable kits, the same failures and the same
 * client-log lines, both one definition at a time and as whole archives read
 * through the client. Every opcode 0-255 of every type is also tried once
 * with each of a few payloads, and every string byte.
 *
 * <p>Two differences are expected, both in the exception a definition that
 * cannot be read fails with (which the parse catches, counts and logs the
 * same way). A definition cut off mid-field ran the old byte reader off its
 * array (ArrayIndexOutOfBoundsException) and now underflows the ByteBuffer
 * (BufferUnderflowException, or IllegalArgumentException for a param key cut
 * off). Kit opcodes 65-69 and 75-79, chathead slots the kit format does not
 * have, overran the old five-slot array and are now unknown opcodes.
 */
public class ParserDiffTest
{
	/** Rounds of 5,000 definitions per archive; {@code -Dparserdiff.rounds=N} for a longer run. */
	private static final int ROUNDS = Integer.getInteger("parserdiff.rounds", 6);

	private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
	private final Logger[] loggers = {
		(Logger) org.slf4j.LoggerFactory.getLogger(LiveCacheParser.class),
		(Logger) org.slf4j.LoggerFactory.getLogger(BeforeLiveCacheParser.class),
	};
	private final Level[] levels = new Level[2];

	private Random random = new Random(1234);

	@Before
	public void listen()
	{
		logged.start();
		for (int i = 0; i < loggers.length; i++)
		{
			levels[i] = loggers[i].getLevel();
			loggers[i].setLevel(Level.DEBUG);
			loggers[i].addAppender(logged);
		}
	}

	@After
	public void stopListening()
	{
		for (int i = 0; i < loggers.length; i++)
		{
			loggers[i].detachAppender(logged);
			loggers[i].setLevel(levels[i]);
		}
	}

	// ------------------------------------------------------------ definitions

	private void u8(ByteArrayOutputStream out, int value)
	{
		out.write(value & 0xFF);
	}

	private void u16(ByteArrayOutputStream out, int value)
	{
		out.write((value >> 8) & 0xFF);
		out.write(value & 0xFF);
	}

	private void i32(ByteArrayOutputStream out, int value)
	{
		u16(out, value >>> 16);
		u16(out, value);
	}

	private void str(ByteArrayOutputStream out)
	{
		for (int n = random.nextInt(12); n > 0; n--)
		{
			out.write(1 + random.nextInt(255));
		}
		out.write(0);
	}

	private int any16()
	{
		int kind = random.nextInt(5);
		return kind == 0 ? 0xFFFF : kind == 1 ? 0 : kind == 2 ? 128 : random.nextInt(0x10000);
	}

	private int any32()
	{
		int kind = random.nextInt(5);
		return kind == 0 ? -1 : kind == 1 ? 0 : random.nextInt();
	}

	private int any8()
	{
		int kind = random.nextInt(5);
		return kind == 0 ? 0xFF : kind == 1 ? 0 : random.nextInt(256);
	}

	private void pairs(ByteArrayOutputStream out)
	{
		int n = random.nextInt(6);
		u8(out, n);
		for (int i = 0; i < n; i++)
		{
			u16(out, any16());
			u16(out, any16());
		}
	}

	private void junk(ByteArrayOutputStream out)
	{
		for (int n = random.nextInt(4); n > 0; n--)
		{
			u8(out, random.nextInt(256));
		}
	}

	private static final int[] ITEM_OPCODES = {1, 2, 3, 9, 4, 5, 6, 7, 8, 11, 15, 16, 65, 160, 12, 13, 14, 23, 24, 25,
		26, 27, 30, 31, 35, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, 52, 53, 54, 75, 78, 79, 90, 91, 92, 93,
		94, 95, 97, 98, 110, 111, 112, 139, 140, 148, 149, 100, 105, 109, 113, 114, 115, 200, 201, 202, 249};

	private byte[] item()
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		if (random.nextInt(3) != 0)
		{
			// Most carry a worn model, or the dumper's filter drops them.
			if (random.nextBoolean())
			{
				u8(out, 23);
				u16(out, any16());
			}
			else
			{
				u8(out, 45);
				i32(out, any32());
			}
			u8(out, any8());
		}
		for (int k = random.nextInt(14); k > 0; k--)
		{
			int op = random.nextInt(60) == 0 ? 1 + random.nextInt(255) : ITEM_OPCODES[random.nextInt(ITEM_OPCODES.length)];
			u8(out, op);
			switch (op)
			{
				case 1: case 4: case 5: case 6: case 7: case 8: case 24: case 26: case 75: case 78: case 79:
				case 90: case 91: case 92: case 93: case 94: case 95: case 97: case 98: case 110: case 111:
				case 112: case 139: case 140: case 148: case 149:
					u16(out, any16());
					break;
				case 2: case 3: case 9: case 30: case 31: case 35: case 39:
					str(out);
					break;
				case 11: case 15: case 16: case 65: case 160:
					break;
				case 12: case 44: case 46: case 47: case 49: case 50: case 51: case 52: case 53: case 54:
					i32(out, any32());
					break;
				case 13: case 14: case 27: case 42: case 113: case 114: case 115:
					u8(out, any8());
					break;
				case 23: case 25:
					u16(out, any16());
					u8(out, any8());
					break;
				case 45: case 48:
					i32(out, any32());
					u8(out, any8());
					break;
				case 40: case 41:
					pairs(out);
					break;
				case 43:
					u8(out, any8());
					for (int j = random.nextInt(4); j > 0; j--)
					{
						u8(out, 1 + random.nextInt(255));
						str(out);
					}
					u8(out, 0);
					break;
				case 100: case 105: case 109:
					u16(out, any16());
					u16(out, any16());
					break;
				case 200:
					u8(out, any8());
					u8(out, any8());
					str(out);
					break;
				case 201:
					u8(out, any8());
					u16(out, any16());
					u16(out, any16());
					i32(out, any32());
					i32(out, any32());
					str(out);
					break;
				case 202:
					u8(out, any8());
					u16(out, any16());
					u16(out, any16());
					u16(out, any16());
					i32(out, any32());
					i32(out, any32());
					str(out);
					break;
				case 249:
				{
					int count = random.nextInt(4);
					u8(out, count);
					for (; count > 0; count--)
					{
						boolean isString = random.nextBoolean();
						u8(out, isString ? 1 : 0);
						u8(out, random.nextInt(256));
						u16(out, any16());
						if (isString)
						{
							str(out);
						}
						else
						{
							i32(out, any32());
						}
					}
					break;
				}
				default:
					junk(out);
					break;
			}
		}
		u8(out, 0);
		return mangle(out.toByteArray());
	}

	private static final int[] KIT_OPCODES = {1, 2, 3, 5, 40, 41, 60, 61, 62, 63, 64, 70, 71, 72, 73, 74, 65, 69, 75, 79};

	private byte[] kit()
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (int k = random.nextInt(10); k > 0; k--)
		{
			int op = random.nextInt(40) == 0 ? 1 + random.nextInt(255) : KIT_OPCODES[random.nextInt(KIT_OPCODES.length)];
			u8(out, op);
			if (op == 1)
			{
				u8(out, any8());
			}
			else if (op == 2 || op == 5)
			{
				int n = random.nextInt(5);
				u8(out, n);
				for (int i = 0; i < n; i++)
				{
					if (op == 2)
					{
						u16(out, any16());
					}
					else
					{
						i32(out, any32());
					}
				}
			}
			else if (op == 40 || op == 41)
			{
				pairs(out);
			}
			else if (op >= 60 && op < 70)
			{
				u16(out, any16());
			}
			else if (op >= 70 && op < 80)
			{
				i32(out, any32());
			}
			else if (op != 3)
			{
				junk(out);
			}
		}
		u8(out, 0);
		return mangle(out.toByteArray());
	}

	private static final int[] SPOTANIM_OPCODES = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 40, 41};

	private byte[] spotAnim()
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (int k = random.nextInt(10); k > 0; k--)
		{
			int op = random.nextInt(40) == 0 ? 1 + random.nextInt(255) : SPOTANIM_OPCODES[random.nextInt(SPOTANIM_OPCODES.length)];
			u8(out, op);
			switch (op)
			{
				case 1: case 2: case 4: case 5: case 6:
					u16(out, any16());
					break;
				case 3:
					i32(out, any32());
					break;
				case 7: case 8:
					u8(out, any8());
					break;
				case 9:
					str(out);
					break;
				case 10:
					break;
				case 40: case 41:
					pairs(out);
					break;
				default:
					junk(out);
					break;
			}
		}
		u8(out, 0);
		return mangle(out.toByteArray());
	}

	/** Now and then cut a definition short or flip a byte, for the failure paths. */
	private byte[] mangle(byte[] bytes)
	{
		int kind = random.nextInt(20);
		if (kind == 0 && bytes.length > 1)
		{
			return Arrays.copyOf(bytes, random.nextInt(bytes.length));
		}
		if (kind == 1 && bytes.length > 0)
		{
			bytes[random.nextInt(bytes.length)] = (byte) random.nextInt(256);
		}
		return bytes;
	}

	// ------------------------------------------------------------- comparing

	private static String describe(Object entry)
	{
		if (entry == null)
		{
			return "null";
		}
		if (entry instanceof ModelRepository.Entry)
		{
			ModelRepository.Entry e = (ModelRepository.Entry) entry;
			return e.n + "|" + e.bp + "|" + e.mo + "|" + e.fo + "|" + e.wp1 + "|" + e.wp2 + "|" + e.wp3 + "|"
				+ Arrays.toString(e.m) + Arrays.toString(e.f) + Arrays.toString(e.hm) + Arrays.toString(e.hf)
				+ Arrays.toString(e.ch) + Arrays.toString(e.cf) + Arrays.toString(e.cr) + Arrays.toString(e.tf)
				+ Arrays.toString(e.tr) + (e.m == e.f ? " shared" : " own");
		}
		SpotAnimRepository.Entry e = (SpotAnimRepository.Entry) entry;
		return e.m + "|" + e.a + "|" + e.rx + "|" + e.ry + "|" + e.rot + "|" + e.am + "|" + e.co + "|"
			+ Arrays.toString(e.cf) + Arrays.toString(e.cr) + Arrays.toString(e.tf) + Arrays.toString(e.tr);
	}

	private static String describe(Map<String, ?> entries)
	{
		StringBuilder out = new StringBuilder();
		entries.forEach((id, entry) -> out.append(id).append('=').append(describe(entry)).append('\n'));
		return out.toString();
	}

	/** The outcome of one decode: the entry, or the exception it failed with. */
	private static Object[] attempt(Supplier<Object> decode)
	{
		try
		{
			return new Object[]{describe(decode.get()), null};
		}
		catch (RuntimeException e)
		{
			return new Object[]{null, e};
		}
	}

	private static final java.util.regex.Pattern KIT_HEAD_OPCODE =
		java.util.regex.Pattern.compile("unknown kit opcode (6[5-9]|7[5-9])");

	/** Whether the new failure is the old one, or one of the two expected differences. */
	private static boolean sameFailure(RuntimeException before, RuntimeException after, int length, boolean kit)
	{
		if (before.getClass() == after.getClass() && String.valueOf(before.getMessage()).equals(String.valueOf(after.getMessage())))
		{
			return true;
		}
		if (!(before instanceof ArrayIndexOutOfBoundsException))
		{
			return false;
		}
		// Once one has been thrown often enough at the same place, the JVM
		// throws a preallocated copy without its message.
		String message = before.getMessage();
		boolean pastTheEnd = message == null || message.equals("Index " + length + " out of bounds for length " + length);
		if (pastTheEnd && (after instanceof BufferUnderflowException
			|| (after instanceof IllegalArgumentException && after.getMessage().startsWith("newPosition > limit"))))
		{
			return true;
		}
		boolean headSlot = message == null || pastTheEnd || message.matches("Index [5-9] out of bounds for length 5");
		return kit && headSlot && after instanceof IllegalStateException && KIT_HEAD_OPCODE.matcher(after.getMessage()).matches();
	}

	private void compareOne(byte[] data, int type)
	{
		String what = type + " " + Arrays.toString(data);
		boolean[] oldFlag = new boolean[1];
		boolean[] newFlag = new boolean[1];
		Object[] before;
		Object[] after;
		if (type == 0)
		{
			before = attempt(() -> BeforeLiveCacheParser.decodeItem(data.clone()));
			after = attempt(() -> LiveCacheParser.decodeItem(data.clone()));
		}
		else
		{
			before = attempt(() -> BeforeLiveCacheParser.decodeKit(data.clone(), oldFlag));
			after = attempt(() -> LiveCacheParser.decodeKit(data.clone(), newFlag));
		}
		assertEquals(what, before[1] == null, after[1] == null);
		if (before[1] == null)
		{
			assertEquals(what, before[0], after[0]);
			assertEquals("non-selectable " + what, oldFlag[0], newFlag[0]);
		}
		else
		{
			assertTrue(what + ": " + before[1] + " / " + after[1],
				sameFailure((RuntimeException) before[1], (RuntimeException) after[1], data.length, type == 1));
		}
	}

	/** A client whose config index holds these archives. */
	private static Client client(Map<Integer, Map<Integer, byte[]>> archives)
	{
		IndexDataBase index = (IndexDataBase) Proxy.newProxyInstance(IndexDataBase.class.getClassLoader(),
			new Class<?>[]{IndexDataBase.class}, (proxy, method, args) ->
			{
				if (method.getName().equals("getFileIds"))
				{
					return archives.getOrDefault((Integer) args[0], new HashMap<>()).keySet().stream()
						.mapToInt(Integer::intValue).sorted().toArray();
				}
				if (method.getName().equals("loadData"))
				{
					byte[] data = archives.get((Integer) args[0]).get((Integer) args[1]);
					return data == null ? null : data.clone();
				}
				return null;
			});
		return (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
			(proxy, method, args) -> method.getName().equals("getIndexConfig") ? index : null);
	}

	/** The log lines since the last call, the old parser's under the new one's name. */
	private String drainLog()
	{
		StringBuilder out = new StringBuilder();
		for (ILoggingEvent event : logged.list)
		{
			out.append(event.getLevel()).append(' ').append(event.getLoggerName().replace(".Before", "."))
				.append(' ').append(event.getFormattedMessage());
			IThrowableProxy thrown = event.getThrowableProxy();
			if (thrown != null)
			{
				out.append(" ! ").append(thrown.getClassName()).append(": ").append(thrown.getMessage());
			}
			out.append('\n');
		}
		logged.list.clear();
		return out.toString();
	}

	/**
	 * The log of a whole parse with the expected exception differences made
	 * alike: a parse reads the same definitions in the same order, so a line
	 * differs only where the definition it names failed differently, which
	 * the definition-by-definition tests compare exactly.
	 */
	private static String alike(String log)
	{
		return log
			.replaceAll("java\\.lang\\.ArrayIndexOutOfBoundsException: (Index \\d+ out of bounds for length \\d+|null)", "<overran>")
			.replaceAll("java\\.nio\\.BufferUnderflowException: null", "<overran>")
			.replaceAll("java\\.lang\\.IllegalArgumentException: newPosition > limit: \\(\\d+ > \\d+\\)", "<overran>")
			.replaceAll("java\\.lang\\.IllegalStateException: unknown kit opcode (6[5-9]|7[5-9])", "<overran>")
			.replaceAll("failed to parse: (Index \\d+ out of bounds for length \\d+|null|newPosition > limit: \\(\\d+ > \\d+\\))",
				"failed to parse: <overran>");
	}

	// ------------------------------------------------------------------ tests

	@Test
	public void everyStringByteDecodesTheSame()
	{
		for (int b = 1; b < 256; b++)
		{
			byte[] definition = {2, (byte) b, 0, 23, 0, 1, 0, 0};
			assertEquals("string byte " + b, BeforeLiveCacheParser.decodeItem(definition).n,
				LiveCacheParser.decodeItem(definition).n);
		}
		// And a name of every byte at once, cut short by its terminator.
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(2);
		for (int b = 1; b < 256; b++)
		{
			out.write(b);
		}
		out.write(0);
		out.write(25);
		u16(out, 7);
		u8(out, 3);
		out.write(0);
		byte[] definition = out.toByteArray();
		assertEquals(describe(BeforeLiveCacheParser.decodeItem(definition)),
			describe(LiveCacheParser.decodeItem(definition)));
	}

	@Test
	public void everyOpcodeOfEveryTypeReadsTheSameWidth()
	{
		int[][] payloads = {new int[24], {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 0, 0, 0, 0, 0, 0, 0, 0},
			{0x80, 0x81, 0x8D, 0xFF, 0, 0x7F, 0x90, 0x9D, 0, 0, 0, 0, 0, 0, 0, 0}, {}, {2}, {200, 1}};
		for (int opcode = 0; opcode < 256; opcode++)
		{
			for (int[] payload : payloads)
			{
				ByteArrayOutputStream out = new ByteArrayOutputStream();
				// A worn model first, so an item that reads through is not filtered out.
				u8(out, 23);
				u16(out, 5);
				u8(out, 1);
				u8(out, opcode);
				for (int b : payload)
				{
					u8(out, b);
				}
				byte[] item = out.toByteArray();
				compareOne(item, 0);

				out = new ByteArrayOutputStream();
				u8(out, 2);
				u8(out, 1);
				u16(out, 500);
				u8(out, opcode);
				for (int b : payload)
				{
					u8(out, b);
				}
				compareOne(out.toByteArray(), 1);

				out = new ByteArrayOutputStream();
				u8(out, 1);
				u16(out, 77);
				u8(out, opcode);
				for (int b : payload)
				{
					u8(out, b);
				}
				Map<Integer, Map<Integer, byte[]>> archives = new HashMap<>();
				archives.put(13, new HashMap<>(Map.of(1, out.toByteArray())));
				Client client = client(archives);
				String before = describe(BeforeLiveCacheParser.parseSpotAnims(client))
					+ drainLog();
				String after = describe(LiveCacheParser.parseSpotAnims(client)) + drainLog();
				assertEquals("spotanim opcode " + opcode + " " + Arrays.toString(payload), alike(before), alike(after));
			}
		}
	}

	@Test
	public void randomDefinitionsDecodeTheSame()
	{
		for (int i = 0; i < ROUNDS * 5000; i++)
		{
			compareOne(item(), 0);
			compareOne(kit(), 1);
		}
	}

	@Test
	public void wholeArchivesParseTheSame()
	{
		random = new Random(4321);
		int entries = 0;
		for (int round = 0; round < ROUNDS; round++)
		{
			Map<Integer, Map<Integer, byte[]>> archives = new HashMap<>();
			for (int archive : new int[]{3, 10, 13})
			{
				Map<Integer, byte[]> files = new HashMap<>();
				for (int id = 0; id < 5000; id++)
				{
					files.put(id, random.nextInt(50) == 0 ? null : archive == 10 ? item() : archive == 3 ? kit() : spotAnim());
				}
				archives.put(archive, files);
			}
			Client client = client(archives);

			Set<Integer> oldFlagged = new HashSet<>();
			Set<Integer> newFlagged = new HashSet<>();
			String before = describe(BeforeLiveCacheParser.parseItems(client))
				+ describe(BeforeLiveCacheParser.parseKits(client, oldFlagged))
				+ describe(BeforeLiveCacheParser.parseSpotAnims(client))
				+ describe(BeforeLiveCacheParser.parseKits(client));
			String oldLog = drainLog();
			String after = describe(LiveCacheParser.parseItems(client))
				+ describe(LiveCacheParser.parseKits(client, newFlagged))
				+ describe(LiveCacheParser.parseSpotAnims(client))
				+ describe(LiveCacheParser.parseKits(client, null));
			String newLog = drainLog();
			assertEquals("round " + round, before, after);
			assertEquals("non-selectable kits, round " + round, oldFlagged, newFlagged);
			assertEquals("log, round " + round, alike(oldLog), alike(newLog));
			assertTrue("some definitions fail", oldLog.contains("failed to parse"));
			entries += before.split("\n").length;
		}
		assertTrue("plenty of definitions read: " + entries, entries > ROUNDS * 3000);

		// No config index yet: nothing, and nothing logged.
		Client empty = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
			(proxy, method, args) -> null);
		assertEquals(0, BeforeLiveCacheParser.parseItems(empty).size()
			+ BeforeLiveCacheParser.parseKits(empty).size()
			+ BeforeLiveCacheParser.parseSpotAnims(empty).size());
		assertEquals(0, LiveCacheParser.parseItems(empty).size() + LiveCacheParser.parseKits(empty, null).size()
			+ LiveCacheParser.parseSpotAnims(empty).size());
		assertEquals("", drainLog());
	}
}
