// public:file chathead
package com.follower.ui;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.runelite.api.Model;

/**
 * Renders a model to an image, in software.
 *
 * <p>Chathead widgets can only show models the cache already holds - a widget
 * takes a model ID, and there is no way to hand it a composed one - so the
 * follower's own head is unreachable through the game's dialog interface. It is
 * reachable this way: the model exposes its vertices, faces and already-lit face
 * colours, so projecting and filling the triangles here produces a real picture
 * of the actual follower rather than a stand-in.
 *
 * <p>Uses the client's own widget-model camera - perspective, orbited to the
 * dialog's pitch at its zoom - with painter's-algorithm depth sorting, and fills
 * triangles through {@link GouraudRasterizer}, a faithful port of the client's
 * integer scanline rasterizer, so the pixels come out the way the game's own
 * renderer produces them - down to which pixels a two-pixel pupil covers. */
public final class ChatheadRenderer
{
	private ChatheadRenderer()
	{
	}

	/**
	 * The client's own chathead camera, read off live dialog widgets.
	 *
	 * <p>The head's angle arrives as the widget's rotationZ, but it drives THIS
	 * renderer's yaw: RuneLite's rotationZ is the axis that turns a chathead in
	 * the client's widget pipeline. NPC dialogs use 1882 and player dialogs 166 -
	 * exact mirrors (1882 = 2048 - 166), which is why an NPC and your own
	 * character face opposite ways. Applying it as a true Z-roll instead tilts the
	 * head sideways in the image plane, which is visibly wrong.
	 */
	public static final int GAME_YAW = 0; // public:cut dev
	public static final int GAME_PITCH = 40;
	public static final int GAME_TURN_NPC = 1882;
	public static final int GAME_TURN_PLAYER = 166;
	public static final int GAME_ZOOM = 796;

	/** The client's projection: screen = model * 512 / depth. */
	private static final double FOCAL_LENGTH = 512;

	// public:cut dev
	/** Anchored at the centre, for the developer's sweep of angles. */
	public static BufferedImage render(Model model, Model boundsFrom, int width, int height,
		int yaw, int cameraPitch, int zoom, double headFraction)
	{
		return render(model, boundsFrom, width, height, yaw, cameraPitch, zoom,
			headFraction, -1, -1);
	}

	// public:end dev
	/**
	 * @param model        the (possibly posed) model to draw
	 * @param boundsFrom   model to compute framing from, or null to use
	 *                     {@code model}. An animated head is framed by its UNPOSED
	 *                     base so the picture doesn't rescale as the jaw moves.
	 * @param yaw          model rotation about the vertical axis, in JAU - the
	 *                     widget's rotationY
	 * @param cameraPitch  the widget's rotationX. NOT a model tilt: the client
	 *                     orbits the CAMERA to this pitch at {@code zoom} distance
	 * @param zoom         camera distance, the widget's modelZoom
	 * @param headFraction share of the model's height, from the top, to include;
	 *                     1.0 draws the whole model (a real chathead model)
	 * @param anchorX      pixel where the model's ORIGIN projects, or -1 for centre.
	 *                     The client anchors widget models at the widget's centre and
	 *                     lets geometry overflow, clipped by the interface surface -
	 *                     which is how tall hair gets cut at the dialog's top edge.
	 * @return the rendered image, or null if the model cannot be drawn
	 */
	public static BufferedImage render(Model model, Model boundsFrom, int width, int height,
		int yaw, int cameraPitch, int zoom, double headFraction, int anchorX, int anchorY)
	{
		if (model == null || width <= 0 || height <= 0)
		{
			return null;
		}
		Model frame = boundsFrom == null ? model : boundsFrom;

		float[] vx = model.getVerticesX();
		float[] vy = model.getVerticesY();
		float[] vz = model.getVerticesZ();
		float[] fx = frame.getVerticesX();
		float[] fy = frame.getVerticesY();
		int[] fa = model.getFaceIndices1();
		int[] fb = model.getFaceIndices2();
		int[] fc = model.getFaceIndices3();
		int[] colors = model.getFaceColors1();

		if (vx == null || vy == null || vz == null || fx == null || fy == null
			|| fa == null || fb == null || fc == null || colors == null)
		{
			return null;
		}

		int verts = Math.min(model.getVerticesCount(), vx.length);
		if (Math.min(frame.getVerticesCount(), fx.length) != verts)
		{
			// A frame reference must be the same geometry (the unposed original).
			return null;
		}

		// The client's exact widget-model camera, from its drawInterface:
		//     eyeY = sin(rotationX) * zoom;  eyeZ = cos(rotationX) * zoom
		//     objRender(0, rotationY, 0, rotationX, 0, eyeY, eyeZ)
		// So the model is yawed, pushed out to `zoom`, and viewed by a camera
		// ORBITED to pitch rotationX - with a perspective divide. Approximating
		// that as a model-space tilt with an orthographic projection is what made
		// the framing need a fudged yaw to look right.
		double yawAngle = yaw / 2048.0 * 2.0 * Math.PI;
		double yawSin = Math.sin(yawAngle);
		double yawCos = Math.cos(yawAngle);
		double pitchAngle = cameraPitch / 2048.0 * 2.0 * Math.PI;
		double pitchSin = Math.sin(pitchAngle);
		double pitchCos = Math.cos(pitchAngle);
		double eyeY = pitchSin * zoom;
		double eyeZ = pitchCos * zoom;

		// The client's own projection scale, not a fit-to-box. Widget models are
		// drawn at a fixed focal length about the model's ORIGIN, and they
		// overflow their widget rectangle freely - the real chathead widget is
		// 32x32 while the head it draws is far bigger. Fitting to a box instead
		// made size a guess; this makes it a consequence of zoom, exactly as in
		// game.
		int offsetX = anchorX < 0 ? width / 2 : anchorX;
		int offsetY = anchorY < 0 ? height / 2 : anchorY;
		// vertexScreenZ, the client's convention: camera depth relative to the
		// model origin's depth (midZ), an INTEGER per vertex.
		double midZ = eyeY * pitchSin + eyeZ * pitchCos;

		float[] rz = new float[verts];
		int[] sx = new int[verts];
		int[] sy = new int[verts];
		int[] sz = new int[verts];
		float minY = Float.MAX_VALUE;
		float maxY = -Float.MAX_VALUE;
		// The client's calculateBoundsCylinder, for the depth-bucket range.
		double boundMaxY = 0;
		double boundMinY = 0;
		double radiusSqr = 0;
		for (int i = 0; i < verts; i++)
		{
			// One vertex through the client's widget-model transform: yaw the
			// model, push it to the camera distance, orbit the camera to its
			// pitch, then divide by depth.
			double x = vz[i] * yawSin + vx[i] * yawCos;
			double z = vz[i] * yawCos - vx[i] * yawSin + eyeZ;
			double y = vy[i] + eyeY;
			double cy = y * pitchCos - z * pitchSin;
			double cz = y * pitchSin + z * pitchCos;
			// Behind or level with the camera: park it far away rather than
			// divide by zero; such faces are dropped by the depth guard below.
			boolean behind = cz < 1;
			rz[i] = behind ? -1 : (float) cz;
			// Screen coordinates the client's way: vertexScreenX = centerX +
			// (x << 9) / z, truncated to an INTEGER per vertex before any face
			// is drawn. The rasterizer walks scanlines between these ints;
			// feeding it float pixel centres instead dropped the sub-pixel eye
			// faces.
			sx[i] = offsetX + (int) ((behind ? 0 : (float) (x / cz)) * FOCAL_LENGTH);
			sy[i] = offsetY + (int) ((behind ? 0 : (float) (cy / cz)) * FOCAL_LENGTH);
			sz[i] = (int) (rz[i] - midZ);

			minY = Math.min(minY, fy[i]);
			maxY = Math.max(maxY, fy[i]);
			boundMaxY = Math.max(boundMaxY, -vy[i]);
			boundMinY = Math.max(boundMinY, vy[i]);
			radiusSqr = Math.max(radiusSqr, vx[i] * vx[i] + vz[i] * vz[i]);
		}
		float cutoff = minY + (maxY - minY) * (float) headFraction;
		int radius = (int) (Math.sqrt(radiusSqr) + 0.99);
		int minDepth = (int) (Math.sqrt(radius * radius + boundMaxY * boundMaxY) + 0.99);
		int maxDepth = minDepth + (int) (Math.sqrt(radius * radius + boundMinY * boundMinY) + 0.99);

		// Gouraud shading, the way the game itself draws models: each face carries
		// three per-corner lit colours (faceColors1/2/3), blended across the
		// triangle. Flat-filling with colours1 alone is what made every triangle
		// edge visible and the lighting angular. No antialiasing either - the
		// game's rasterizer has none, and crisp edges are part of the look.
		int[] colors2 = model.getFaceColors2();
		int[] colors3 = model.getFaceColors3();

		// Faces wholly inside the head region OF THE FRAME model, skipping ones
		// the client hides. Using the frame keeps face selection stable across
		// animation frames.
		//
		// The client's draw2 ordering, not a painter's sort. Faces get INTEGER
		// average depths ((zA+zB+zC)/3 + minDepth) and draw far to near, in
		// face-index order within one depth. Model authors rely on that: a pupil
		// sits coplanar ON the eye-white face and is authored LATER in face order
		// so the same depth draws it on top. Sorting by exact float depth instead
		// let the eye-white face land on top of the pupil on some animation
		// frames - the stray white pixels in the eyes.
		//
		// Backface culling happens before that, with the client's own test on the
		// same INTEGER screen coordinates it rasterises with. Without it the BACK
		// of the head - lit from behind, darker - bled through along cheeks and
		// brow as triangular patches.
		boolean anyInHead = false;
		int[] depth = new int[fa.length];
		List<Integer> faces = new ArrayList<>();
		for (int i = 0; i < fa.length; i++)
		{
			int a = fa[i];
			int b = fb[i];
			int c = fc[i];
			if ((colors3 != null && i < colors3.length && colors3[i] == -2)
				|| a >= verts || b >= verts || c >= verts || a < 0 || b < 0 || c < 0
				|| !(fy[a] <= cutoff && fy[b] <= cutoff && fy[c] <= cutoff))
			{
				continue;
			}
			anyInHead = true;
			if (rz[a] < 0 || rz[b] < 0 || rz[c] < 0
				|| (sx[a] - sx[b]) * (sy[c] - sy[b]) - (sy[a] - sy[b]) * (sx[c] - sx[b]) <= 0)
			{
				continue;
			}
			depth[i] = Math.max(0, Math.min(maxDepth, (sz[a] + sz[b] + sz[c]) / 3 + minDepth));
			faces.add(i);
		}
		if (!anyInHead)
		{
			return null;
		}
		// Stable, so faces at one depth keep their face-index order.
		faces.sort((p, q) -> depth[q] - depth[p]);

		int[] pixels = new int[width * height];
		GouraudRasterizer raster = new GouraudRasterizer(pixels, width, height);

		for (int face : resolveDrawOrder(model.getFaceRenderPriorities(), faces, depth))
		{
			int a = fa[face];
			int b = fb[face];
			int c = fc[face];

			// colours3 == -1 flags a flat-shaded face: all corners take colours1.
			// Corners stay PACKED - the client interpolates the palette index
			// across the triangle and looks the table up per pixel, and matching
			// its colours means doing the same. Corner colours pair with vertices
			// A/B/C directly, exactly as the client's drawFace passes them.
			boolean flat = colors3 == null || colors3[face] == -1;
			int colourA = colors[face] & 0xFFFF;
			int colourB = flat || colors2 == null ? colourA : colors2[face] & 0xFFFF;
			int colourC = flat ? colourA : colors3[face] & 0xFFFF;

			raster.gouraudTriangle(sx[a], sx[b], sx[c], sy[a], sy[b], sy[c],
				colourA, colourB, colourC);
		}

		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		image.setRGB(0, 0, width, height, pixels, 0, width);
		return image;
	}

	/**
	 * The client's draw2 emission order. Without per-face render priorities:
	 * far to near, as sorted. With priorities: the client's 12-class algorithm
	 * - faces collect into priority classes in depth order, classes 0..9 emit
	 * in class order, and the special classes 10 and 11 (all of 10, then all of
	 * 11) cut into the stream whenever their next face is deeper than the
	 * running depth averages of classes 1+2 (checked before class 0), 3+4
	 * (before class 3), and 6+8 (before class 5), with the leftovers emitted at
	 * the end. Player and NPC models rely on this to layer coplanar detail
	 * correctly.
	 */
	private static List<Integer> resolveDrawOrder(byte[] priorities, List<Integer> faces, int[] depth)
	{
		if (priorities == null)
		{
			return faces;
		}

		int[][] classes = new int[12][faces.size()];
		int[] counts = new int[12];
		int[] depthSums = new int[12];
		for (int face : faces)
		{
			int priorityClass = Math.max(0, Math.min(11, priorities[face]));
			classes[priorityClass][counts[priorityClass]++] = face;
			depthSums[priorityClass] += depth[face];
		}
		int[] special = Arrays.copyOf(classes[10], counts[10] + counts[11]);
		System.arraycopy(classes[11], 0, special, counts[10], counts[11]);

		List<Integer> order = new ArrayList<>(faces.size());
		int next = 0;
		for (int priority = 0; priority <= 10; priority++)
		{
			int deeperThan = priority == 0 ? average(counts, depthSums, 1, 2)
				: priority == 3 ? average(counts, depthSums, 3, 4)
				: priority == 5 ? average(counts, depthSums, 6, 8)
				: priority == 10 ? -1 : Integer.MAX_VALUE;
			while (next < special.length && depth[special[next]] > deeperThan)
			{
				order.add(special[next++]);
			}
			for (int i = 0; priority < 10 && i < counts[priority]; i++)
			{
				order.add(classes[priority][i]);
			}
		}
		return order;
	}

	/** The running depth average of two priority classes, 0 when both are empty. */
	private static int average(int[] counts, int[] depthSums, int first, int second)
	{
		int count = counts[first] + counts[second];
		return count > 0 ? (depthSums[first] + depthSums[second]) / count : 0;
	}

	// public:cut chronicle
	/**
	 * The pixels {@link #render} would cover, without drawing them: the same
	 * camera, the same integer projection, the same faces kept and turned
	 * away, the whole model ({@code headFraction} 1). minX, minY, maxX, maxY
	 * with the maxima exclusive, unclipped by any canvas, or null when
	 * nothing faces the camera. For framing, which measures a model at many
	 * angles and poses: drawing it each time to find its edges cost far more
	 * than the drawing being framed.
	 */
	public static int[] drawnBounds(Model model, int yaw, int cameraPitch, int zoom, int anchorX, int anchorY)
	{
		if (model == null)
		{
			return null;
		}
		float[] vx = model.getVerticesX();
		float[] vy = model.getVerticesY();
		float[] vz = model.getVerticesZ();
		int[] fa = model.getFaceIndices1();
		int[] fb = model.getFaceIndices2();
		int[] fc = model.getFaceIndices3();
		int[] hidden = model.getFaceColors3();
		if (vx == null || vy == null || vz == null || fa == null || fb == null || fc == null)
		{
			return null;
		}
		int verts = Math.min(model.getVerticesCount(), vx.length);
		double yawAngle = yaw / 2048.0 * 2.0 * Math.PI;
		double yawSin = Math.sin(yawAngle);
		double yawCos = Math.cos(yawAngle);
		double pitchAngle = cameraPitch / 2048.0 * 2.0 * Math.PI;
		double pitchSin = Math.sin(pitchAngle);
		double pitchCos = Math.cos(pitchAngle);
		double eyeY = pitchSin * zoom;
		double eyeZ = pitchCos * zoom;

		int[] sx = new int[verts];
		int[] sy = new int[verts];
		boolean[] behind = new boolean[verts];
		for (int i = 0; i < verts; i++)
		{
			double[] p = project(vx[i], vy[i], vz[i], yawSin, yawCos, pitchSin, pitchCos, eyeY, eyeZ);
			behind[i] = p[2] < 0;
			sx[i] = anchorX + (int) (p[0] * FOCAL_LENGTH);
			sy[i] = anchorY + (int) (p[1] * FOCAL_LENGTH);
		}

		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		for (int face = 0; face < fa.length; face++)
		{
			if (hidden != null && face < hidden.length && hidden[face] == -2)
			{
				continue;
			}
			int a = fa[face];
			int b = fb[face];
			int c = fc[face];
			if (a >= verts || b >= verts || c >= verts || a < 0 || b < 0 || c < 0
				|| behind[a] || behind[b] || behind[c])
			{
				continue;
			}
			if ((sx[a] - sx[b]) * (sy[c] - sy[b]) - (sy[a] - sy[b]) * (sx[c] - sx[b]) <= 0)
			{
				continue;
			}
			minX = Math.min(minX, Math.min(sx[a], Math.min(sx[b], sx[c])));
			maxX = Math.max(maxX, Math.max(sx[a], Math.max(sx[b], sx[c])));
			minY = Math.min(minY, Math.min(sy[a], Math.min(sy[b], sy[c])));
			maxY = Math.max(maxY, Math.max(sy[a], Math.max(sy[b], sy[c])));
		}
		return minX > maxX ? null : new int[]{minX, minY, maxX + 1, maxY + 1};
	}

	/**
	 * One vertex through the client's widget-model transform: yaw the model, push
	 * it to the camera distance, orbit the camera to its pitch, then divide by
	 * depth. Returns screen x, screen y and camera-space depth.
	 */
	private static double[] project(double vx, double vy, double vz,
		double yawSin, double yawCos, double pitchSin, double pitchCos,
		double eyeY, double eyeZ)
	{
		double x = vz * yawSin + vx * yawCos;
		double z = vz * yawCos - vx * yawSin;
		double y = vy;

		y += eyeY;
		z += eyeZ;

		double cy = y * pitchCos - z * pitchSin;
		double cz = y * pitchSin + z * pitchCos;

		// Behind or level with the camera: park it far away rather than divide by
		// zero; such faces are dropped by the depth guard when they are drawn.
		if (cz < 1)
		{
			return new double[]{0, 0, -1};
		}
		return new double[]{x / cz, cy / cz, cz};
	}

	// public:end chronicle
}
