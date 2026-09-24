package com.follower.chronicle;

import com.follower.ui.ChatheadRenderer;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.NPCComposition;

/**
 * Portraits for the chronicle's plates: a creature or a person drawn from
 * its own model in the game's cache, whole, turning slowly, framed to fill
 * the plate however big or small the thing is.
 *
 * <p>The drawing is {@link ChatheadRenderer}, the port of the client's own
 * rasterizer that already draws the follower's face in the dialog, so a
 * goblin on the page is the goblin in the world, pixel for pixel, rather
 * than a picture of one. Models are built on the client thread, which is
 * where an overlay renders, and kept.
 */
@Slf4j
public class ChroniclePortraits
{
	/** A touch above eye level, as a plate is drawn. */
	static final int PITCH = 96;

	/** One turn every so many milliseconds; slow enough to study. */
	static final int TURN_MS = 14000;

	/** The ink a thing not yet seen is drawn in. */
	static final int SILHOUETTE = 0x1a1510;

	private final Client client;
	private final Map<Integer, Model> models = new HashMap<>();
	private final Set<Integer> failed = new HashSet<>();
	private final Map<String, int[]> framing = new HashMap<>();

	public ChroniclePortraits(Client client)
	{
		this.client = client;
	}

	/** The yaw for this moment of the turn. */
	public static int yawAt(long nowMs)
	{
		return (int) ((nowMs % TURN_MS) * 2048 / TURN_MS);
	}

	/**
	 * The portrait at a yaw, or null if the model cannot be had. Unseen, it
	 * is the same shape in flat ink: the outline is a hint, not a spoiler.
	 */
	public BufferedImage draw(int npcId, int width, int height, int yaw, boolean unseen)
	{
		Model model = model(npcId);
		if (model == null)
		{
			return null;
		}
		int[] frame = frame(npcId, model, width, height);
		if (frame == null)
		{
			return null;
		}
		BufferedImage image = ChatheadRenderer.render(model, null, width, height, yaw, PITCH,
			frame[0], 1.0, frame[1], frame[2]);
		return unseen && image != null ? silhouette(image) : image;
	}

	/** Every drawn pixel in flat ink, the rest left clear. */
	static BufferedImage silhouette(BufferedImage image)
	{
		BufferedImage out = new BufferedImage(image.getWidth(), image.getHeight(),
			BufferedImage.TYPE_INT_ARGB);
		int ink = 0xE6000000 | SILHOUETTE;
		for (int y = 0; y < image.getHeight(); y++)
		{
			for (int x = 0; x < image.getWidth(); x++)
			{
				if ((image.getRGB(x, y) >>> 24) != 0)
				{
					out.setRGB(x, y, ink);
				}
			}
		}
		return out;
	}

	private Model model(int npcId)
	{
		Model cached = models.get(npcId);
		if (cached != null || failed.contains(npcId))
		{
			return cached;
		}
		Model built = null;
		try
		{
			built = build(npcId);
		}
		catch (RuntimeException e)
		{
			log.debug("Could not build a portrait of NPC {}", npcId, e);
		}
		if (built == null)
		{
			failed.add(npcId);
			return null;
		}
		models.put(npcId, built);
		return built;
	}

	/** The NPC as the game builds it: its parts merged, recoloured and scaled, then lit. */
	private Model build(int npcId)
	{
		NPCComposition npc = client.getNpcDefinition(npcId);
		if (npc == null || npc.getModels() == null || npc.getModels().length == 0)
		{
			return null;
		}
		int[] ids = npc.getModels();
		ModelData[] parts = new ModelData[ids.length];
		for (int i = 0; i < ids.length; i++)
		{
			parts[i] = client.loadModelData(ids[i]);
			if (parts[i] == null)
			{
				return null;
			}
		}
		// loadModelData shares arrays between callers; a merge or a clone
		// before anything is changed.
		ModelData data = parts.length == 1 ? parts[0].shallowCopy() : client.mergeModels(parts);
		short[] from = npc.getColorToReplace();
		short[] to = npc.getColorToReplaceWith();
		if (from != null && to != null)
		{
			data = data.cloneColors();
			for (int i = 0; i < from.length && i < to.length; i++)
			{
				data.recolor(from[i], to[i]);
			}
		}
		int wide = npc.getWidthScale();
		int tall = npc.getHeightScale();
		if ((wide != 128 && wide > 0) || (tall != 128 && tall > 0))
		{
			data = data.cloneVertices();
			data.scale(wide > 0 ? wide : 128, tall > 0 ? tall : 128, wide > 0 ? wide : 128);
		}
		return data.light(64, 850, -30, -50, -30);
	}

	/**
	 * The camera distance and where the model's origin goes, so the whole
	 * thing fits the plate at every angle of the turn and sits in the middle
	 * of it. Measured once by drawing the model round a full turn and taking
	 * the bounds of what was drawn, which is right for a goblin and a dragon
	 * alike where any arithmetic on the model would be a guess.
	 */
	private int[] frame(int npcId, Model model, int width, int height)
	{
		String key = npcId + ":" + width + "x" + height;
		int[] cached = framing.get(key);
		if (cached != null)
		{
			return cached;
		}
		int[] fitted = fit(model, width, height);
		if (fitted != null)
		{
			framing.put(key, fitted);
		}
		return fitted;
	}

	static int[] fit(Model model, int width, int height)
	{
		float[] vx = model.getVerticesX();
		float[] vy = model.getVerticesY();
		float[] vz = model.getVerticesZ();
		if (vx == null || vy == null || vz == null || model.getVerticesCount() == 0)
		{
			return null;
		}
		double tall = 1;
		double radius = 1;
		for (int i = 0; i < model.getVerticesCount() && i < vx.length; i++)
		{
			tall = Math.max(tall, -vy[i]);
			radius = Math.max(radius, Math.sqrt(vx[i] * vx[i] + vz[i] * vz[i]));
		}
		// A first guess, drawn on a canvas three plates wide so nothing is
		// cut off, then corrected by what was actually drawn.
		int zoom = (int) Math.max(200, Math.max(tall, radius * 2) * 512 / (Math.min(width, height) * 0.8));
		int canvasW = width * 3;
		int canvasH = height * 3;
		int[] box = turnBounds(model, canvasW, canvasH, zoom, canvasW / 2, canvasH / 2);
		if (box == null)
		{
			return null;
		}
		double scale = Math.max((box[2] - box[0]) / (width * 0.90), (box[3] - box[1]) / (height * 0.84));
		zoom = (int) Math.max(50, zoom * scale);
		box = turnBounds(model, canvasW, canvasH, zoom, canvasW / 2, canvasH / 2);
		if (box == null)
		{
			return null;
		}
		// Where the origin must go for the drawn bounds to sit in the middle.
		int anchorX = width / 2 - ((box[0] + box[2]) / 2 - canvasW / 2);
		int anchorY = height / 2 - ((box[1] + box[3]) / 2 - canvasH / 2);
		return new int[]{zoom, anchorX, anchorY};
	}

	/** The union of what is drawn over eight angles: minX, minY, maxX, maxY. */
	private static int[] turnBounds(Model model, int w, int h, int zoom, int ax, int ay)
	{
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		for (int step = 0; step < 8; step++)
		{
			BufferedImage image = ChatheadRenderer.render(model, null, w, h, step * 256, PITCH,
				zoom, 1.0, ax, ay);
			if (image == null)
			{
				continue;
			}
			for (int y = 0; y < h; y++)
			{
				for (int x = 0; x < w; x++)
				{
					if ((image.getRGB(x, y) >>> 24) != 0)
					{
						minX = Math.min(minX, x);
						maxX = Math.max(maxX, x);
						minY = Math.min(minY, y);
						maxY = Math.max(maxY, y);
					}
				}
			}
		}
		return minX > maxX ? null : new int[]{minX, minY, maxX + 1, maxY + 1};
	}
}
