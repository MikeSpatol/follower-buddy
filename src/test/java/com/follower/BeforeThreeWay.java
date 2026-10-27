package com.follower;

/*
 * The loader as it stood before MergingLoader (commit 310760e), kept verbatim as
 * LoaderDiffTest's reference: the same upgrade scenarios must leave the same
 * files, status, errors, rules, trees and log lines with either. Only the
 * package, the imports of the speech classes it shared, Lombok's @Slf4j and
 * @Getter written out (the tests are built without Lombok) and, in
 * DialogLoader, the dialogs-editor marker round getFile differ. Not used by
 * the plugin.
 */

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Folding an update into a file somebody has been editing.
 *
 * <p>Both the rules and the conversation trees ship with defaults, get edited,
 * and then have to receive the next version without losing the editing. Doing
 * that needs three copies rather than two, because from the player's file alone
 * a line the update ADDED and a line the player DELETED look identical - both
 * are lines the bundled file has and theirs does not. Keep the defaults as they
 * stood at the last merge and the question becomes answerable: bundled against
 * base is what the update did, theirs against base is what the player did.
 *
 * <p>The rule throughout is that upstream wins where the player has not touched
 * something, and the player wins everywhere they have. This is one class rather
 * than two copies because the two loaders drifting apart on a question this
 * subtle would be a bug nobody would find by reading either one.
 */
public final class BeforeThreeWay
{
	private BeforeThreeWay()
	{
	}

	/**
	 * Every field except the named ones. A field the update did not change is
	 * left alone; one the player changed is theirs; the rest take the update's
	 * value, or go if the update dropped them.
	 *
	 * @return how many fields moved
	 */
	public static int fields(JsonObject held, JsonObject base, JsonObject bundled,
		Set<String> except)
	{
		int changes = 0;
		Set<String> keys = new LinkedHashSet<>(bundled.keySet());
		keys.addAll(base.keySet());
		for (String key : keys)
		{
			if (except.contains(key))
			{
				continue;
			}
			JsonElement before = base.get(key);
			JsonElement now = bundled.get(key);
			if (Objects.equals(before, now))
			{
				// The update has nothing to offer here.
				continue;
			}
			if (!Objects.equals(held.get(key), before))
			{
				// The player moved it. A tuned cooldown or a reworded note is
				// a decision, not a stale copy.
				continue;
			}
			if (now == null)
			{
				held.remove(key);
			}
			else
			{
				held.add(key, now);
			}
			changes++;
		}
		return changes;
	}

	/**
	 * An array of strings, merged as a set with a history. What the update
	 * added arrives; what it removed goes, unless the player had already
	 * removed it; everything else - their own lines, their own rewordings - is
	 * left exactly where it is. A list the player never touched takes the
	 * update's whole, in the update's order: a dialog node's lines are said in
	 * order, and a reworded first line appended at the end had the follower
	 * say "Lead on." before its answer (dialogue check, 14 Oct).
	 *
	 * @return how many lines moved
	 */
	public static int strings(JsonObject held, String key, JsonObject base, JsonObject bundled)
	{
		if (!held.has(key) || !held.get(key).isJsonArray())
		{
			return 0;
		}
		List<String> shipped = strings(bundled, key);
		Set<String> was = new HashSet<>(strings(base, key));
		Set<String> now = new HashSet<>(shipped);
		int changes = 0;

		List<String> mine = strings(held, key);
		if (base != null && mine.equals(strings(base, key)) && !mine.equals(shipped))
		{
			for (String line : was)
			{
				changes += now.contains(line) ? 0 : 1;
			}
			for (String line : now)
			{
				changes += was.contains(line) ? 0 : 1;
			}
			JsonArray out = new JsonArray();
			for (String line : shipped)
			{
				out.add(line);
			}
			held.add(key, out);
			return Math.max(changes, 1);
		}

		List<String> kept = new ArrayList<>();
		for (String line : strings(held, key))
		{
			if (was.contains(line) && !now.contains(line))
			{
				changes++;
				continue;
			}
			kept.add(line);
		}
		for (String line : shipped)
		{
			if (!was.contains(line) && !kept.contains(line))
			{
				kept.add(line);
				changes++;
			}
		}
		if (changes > 0)
		{
			JsonArray out = new JsonArray();
			for (String line : kept)
			{
				out.add(line);
			}
			held.add(key, out);
		}
		return changes;
	}

	/** The strings under a key, or empty. */
	public static List<String> strings(JsonObject object, String key)
	{
		List<String> out = new ArrayList<>();
		if (object != null && object.has(key) && object.get(key).isJsonArray())
		{
			for (JsonElement element : object.getAsJsonArray(key))
			{
				if (element.isJsonPrimitive())
				{
					out.add(element.getAsString());
				}
			}
		}
		return out;
	}

	/**
	 * Whether the player has written anything of their own here.
	 *
	 * <p>The only question the first run can honestly ask. With no base there
	 * is no way to tell a deletion from an addition, but a file holding
	 * nothing but shipped lines has plainly not been written into, and can be
	 * brought up to date without taking anything away from anybody.
	 */
	public static boolean untouched(List<String> theirs, List<String> shipped)
	{
		Set<String> known = new HashSet<>(shipped);
		for (String line : theirs)
		{
			if (!known.contains(line))
			{
				return false;
			}
		}
		return true;
	}

	/** The objects of an array, by their {@code id}. */
	public static Map<String, JsonObject> byId(JsonArray array)
	{
		Map<String, JsonObject> map = new HashMap<>();
		if (array == null)
		{
			return map;
		}
		for (JsonElement element : array)
		{
			String id = idOf(element);
			if (id != null)
			{
				map.put(id, element.getAsJsonObject());
			}
		}
		return map;
	}

	public static JsonArray array(JsonObject object, String key)
	{
		return object != null && object.has(key) && object.get(key).isJsonArray()
			? object.getAsJsonArray(key) : null;
	}

	public static String idOf(JsonElement element)
	{
		if (element == null || !element.isJsonObject())
		{
			return null;
		}
		JsonElement id = element.getAsJsonObject().get("id");
		return id != null && id.isJsonPrimitive() ? id.getAsString() : null;
	}
}
