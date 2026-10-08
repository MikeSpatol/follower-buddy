package com.follower.appearance;

import com.google.gson.Gson;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import net.runelite.api.Animation;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.kit.KitType;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

/**
 * A woman sits on the chair a held pose puts in her hand, not above it.
 *
 * <p>Sit on a wooden chair (4073) drops the body by a fixed amount made for
 * a man's hips, and never moves the chair. A woman's hips are modelled
 * higher, so in Elven legwear she sat 9 over the seat where a man sits 3
 * over it, as she does in the game. The composer poses the body as the clip
 * does, measures it against the chair, and lowers it to sit as a man does;
 * the chair stays where it was made, on the ground.
 *
 * <p>Driven through a client whose models are small meshes: a chair's seat
 * 66 up, and legwear whose thighs the clip sits over it. The clip drops every
 * vertex of the body 51, as the real one drops every body label, and leaves
 * the chair where it is.
 */
public class SittingOnTheSeatTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private static final int SIT_ON_A_WOODEN_CHAIR = 4073;
	private static final int EAT_AT_A_TABLE = 4141;
	private static final int FISH_WITH_A_LOBSTER_POT = 619;
	private static final int SIT_DRUNK_ON_A_CHAIR = 2339;

	private static final int CHAIR = 8798;
	private static final int ELVEN_LEGWEAR = 24024;
	/** Carried in the hands as the clip fishes, so the clip moves it. */
	private static final int LOBSTER_POT = 301;
	/** A prop the clip leaves still, whose flat top is too small to sit on. */
	private static final int VIAL = 60001;
	/** What Sit drunk on a chair holds in the other hand, carried as the clip sits. */
	private static final int BEER_GLASS = 6123;

	private static final int CHAIR_MODEL = 12797;
	private static final int HIS_LEGWEAR = 38083;
	private static final int HER_LEGWEAR = 38219;
	private static final int POT_MODEL = 531;
	private static final int VIAL_MODEL = 60002;
	private static final int GLASS_MODEL = 60003;

	/** How far the clip drops the body. */
	private static final int CLIP_DROP = 51;

	/** Every animation the composer loaded, by id. */
	private final List<Integer> clips = new ArrayList<>();

	/** A model's vertices and faces, behind both the ModelData and the Model the client hands out. */
	private static final class Mesh implements InvocationHandler
	{
		final float[] x;
		final float[] y;
		final float[] z;
		final int[][] faces;
		/** Which vertices the clip moves: a body's and a carried prop's, never a chair's. */
		final boolean[] moves;

		Mesh(float[] x, float[] y, float[] z, int[][] faces, boolean[] moves)
		{
			this.x = x;
			this.y = y;
			this.z = z;
			this.faces = faces;
			this.moves = moves;
		}

		Mesh(boolean moves, float[][] vertices, int[][] faces)
		{
			this(new float[vertices.length], new float[vertices.length], new float[vertices.length],
				faces, new boolean[vertices.length]);
			for (int v = 0; v < vertices.length; v++)
			{
				x[v] = vertices[v][0];
				y[v] = vertices[v][1];
				z[v] = vertices[v][2];
				this.moves[v] = moves;
			}
		}

		Mesh copy()
		{
			return new Mesh(x.clone(), y.clone(), z.clone(), faces, moves.clone());
		}

		/** Posed as the clip poses it. */
		Mesh sat()
		{
			Mesh posed = copy();
			for (int v = 0; v < y.length; v++)
			{
				posed.y[v] += moves[v] ? CLIP_DROP : 0;
			}
			return posed;
		}

		ModelData data()
		{
			return (ModelData) Proxy.newProxyInstance(ModelData.class.getClassLoader(),
				new Class<?>[]{ModelData.class}, this);
		}

		Model model()
		{
			return (Model) Proxy.newProxyInstance(Model.class.getClassLoader(), new Class<?>[]{Model.class}, this);
		}

		private int[] corner(int corner)
		{
			int[] out = new int[faces.length];
			for (int f = 0; f < faces.length; f++)
			{
				out[f] = faces[f][corner];
			}
			return out;
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] args)
		{
			switch (method.getName())
			{
				case "getVerticesCount":
					return x.length;
				case "getVerticesX":
					return x;
				case "getVerticesY":
					return y;
				case "getVerticesZ":
					return z;
				case "getFaceCount":
					return faces.length;
				case "getFaceIndices1":
					return corner(0);
				case "getFaceIndices2":
					return corner(1);
				case "getFaceIndices3":
					return corner(2);
				case "cloneVertices":
					return copy().data();
				case "translate":
					for (int v = 0; v < y.length; v++)
					{
						x[v] += (Integer) args[0];
						y[v] += (Integer) args[1];
						z[v] += (Integer) args[2];
					}
					return proxy;
				case "light":
					return copy().model();
				case "hashCode":
					return System.identityHashCode(proxy);
				case "equals":
					return proxy == args[0];
				default:
					return method.getReturnType().isInstance(proxy) ? proxy : null;
			}
		}
	}

	private static Mesh mesh(Object proxy)
	{
		return (Mesh) Proxy.getInvocationHandler(proxy);
	}

	/** The parts end to end, as the client merges them. */
	private static Mesh merge(ModelData[] parts)
	{
		List<float[]> vertices = new ArrayList<>();
		List<Boolean> moves = new ArrayList<>();
		List<int[]> faces = new ArrayList<>();
		for (ModelData part : parts)
		{
			Mesh mesh = mesh(part);
			int base = vertices.size();
			for (int v = 0; v < mesh.y.length; v++)
			{
				vertices.add(new float[]{mesh.x[v], mesh.y[v], mesh.z[v]});
				moves.add(mesh.moves[v]);
			}
			for (int[] face : mesh.faces)
			{
				faces.add(new int[]{base + face[0], base + face[1], base + face[2]});
			}
		}
		Mesh merged = new Mesh(false, vertices.toArray(new float[0][]), faces.toArray(new int[0][]));
		for (int v = 0; v < moves.size(); v++)
		{
			merged.moves[v] = moves.get(v);
		}
		return merged;
	}

	/** The chair's seat, a 58 unit square 66 up, and a leg down to the ground. */
	private static Mesh chair()
	{
		return new Mesh(false, new float[][]{{-29, -66, -29}, {29, -66, -29}, {29, -66, 29}, {-29, -66, 29},
			{-29, 6, -29}, {-27, 6, -29}}, new int[][]{{0, 1, 2}, {0, 2, 3}, {0, 4, 5}});
	}

	/**
	 * Legwear, from thighs modelled at {@code thighs}: the thighs, which the
	 * clip sits over the seat, a knee past its front edge, and a shin hanging
	 * down in front of it, more than 20 below the seat.
	 */
	private static Mesh legwear(float thighs)
	{
		return new Mesh(true, new float[][]{{-20, thighs, -10}, {20, thighs, -10}, {20, thighs, 25},
			{-20, thighs, 25}, {0, thighs, 5}, {0, thighs + 15, 40}, {0, thighs + 45, 28}},
			new int[][]{{0, 1, 4}, {1, 2, 4}, {2, 3, 4}, {3, 0, 4}, {2, 5, 6}});
	}

	/** His thighs sit 3 over the seat once the clip has dropped them, hers 9. */
	private static Mesh his()
	{
		return legwear(-66 - 3 - CLIP_DROP);
	}

	private static Mesh hers()
	{
		return legwear(-66 - 9 - CLIP_DROP);
	}

	/** A lobster pot as flat on top as the chair's seat, but carried. */
	private static Mesh pot()
	{
		return new Mesh(true, new float[][]{{-29, -66, -29}, {29, -66, -29}, {29, -66, 29}, {-29, -66, 29}},
			new int[][]{{0, 1, 2}, {0, 2, 3}});
	}

	/** A 20 unit square top at the seat's height, left still. */
	private static Mesh vial()
	{
		return new Mesh(false, new float[][]{{-10, -66, -10}, {10, -66, -10}, {10, -66, 10}, {-10, -66, 10}},
			new int[][]{{0, 1, 2}, {0, 2, 3}});
	}

	/** A glass held at the side, which the clip carries with the hand. */
	private static Mesh glass()
	{
		return new Mesh(true, new float[][]{{35, -140, 0}, {41, -140, 0}, {38, -152, 3}},
			new int[][]{{0, 1, 2}});
	}

	private AppearanceComposer composer() throws IOException
	{
		Path dir = folder.newFolder().toPath();
		Files.write(dir.resolve(ModelRepository.FILE_NAME), ("{\"version\": " + ModelRepository.SUPPORTED_VERSION
			+ ", \"items\": {"
			+ "\"" + CHAIR + "\": {\"n\": \"null\", \"wp1\": 3, \"mo\": 0, \"fo\": 0,"
			+ " \"m\": [" + CHAIR_MODEL + ", -1, -1], \"f\": [" + CHAIR_MODEL + ", -1, -1]},"
			+ "\"" + ELVEN_LEGWEAR + "\": {\"n\": \"Elven legwear\", \"wp1\": 7,"
			+ " \"m\": [" + HIS_LEGWEAR + ", -1, -1], \"f\": [" + HER_LEGWEAR + ", -1, -1]},"
			+ "\"" + LOBSTER_POT + "\": {\"n\": \"Lobster pot\", \"m\": [" + POT_MODEL + ", -1, -1],"
			+ " \"f\": [" + POT_MODEL + ", -1, -1]},"
			+ "\"" + VIAL + "\": {\"n\": \"Vial\", \"m\": [" + VIAL_MODEL + ", -1, -1], \"f\": [" + VIAL_MODEL + ", -1, -1]},"
			+ "\"" + BEER_GLASS + "\": {\"n\": \"Beer glass\", \"wp1\": 5, \"m\": [" + GLASS_MODEL + ", -1, -1],"
			+ " \"f\": [" + GLASS_MODEL + ", -1, -1]}"
			+ "}, \"kits\": {}}").getBytes(StandardCharsets.UTF_8));
		ModelRepository repository = new ModelRepository(new Gson());
		repository.load(dir);
		Animation clip = (Animation) Proxy.newProxyInstance(Animation.class.getClassLoader(),
			new Class<?>[]{Animation.class}, (proxy, method, args) -> null);
		Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
			(proxy, method, args) ->
			{
				switch (method.getName())
				{
					case "loadModelData":
						return model((Integer) args[0]).data();
					case "mergeModels":
						return merge((ModelData[]) args[0]).data();
					case "loadAnimation":
						clips.add((Integer) args[0]);
						return clip;
					case "applyTransformations":
						return mesh(args[0]).sat().model();
					default:
						return null;
				}
			});
		return new AppearanceComposer(client, repository);
	}

	private static Mesh model(int id)
	{
		switch (id)
		{
			case CHAIR_MODEL:
				return chair();
			case HIS_LEGWEAR:
				return his();
			case HER_LEGWEAR:
				return hers();
			case POT_MODEL:
				return pot();
			case VIAL_MODEL:
				return vial();
			case GLASS_MODEL:
				return glass();
			default:
				return null;
		}
	}

	/** In Elven legwear with a prop in the right hand, as the film dresses a pose. */
	private static Outfit sitting(int gender, int prop, int seatPose)
	{
		Outfit outfit = new Outfit();
		outfit.setGender(gender);
		outfit.setItem(KitType.LEGS, ELVEN_LEGWEAR);
		outfit.setProp(KitType.WEAPON, prop);
		outfit.setSeatPose(seatPose);
		return outfit;
	}

	/**
	 * How far down the composed model has each part from where it was made:
	 * the prop, merged first from the weapon slot, then the legwear.
	 */
	private static String moved(Model composed, Mesh prop, Mesh legwear)
	{
		return "prop " + shift(composed, 0, prop) + ", body " + shift(composed, prop.y.length, legwear);
	}

	/** How far down the composed model has one part, merged from vertex {@code from}, from where it was made. */
	private static Set<Float> shift(Model composed, int from, Mesh part)
	{
		float[] y = composed.getVerticesY();
		Set<Float> moved = new TreeSet<>();
		for (int v = 0; v < part.y.length; v++)
		{
			moved.add(y[from + v] - part.y[v]);
		}
		return moved;
	}

	@Test
	public void aWomanSitsOnTheChairNotAboveIt() throws IOException
	{
		Model sat = composer().compose(sitting(1, CHAIR, SIT_ON_A_WOODEN_CHAIR));
		assertEquals("her body lowered 6, to sit 3 over the seat as a man does, the chair where it was made",
			"prop [0.0], body [6.0]", moved(sat, chair(), hers()));
		assertEquals(Collections.singletonList(SIT_ON_A_WOODEN_CHAIR), clips);
	}

	@Test
	public void aManSitsWhereTheClipSitsHim() throws IOException
	{
		Model sat = composer().compose(sitting(0, CHAIR, SIT_ON_A_WOODEN_CHAIR));
		assertEquals("prop [0.0], body [0.0]", moved(sat, chair(), his()));
	}

	/**
	 * Sit drunk on a chair has a beer glass in the other hand, which the clip
	 * carries. It goes down with the hand that holds it: left with the chair,
	 * it was up to 13 off Rombie's hand.
	 */
	@Test
	public void whatTheOtherHandCarriesGoesDownWithIt() throws IOException
	{
		Outfit outfit = sitting(1, CHAIR, SIT_DRUNK_ON_A_CHAIR);
		outfit.setProp(KitType.SHIELD, BEER_GLASS);
		Model sat = composer().compose(outfit);
		Mesh chair = chair();
		Mesh glass = glass();
		assertEquals("the chair where it was made, the glass and her body lowered 6", "[0.0] [6.0] [6.0]",
			shift(sat, 0, chair) + " " + shift(sat, chair.y.length, glass) + " "
				+ shift(sat, chair.y.length + glass.y.length, hers()));
	}

	@Test
	public void aCarriedPropOrASmallTopIsNoSeat() throws IOException
	{
		// The pot's top is the chair's seat, but the clip carries it with the
		// body, so it is no seat to measure against.
		Model fishing = composer().compose(sitting(1, LOBSTER_POT, FISH_WITH_A_LOBSTER_POT));
		assertEquals("prop [0.0], body [0.0]", moved(fishing, pot(), hers()));

		Model holding = composer().compose(sitting(1, VIAL, SIT_ON_A_WOODEN_CHAIR));
		assertEquals("too small to sit on", "prop [0.0], body [0.0]", moved(holding, vial(), hers()));
	}

	@Test
	public void withoutAHeldPoseNothingIsMeasured() throws IOException
	{
		Model walking = composer().compose(sitting(1, CHAIR, 0));
		assertEquals("prop [0.0], body [0.0]", moved(walking, chair(), hers()));
		assertEquals("no clip loaded", Collections.emptyList(), clips);
	}

	@Test
	public void theSameChairInAnotherPoseIsAnotherModel()
	{
		Outfit sitting = sitting(1, CHAIR, SIT_ON_A_WOODEN_CHAIR);
		assertNotEquals(sitting, sitting(1, CHAIR, EAT_AT_A_TABLE));
		assertNotEquals(sitting, sitting(1, CHAIR, 0));
		assertEquals("a copy keeps its pose", sitting, new Outfit(sitting));
		assertEquals(sitting.hashCode(), new Outfit(sitting).hashCode());
	}
}
