package com.follower.appearance;

import java.io.ByteArrayOutputStream;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The cache decoders, which had no test at all.
 *
 * <p>Seven hundred lines of opcode parsing transcribed from another library's
 * bytecode, and everything the follower WEARS comes through it. Until now the
 * only check was {@code ::follower cachecheck} - a manual in-game diff against
 * an offline dump, which catches a regression only if somebody happens to run
 * it, and only on a machine whose cache still has the item in question.
 *
 * <p>The format is a stream of opcodes, each with its own payload, terminated
 * by a zero. That shape fails in a particular way: an opcode whose payload
 * length is wrong does not throw, it desynchronises the stream and every
 * opcode after it decodes garbage from the wrong offset. So the tests below
 * always put something identifiable AFTER the opcode under test - if the
 * payload length is wrong, the trailing value comes out wrong too.
 */
public class LiveCacheParserTest
{
	/** Builds a definition the way the cache stores one. */
	private static final class Def
	{
		private final ByteArrayOutputStream out = new ByteArrayOutputStream();

		Def op(int opcode)
		{
			out.write(opcode);
			return this;
		}

		Def u8(int value)
		{
			out.write(value & 0xFF);
			return this;
		}

		Def u16(int value)
		{
			out.write((value >> 8) & 0xFF);
			out.write(value & 0xFF);
			return this;
		}

		Def i32(int value)
		{
			out.write((value >> 24) & 0xFF);
			out.write((value >> 16) & 0xFF);
			out.write((value >> 8) & 0xFF);
			out.write(value & 0xFF);
			return this;
		}

		/** The cache's string form: bytes then a zero terminator. */
		Def str(String value)
		{
			for (char c : value.toCharArray())
			{
				out.write(c & 0xFF);
			}
			out.write(0);
			return this;
		}

		byte[] end()
		{
			out.write(0);
			return out.toByteArray();
		}
	}

	/** An item is only kept if it has a worn model, so every case needs one. */
	private static Def wearable()
	{
		return new Def().op(23).u16(1111).u8(0);
	}

	// ---------------------------------------------------------------- items

	@Test
	public void anItemsNameAndWornModelsAreRead()
	{
		ModelRepository.Entry entry = LiveCacheParser.decodeItem(
			new Def()
				.op(2).str("Rune platebody")
				.op(23).u16(100).u8(7)
				.op(24).u16(101)
				.op(78).u16(102)
				.op(25).u16(200).u8(9)
				.op(26).u16(201)
				.op(79).u16(202)
				.end());

		assertNotNull(entry);
		assertEquals("Rune platebody", entry.n);
		assertArrayEquals("male models", new int[]{100, 101, 102}, entry.m);
		assertArrayEquals("female models", new int[]{200, 201, 202}, entry.f);
		assertEquals("male offset", Integer.valueOf(7), entry.mo);
		assertEquals("female offset", Integer.valueOf(9), entry.fo);
	}

	@Test
	public void anItemWithNoWornModelIsNotAnOutfitItem()
	{
		// The dumper's filter, mirrored: a cabbage has an inventory model and
		// nothing to wear. Keeping those would put thousands of entries in the
		// catalogue that can never be worn.
		assertNull(LiveCacheParser.decodeItem(
			new Def().op(2).str("Cabbage").op(1).u16(1965).end()));
	}

	@Test
	public void theWearPositionsDecideWhichSlotsAnItemCovers()
	{
		// wp1 is where it goes; wp2 and wp3 are further slots it HIDES. Get
		// these wrong and a full helm stops hiding hair, or a platebody
		// stops hiding arms - visible immediately and hard to attribute.
		ModelRepository.Entry entry = LiveCacheParser.decodeItem(
			wearable().op(13).u8(4).op(14).u8(6).op(27).u8(8).end());

		assertEquals(Integer.valueOf(4), entry.wp1);
		assertEquals(Integer.valueOf(6), entry.wp2);
		assertEquals(Integer.valueOf(8), entry.wp3);
	}

	@Test
	public void theIntFormOfAModelIdIsReadAsWellAsTheShortForm()
	{
		// Model ids outgrew a short, so the cache gained a second encoding for
		// every model opcode. An item using the newer form is every recent
		// item in the game.
		ModelRepository.Entry entry = LiveCacheParser.decodeItem(
			new Def()
				.op(45).i32(70000).u8(3)
				.op(46).i32(70001)
				.op(47).i32(70002)
				.op(48).i32(80000).u8(4)
				.op(49).i32(80001)
				.op(50).i32(80002)
				.end());

		assertArrayEquals(new int[]{70000, 70001, 70002}, entry.m);
		assertArrayEquals(new int[]{80000, 80001, 80002}, entry.f);
		assertEquals(Integer.valueOf(3), entry.mo);
		assertEquals(Integer.valueOf(4), entry.fo);
	}

	@Test
	public void headModelsArriveInBothEncodingsToo()
	{
		ModelRepository.Entry shortForm = LiveCacheParser.decodeItem(
			wearable().op(90).u16(300).op(92).u16(301)
				.op(91).u16(400).op(93).u16(401).end());
		assertArrayEquals(new int[]{300, 301}, shortForm.hm);
		assertArrayEquals(new int[]{400, 401}, shortForm.hf);

		ModelRepository.Entry intForm = LiveCacheParser.decodeItem(
			wearable().op(51).i32(90000).op(52).i32(90001)
				.op(53).i32(95000).op(54).i32(95001).end());
		assertArrayEquals(new int[]{90000, 90001}, intForm.hm);
		assertArrayEquals(new int[]{95000, 95001}, intForm.hf);
	}

	@Test
	public void anItemWithNoHeadModelCarriesNoneRatherThanMinusOnes()
	{
		// The dump omits the arrays entirely rather than filling them with
		// -1, and the repositories are written against that shape.
		ModelRepository.Entry entry = LiveCacheParser.decodeItem(wearable().end());
		assertNull("no male head", entry.hm);
		assertNull("no female head", entry.hf);
	}

	@Test
	public void colourAndTextureReplacementsSurviveWithTheirPairing()
	{
		// These recolour the model. Losing the pairing between find and
		// replace turns a blue cape green rather than failing outright.
		ModelRepository.Entry entry = LiveCacheParser.decodeItem(
			wearable()
				.op(40).u8(2).u16(11).u16(22).u16(33).u16(44)
				.op(41).u8(1).u16(55).u16(66)
				.end());

		assertArrayEquals(new short[]{11, 33}, entry.cf);
		assertArrayEquals(new short[]{22, 44}, entry.cr);
		assertArrayEquals(new short[]{55}, entry.tf);
		assertArrayEquals(new short[]{66}, entry.tr);
	}

	// ------------------------------------- payload lengths, the silent failure

	@Test
	public void everyOpcodeConsumesExactlyItsOwnPayload()
	{
		// The failure this guards. An opcode that reads one byte too few or
		// too many does not throw - it leaves the cursor mid-payload, and the
		// NEXT opcode byte is read from the middle of the previous one. Every
		// field after it is then garbage, decoded from a stream that has
		// slipped.
		//
		// So each opcode is followed by a name, which is only recoverable if
		// the cursor landed exactly where it should have.
		int[][] withPayload = {
			{1, 2}, {12, 4}, {42, 1}, {44, 4}, {75, 2},
			{94, 2}, {95, 2}, {97, 2}, {98, 2}, {110, 2}, {111, 2}, {112, 2},
		};

		for (int[] pair : withPayload)
		{
			Def def = wearable().op(pair[0]);
			for (int i = 0; i < pair[1]; i++)
			{
				def.u8(0x5A);
			}
			ModelRepository.Entry entry =
				LiveCacheParser.decodeItem(def.op(2).str("marker").end());

			assertEquals("opcode " + pair[0] + " left the stream out of step",
				"marker", entry.n);
		}
	}

	@Test
	public void theStringOpcodesConsumeTheirTerminator()
	{
		for (int opcode : new int[]{3, 9, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39})
		{
			ModelRepository.Entry entry = LiveCacheParser.decodeItem(
				wearable().op(opcode).str("ignored").op(2).str("marker").end());
			assertEquals("opcode " + opcode + " mislaid its string terminator",
				"marker", entry.n);
		}
	}

	@Test
	public void theFlagOpcodesReadNothingAtAll()
	{
		for (int opcode : new int[]{11, 15, 16, 65, 160})
		{
			ModelRepository.Entry entry = LiveCacheParser.decodeItem(
				wearable().op(opcode).op(2).str("marker").end());
			assertEquals("opcode " + opcode + " consumed a payload it does not have",
				"marker", entry.n);
		}
	}

	@Test
	public void theMenuSubopListRunsToItsOwnTerminator()
	{
		// Opcode 43 is a group byte then (index, string) pairs until a zero
		// index. A fixed-length read here would slip by however many entries
		// the item happened to carry.
		ModelRepository.Entry entry = LiveCacheParser.decodeItem(
			wearable()
				.op(43).u8(1).u8(1).str("first").u8(2).str("second").u8(0)
				.op(2).str("marker")
				.end());
		assertEquals("marker", entry.n);
	}

	// ------------------------------------------------------------ robustness

	@Test
	public void anUnknownOpcodeIsRefusedRatherThanGuessedAt()
	{
		// The class promises that a malformed or future-format entry is
		// skipped rather than failing the whole catalogue - and skipping
		// depends on this throwing, which the callers catch per definition.
		// Carrying on instead would decode the rest from a slipped stream and
		// put a plausible-looking wrong entry in the catalogue.
		try
		{
			LiveCacheParser.decodeItem(wearable().op(210).u16(1).end());
			org.junit.Assert.fail("an unknown opcode was accepted");
		}
		catch (IllegalStateException expected)
		{
			assertTrue("the message should name the opcode so a future format"
					+ " change can be found: " + expected.getMessage(),
				String.valueOf(expected.getMessage()).contains("210"));
		}
	}

	@Test
	public void aTruncatedDefinitionThrowsRatherThanReturningNonsense()
	{
		// A definition cut off mid-payload. Whatever happens it must not come
		// back as a usable entry, because a wrong model id renders as somebody
		// else's armour.
		byte[] truncated = {23, 0x04};
		try
		{
			ModelRepository.Entry entry = LiveCacheParser.decodeItem(truncated);
			assertNull("a half-read definition must not become an entry", entry);
		}
		catch (RuntimeException expected)
		{
			// Also fine: the caller catches this per definition and skips it.
			assertTrue(true);
		}
	}

	// ----------------------------------------------------------------- kits

	@Test
	public void aKitCarriesItsBodyPartAndModels()
	{
		boolean[] nonSelectable = new boolean[1];
		ModelRepository.Entry entry = LiveCacheParser.decodeKit(
			new Def().op(1).u8(8).op(2).u8(2).u16(500).u16(501).end(), nonSelectable);

		assertNotNull(entry);
		assertEquals("body part", Integer.valueOf(8), entry.bp);
		assertArrayEquals("the kit's models", new int[]{500, 501}, entry.m);
	}

	@Test
	public void aKitOpcodeAlsoConsumesExactlyItsPayload()
	{
		boolean[] nonSelectable = new boolean[1];
		ModelRepository.Entry entry = LiveCacheParser.decodeKit(
			new Def()
				.op(1).u8(8)
				.op(2).u8(1).u16(500)
				.op(40).u8(1).u16(11).u16(22)
				.op(41).u8(1).u16(33).u16(44)
				.end(),
			nonSelectable);

		assertArrayEquals(new short[]{11}, entry.cf);
		assertArrayEquals(new short[]{22}, entry.cr);
		assertArrayEquals(new short[]{33}, entry.tf);
		assertArrayEquals(new short[]{44}, entry.tr);
	}
}
