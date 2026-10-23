// public:file devdump
package com.follower.appearance;

import com.google.gson.Gson;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.ModelData;
import net.runelite.api.kit.KitType;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;

/**
 * A woman's worn model that Jagex rigged to the wrong arm is drawn from the
 * man's.
 *
 * <p>Write with a quill (13302) writes with the left hand and holds the
 * scroll still in the right. The man's quill (59224) is labelled for the left
 * hand; the woman's (59225) is the same quill moved by (0, 7, -1), but
 * labelled for the right hand, so on a woman the quill stayed by the
 * scroll while the hand wrote without it. The composer is asked here which
 * models it loads and how it moves them, through a client that notes both.
 */
public class WrongArmModelTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private static final int QUILL = 31829;
	private static final int CHART = 31830;

	/** Every model the composer loaded, by id. */
	private final List<Integer> loaded = new ArrayList<>();

	/** Every move it made of a loaded model, as "model by x,y,z". */
	private final List<String> moved = new ArrayList<>();

	/** The cache's own entries for the quill and the chart: no offset for either. */
	private AppearanceComposer composer() throws IOException
	{
		Path dir = folder.newFolder().toPath();
		Files.write(dir.resolve(ModelRepository.FILE_NAME), ("{\"version\": " + ModelRepository.SUPPORTED_VERSION
			+ ", \"items\": {"
			+ "\"" + QUILL + "\": {\"n\": \"null\", \"mo\": 0, \"fo\": 0, \"m\": [59224, -1, -1], \"f\": [59225, -1, -1]},"
			+ "\"" + CHART + "\": {\"n\": \"null\", \"mo\": 0, \"fo\": 0, \"m\": [59237, -1, -1], \"f\": [59238, -1, -1]}"
			+ "}, \"kits\": {}}").getBytes(StandardCharsets.UTF_8));
		ModelRepository repository = new ModelRepository(new Gson());
		repository.load(dir);
		Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
			(proxy, method, args) ->
			{
				switch (method.getName())
				{
					case "loadModelData":
						loaded.add((Integer) args[0]);
						return model((Integer) args[0]);
					case "mergeModels":
						return model(-1);
					default:
						return null;
				}
			});
		return new AppearanceComposer(client, repository);
	}

	/** A model whose copies are itself, noting each move made of it. */
	private ModelData model(int id)
	{
		return (ModelData) Proxy.newProxyInstance(ModelData.class.getClassLoader(), new Class<?>[]{ModelData.class},
			(proxy, method, args) ->
			{
				if (method.getName().equals("translate"))
				{
					moved.add(id + " by " + args[0] + "," + args[1] + "," + args[2]);
				}
				return method.getReturnType().isInstance(proxy) ? proxy : null;
			});
	}

	/** Dressed as the film dresses Write with a quill. */
	private static Outfit writing(int gender)
	{
		Outfit outfit = new Outfit();
		outfit.setGender(gender);
		outfit.setProp(KitType.SHIELD, QUILL);
		outfit.setProp(KitType.WEAPON, CHART);
		return outfit;
	}

	@Test
	public void aWomansQuillIsTheMansModelMovedToWhereHersSits() throws IOException
	{
		composer().compose(writing(1));
		assertEquals("the man's quill, on the writing wrist, and her own chart",
			new HashSet<>(Arrays.asList(59224, 59238)), new HashSet<>(loaded));
		assertEquals("and nothing else", 2, loaded.size());
		assertEquals("moved as hers is from his", Collections.singletonList("59224 by 0,7,-1"), moved);
	}

	@Test
	public void aMansQuillIsHisOwnWhereItIs() throws IOException
	{
		composer().compose(writing(0));
		assertEquals(new HashSet<>(Arrays.asList(59224, 59237)), new HashSet<>(loaded));
		assertEquals("nothing moved", Collections.emptyList(), moved);
	}
}
