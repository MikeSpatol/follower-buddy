// public:file chronicle
package com.follower.chronicle;

import com.follower.ui.ChatheadRenderer; // public:cut chathead
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Animation;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.NPCComposition;

/**
 * Portraits for the chronicle's plates: a creature or a person drawn from
 * its own model in the game's cache, whole, turning slowly and standing as
 * it stands in the world, in its own idle animation, framed to fill the
 * plate however big or small the thing is.
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

	/** Poses of the idle measured for the framing, spread through it, so no part of the motion leaves the plate. */
	static final int POSES = 16;

	/** A client cycle, the unit animation frame lengths are counted in. */
	private static final int CYCLE_MS = 20;

	private final Client client;
	private final Map<Integer, Model> models = new HashMap<>();
	private final Set<Integer> failed = new HashSet<>();
	private final Map<Integer, Animation> animations = new HashMap<>();
	private final Set<Integer> noAnimation = new HashSet<>();
	private final Map<String, int[]> framing = new HashMap<>();

	public ChroniclePortraits(Client client)
	{
		this.client = client;
	}

	/** Lets every model and animation built go, for a plugin switched off; drawn again as wanted. */
	public void clear()
	{
		models.clear();
		failed.clear();
		animations.clear();
		noAnimation.clear();
		framing.clear();
	}

	/** The yaw for this moment of the turn. */
	public static int yawAt(long nowMs)
	{
		return (int) ((nowMs % TURN_MS) * 2048 / TURN_MS);
	}

	/**
	 * The portrait at a yaw and a moment of its idle, or null if the model
	 * cannot be had. Unseen, it is the same shape in flat ink: the outline is
	 * a hint, not a spoiler. With no idle, or one that will not load, it
	 * stands still.
	 */
	public BufferedImage draw(int npcId, Integer idleId, int width, int height, int yaw, long nowMs,
		boolean unseen)
	{
		Model model = model(npcId);
		if (model == null)
		{
			return null;
		}
		Animation idle = animation(idleId);
		int[] frame = frame(npcId, model, idle, width, height);
		if (frame == null)
		{
			return null;
		}
		// The posed model is the client's shared scratch, overwritten by the
		// next pose anyone asks for, so it is drawn now and never kept; the
		// model at rest is its frame reference, as the dialog's head is.
		Model posed = idle == null ? null : client.applyTransformations(model, idle, frameAt(idle, nowMs), null, 0);
		BufferedImage image = ChatheadRenderer.render(posed == null ? model : posed, model, width, height, // public:cut chathead
		// public:else BufferedImage image = null;
			yaw, PITCH, frame[0], 1.0, frame[1], frame[2]); // public:cut chathead
		return unseen && image != null ? silhouette(image) : image;
	}

	private static int frameAt(Animation idle, long nowMs)
	{
		return frameAt(idle.isMayaAnim(), idle.getDuration(), idle.getFrameLengths(), nowMs);
	}

	/**
	 * The frame of a looping animation at a moment on the wall clock, as the
	 * client's own AnimationController counts it: an older animation steps
	 * through frames that each last so many client cycles, and a newer one
	 * is addressed by the cycle itself.
	 */
	static int frameAt(boolean newer, int duration, int[] lengths, long nowMs)
	{
		long cycles = Math.max(0, nowMs) / CYCLE_MS;
		if (newer)
		{
			return duration > 0 ? (int) (cycles % duration) : 0;
		}
		if (lengths == null || lengths.length == 0)
		{
			return 0;
		}
		long loop = 0;
		for (int length : lengths)
		{
			loop += Math.max(1, length);
		}
		long into = cycles % loop;
		for (int i = 0; i < lengths.length; i++)
		{
			into -= Math.max(1, lengths[i]);
			if (into < 0)
			{
				return i;
			}
		}
		return lengths.length - 1;
	}

	/** The frames measured for the framing: the first, and the rest spread evenly through the loop. */
	static int[] sampledFrames(boolean newer, int duration, int[] lengths)
	{
		int count = newer ? duration : lengths == null ? 0 : lengths.length;
		if (count <= 1)
		{
			return new int[]{0};
		}
		int samples = Math.min(POSES, count);
		int[] out = new int[samples];
		for (int i = 0; i < samples; i++)
		{
			out[i] = i * count / samples;
		}
		return out;
	}

	/** Every drawn pixel in flat ink, the rest left clear. */
	static BufferedImage silhouette(BufferedImage image)
	{
		int w = image.getWidth();
		int h = image.getHeight();
		int[] pixels = image.getRGB(0, 0, w, h, null, 0, w);
		int ink = 0xE6000000 | SILHOUETTE;
		for (int i = 0; i < pixels.length; i++)
		{
			pixels[i] = (pixels[i] >>> 24) != 0 ? ink : 0;
		}
		BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		out.setRGB(0, 0, w, h, pixels, 0, w);
		return out;
	}

	private Animation animation(Integer id)
	{
		if (id == null || id < 0 || noAnimation.contains(id))
		{
			return null;
		}
		Animation cached = animations.get(id);
		if (cached != null)
		{
			return cached;
		}
		Animation loaded = null;
		try
		{
			loaded = client.loadAnimation(id);
		}
		catch (RuntimeException e)
		{
			log.debug("Could not load idle animation {}", id, e); // public:cut logs
		}
		boolean playable = loaded != null && (loaded.isMayaAnim()
			? loaded.getDuration() > 0
			: loaded.getFrameLengths() != null && loaded.getFrameLengths().length > 0);
		if (!playable)
		{
			noAnimation.add(id);
			return null;
		}
		animations.put(id, loaded);
		return loaded;
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
			log.debug("Could not build a portrait of NPC {}", npcId, e); // public:cut logs
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
	 * of it. Measured once, round a full turn and through the idle, by
	 * projecting the model exactly as it is drawn and taking the bounds of
	 * what it would cover, which is right for a goblin and a dragon alike
	 * where a rule of thumb on the model's size would be a guess.
	 */
	private int[] frame(int npcId, Model model, Animation idle, int width, int height)
	{
		String key = npcId + ":" + (idle == null ? "still" : idle.getId()) + ":" + width + "x" + height;
		int[] cached = framing.get(key);
		if (cached != null)
		{
			return cached;
		}
		List<Supplier<Model>> poses = new ArrayList<>();
		if (idle == null)
		{
			poses.add(() -> model);
		}
		else
		{
			for (int f : sampledFrames(idle.isMayaAnim(), idle.getDuration(), idle.getFrameLengths()))
			{
				poses.add(() ->
				{
					Model posed = client.applyTransformations(model, idle, f, null, 0);
					return posed == null ? model : posed;
				});
			}
		}
		int[] fitted = fit(model, poses, width, height);
		if (fitted != null)
		{
			framing.put(key, fitted);
		}
		return fitted;
	}

	static int[] fit(Model model, int width, int height)
	{
		return fit(model, Collections.singletonList(() -> model), width, height);
	}

	/**
	 * The same, measured over every pose given: each is asked for only when
	 * it is drawn, because a posed model is the client's shared scratch and
	 * the next pose overwrites it.
	 */
	static int[] fit(Model model, List<Supplier<Model>> poses, int width, int height)
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
		// A first guess, then corrected by what it would draw, measured with
		// the origin at (0, 0) and nothing clipped.
		int zoom = (int) Math.max(200, Math.max(tall, radius * 2) * 512 / (Math.min(width, height) * 0.8));
		int[] box = turnBounds(poses, zoom, 0, 0);
		if (box == null)
		{
			return null;
		}
		double scale = Math.max((box[2] - box[0]) / (width * 0.90), (box[3] - box[1]) / (height * 0.84));
		zoom = (int) Math.max(50, zoom * scale);
		box = turnBounds(poses, zoom, 0, 0);
		if (box == null)
		{
			return null;
		}
		// Where the origin must go for the drawn bounds to sit in the middle.
		int anchorX = width / 2 - (box[0] + box[2]) / 2;
		int anchorY = height / 2 - (box[1] + box[3]) / 2;
		return new int[]{zoom, anchorX, anchorY};
	}

	/**
	 * The union of what is drawn over eight angles of every pose: minX,
	 * minY, maxX, maxY. Measured by projecting, not drawing, so every pose
	 * of an idle can be measured in less time than drawing one took.
	 */
	private static int[] turnBounds(List<Supplier<Model>> poses, int zoom, int ax, int ay)
	{
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		for (Supplier<Model> pose : poses)
		{
			Model model = pose.get();
			for (int step = 0; step < 8; step++)
			{
				int[] box = ChatheadRenderer.drawnBounds(model, step * 256, PITCH, zoom, ax, ay); // public:cut chathead
				// public:else int[] box = null;
				if (box == null)
				{
					continue;
				}
				minX = Math.min(minX, box[0]);
				minY = Math.min(minY, box[1]);
				maxX = Math.max(maxX, box[2]);
				maxY = Math.max(maxY, box[3]);
			}
		}
		return minX > maxX ? null : new int[]{minX, minY, maxX, maxY};
	}
}
