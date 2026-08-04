package com.follower.ui;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
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
 * triangles through {@link GouraudRasterizer}, a verbatim port of the client's
 * integer scanline rasterizer, so the pixels come out the way the game's own
 * renderer produces them - down to which pixels a two-pixel pupil covers. */
@lombok.extern.slf4j.Slf4j
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
	public static final int GAME_YAW = 0;
	public static final int GAME_PITCH = 40;
	public static final int GAME_TURN_NPC = 1882;
	public static final int GAME_TURN_PLAYER = 166;
	public static final int GAME_ZOOM = 796;

	/** The client's projection: screen = model * 512 / depth. */
	private static final double FOCAL_LENGTH = 512;

	/** One-line shading census per render, cheap enough to leave on. */
	private static final boolean LOG_SHADING = false;

	/** Convenience overload: bounds come from the model itself, game camera. */
	public static BufferedImage render(Model model, int width, int height,
		int yaw, double headFraction)
	{
		return render(model, null, width, height, yaw, GAME_PITCH, GAME_ZOOM, headFraction);
	}

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
	 * @return the rendered image, or null if the model cannot be drawn
	 */
	public static BufferedImage render(Model model, Model boundsFrom, int width, int height,
		int yaw, int cameraPitch, int zoom, double headFraction)
	{
		return render(model, boundsFrom, width, height, yaw, cameraPitch, zoom,
			headFraction, -1, -1);
	}

	/**
	 * @param anchorX pixel where the model's ORIGIN projects, or -1 for centre.
	 *                The client anchors widget models at the widget's centre and
	 *                lets geometry overflow, clipped by the interface surface -
	 *                which is how tall hair gets cut at the dialog's top edge.
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
		int[] hidden = model.getFaceColors3();

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

		float[] rx = new float[verts];
		float[] ry = new float[verts];
		float[] rz = new float[verts];

		for (int i = 0; i < verts; i++)
		{
			double[] p = project(vx[i], vy[i], vz[i], yawSin, yawCos, pitchSin, pitchCos, eyeY, eyeZ);
			rx[i] = (float) p[0];
			ry[i] = (float) p[1];
			rz[i] = (float) p[2];
		}

		float minY = Float.MAX_VALUE;
		float maxY = -Float.MAX_VALUE;
		for (int i = 0; i < verts; i++)
		{
			minY = Math.min(minY, fy[i]);
			maxY = Math.max(maxY, fy[i]);
		}
		float cutoff = minY + (maxY - minY) * (float) headFraction;

		// Faces wholly inside the head region OF THE FRAME model, skipping ones
		// the client hides. Using the frame keeps face selection stable across
		// animation frames.
		List<Integer> keep = new ArrayList<>();
		for (int i = 0; i < fa.length; i++)
		{
			if (hidden != null && i < hidden.length && hidden[i] == -2)
			{
				continue;
			}
			int a = fa[i];
			int b = fb[i];
			int c = fc[i];
			if (a >= verts || b >= verts || c >= verts || a < 0 || b < 0 || c < 0)
			{
				continue;
			}
			if (fy[a] <= cutoff && fy[b] <= cutoff && fy[c] <= cutoff)
			{
				keep.add(i);
			}
		}
		if (keep.isEmpty())
		{
			return null;
		}

		// The client's own projection scale, not a fit-to-box. Widget models are
		// drawn at a fixed focal length about the model's ORIGIN, and they
		// overflow their widget rectangle freely - the real chathead widget is
		// 32x32 while the head it draws is far bigger. Fitting to a box instead
		// made size a guess; this makes it a consequence of zoom, exactly as in
		// game.
		int offsetX = anchorX < 0 ? width / 2 : anchorX;
		int offsetY = anchorY < 0 ? height / 2 : anchorY;

		// Screen coordinates the client's way: vertexScreenX = centerX +
		// (x << 9) / z, truncated to an INTEGER per vertex before any face is
		// drawn. The rasterizer walks scanlines between these ints; feeding it
		// float pixel centres instead is what dropped the sub-pixel eye faces.
		int[] sx = new int[verts];
		int[] sy = new int[verts];
		int[] sz = new int[verts];
		// vertexScreenZ, the client's convention: camera depth relative to the
		// model origin's depth (midZ), an INTEGER per vertex.
		double midZ = eyeY * pitchSin + eyeZ * pitchCos;
		for (int i = 0; i < verts; i++)
		{
			sx[i] = offsetX + (int) (rx[i] * FOCAL_LENGTH);
			sy[i] = offsetY + (int) (ry[i] * FOCAL_LENGTH);
			sz[i] = (int) (rz[i] - midZ);
		}

		// The client's calculateBoundsCylinder, for the depth-bucket range.
		double boundMaxY = 0;
		double boundMinY = 0;
		double radiusSqr = 0;
		for (int i = 0; i < verts; i++)
		{
			if (-vy[i] > boundMaxY)
			{
				boundMaxY = -vy[i];
			}
			if (vy[i] > boundMinY)
			{
				boundMinY = vy[i];
			}
			double r = vx[i] * vx[i] + vz[i] * vz[i];
			if (r > radiusSqr)
			{
				radiusSqr = r;
			}
		}
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

		// Faces flagged -1 in colours3 are FLAT shaded - one colour across the
		// whole triangle - and no amount of interpolation smooths those. If a head
		// renders faceted, this ratio says whether the model is authored that way
		// or the blending is at fault.
		if (LOG_SHADING)
		{
			int flatFaces = 0;
			for (int face : keep)
			{
				if (colors3 == null || colors3[face] == -1)
				{
					flatFaces++;
				}
			}
			log.info("chathead: {} faces drawn, {} flat-shaded, {} gouraud",
				keep.size(), flatFaces, keep.size() - flatFaces);
		}

		// The client's draw2 ordering, not a painter's sort. Faces go into
		// INTEGER average-depth buckets ((zA+zB+zC)/3 + minDepth) in face-index
		// order, and buckets are emitted far to near with insertion order kept
		// inside each bucket. Model authors rely on that: a pupil sits coplanar
		// ON the eye-white face and is authored LATER in face order so the same
		// bucket draws it on top. Sorting by exact float depth instead let the
		// eye-white face land on top of the pupil on some animation frames -
		// the stray white pixels in the eyes.
		//
		// Backface culling happens here, before bucketing, with the client's own
		// test on the same INTEGER screen coordinates it rasterises with. Without
		// it the BACK of the head - lit from behind, darker - bled through along
		// cheeks and brow as triangular patches.
		int[] bucketCount = new int[maxDepth + 1];
		int[][] buckets = new int[maxDepth + 1][];
		for (int face : keep)
		{
			int a = fa[face];
			int b = fb[face];
			int c = fc[face];
			if (rz[a] < 0 || rz[b] < 0 || rz[c] < 0)
			{
				continue;
			}
			if ((sx[a] - sx[b]) * (sy[c] - sy[b]) - (sy[a] - sy[b]) * (sx[c] - sx[b]) <= 0)
			{
				continue;
			}

			int depthAverage = (sz[a] + sz[b] + sz[c]) / 3 + minDepth;
			if (depthAverage < 0)
			{
				depthAverage = 0;
			}
			else if (depthAverage > maxDepth)
			{
				depthAverage = maxDepth;
			}
			if (buckets[depthAverage] == null)
			{
				buckets[depthAverage] = new int[8];
			}
			else if (bucketCount[depthAverage] == buckets[depthAverage].length)
			{
				int[] grown = new int[buckets[depthAverage].length * 2];
				System.arraycopy(buckets[depthAverage], 0, grown, 0, bucketCount[depthAverage]);
				buckets[depthAverage] = grown;
			}
			buckets[depthAverage][bucketCount[depthAverage]++] = face;
		}

		int[] drawOrder = resolveDrawOrder(model.getFaceRenderPriorities(),
			buckets, bucketCount, maxDepth, fa.length);

		int[] pixels = new int[width * height];
		GouraudRasterizer raster = new GouraudRasterizer(pixels, width, height);

		for (int face : drawOrder)
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
	 * The client's draw2 emission order, ported line for line. Without per-face
	 * render priorities: buckets far to near, insertion order within each. With
	 * priorities: the client's 12-class algorithm - faces collect into priority
	 * classes in depth order, classes 0..9 emit in class order, and the special
	 * classes 10 and 11 interleave into the stream whenever their next face is
	 * deeper than the running depth averages of classes 1+2 (checked before
	 * class 0), 3+4 (before class 3), and 6+8 (before class 5), with the
	 * leftovers emitted at the end. Player and NPC models rely on this to
	 * layer coplanar detail correctly.
	 */
	private static int[] resolveDrawOrder(byte[] priorities,
		int[][] buckets, int[] bucketCount, int maxDepth, int faceTotal)
	{
		int[] order = new int[faceTotal];
		int emitted = 0;

		if (priorities == null)
		{
			for (int depth = maxDepth; depth >= 0; depth--)
			{
				int count = bucketCount[depth];
				for (int i = 0; i < count; i++)
				{
					order[emitted++] = buckets[depth][i];
				}
			}
			return java.util.Arrays.copyOf(order, emitted);
		}

		int[] priorityFaceCounts = new int[12];
		int[][] priorityFaceLists = new int[12][faceTotal];
		int[] priorityDepthSum = new int[12];
		int[] priority10FaceDepth = new int[faceTotal];
		int[] priority11FaceDepth = new int[faceTotal];

		for (int depth = maxDepth; depth >= 0; depth--)
		{
			int faceCount = bucketCount[depth];
			for (int i = 0; i < faceCount; i++)
			{
				int face = buckets[depth][i];
				int priorityClass = Math.max(0, Math.min(11, priorities[face]));
				int classCount = priorityFaceCounts[priorityClass]++;
				priorityFaceLists[priorityClass][classCount] = face;
				if (priorityClass < 10)
				{
					priorityDepthSum[priorityClass] += depth;
				}
				else if (priorityClass == 10)
				{
					priority10FaceDepth[classCount] = depth;
				}
				else
				{
					priority11FaceDepth[classCount] = depth;
				}
			}
		}

		int averagePriorityDepthSum1_2 = 0;
		if (priorityFaceCounts[1] > 0 || priorityFaceCounts[2] > 0)
		{
			averagePriorityDepthSum1_2 = (priorityDepthSum[1] + priorityDepthSum[2])
				/ (priorityFaceCounts[1] + priorityFaceCounts[2]);
		}

		int averagePriorityDepthSum3_4 = 0;
		if (priorityFaceCounts[3] > 0 || priorityFaceCounts[4] > 0)
		{
			averagePriorityDepthSum3_4 = (priorityDepthSum[3] + priorityDepthSum[4])
				/ (priorityFaceCounts[3] + priorityFaceCounts[4]);
		}

		int averagePriorityDepthSum6_8 = 0;
		if (priorityFaceCounts[6] > 0 || priorityFaceCounts[8] > 0)
		{
			averagePriorityDepthSum6_8 = (priorityDepthSum[6] + priorityDepthSum[8])
				/ (priorityFaceCounts[6] + priorityFaceCounts[8]);
		}

		int priorityFace = 0;
		int priorityFaceCount = priorityFaceCounts[10];
		int[] priorityFaces = priorityFaceLists[10];
		int[] priorityFaceDepths = priority10FaceDepth;
		if (priorityFace == priorityFaceCount)
		{
			priorityFace = 0;
			priorityFaceCount = priorityFaceCounts[11];
			priorityFaces = priorityFaceLists[11];
			priorityFaceDepths = priority11FaceDepth;
		}

		int priorityDepth;
		if (priorityFace < priorityFaceCount)
		{
			priorityDepth = priorityFaceDepths[priorityFace];
		}
		else
		{
			priorityDepth = -1000;
		}

		for (int priority = 0; priority < 10; priority++)
		{
			while (priority == 0 && priorityDepth > averagePriorityDepthSum1_2)
			{
				order[emitted++] = priorityFaces[priorityFace++];
				if (priorityFace == priorityFaceCount && priorityFaces != priorityFaceLists[11])
				{
					priorityFace = 0;
					priorityFaceCount = priorityFaceCounts[11];
					priorityFaces = priorityFaceLists[11];
					priorityFaceDepths = priority11FaceDepth;
				}
				if (priorityFace < priorityFaceCount)
				{
					priorityDepth = priorityFaceDepths[priorityFace];
				}
				else
				{
					priorityDepth = -1000;
				}
			}

			while (priority == 3 && priorityDepth > averagePriorityDepthSum3_4)
			{
				order[emitted++] = priorityFaces[priorityFace++];
				if (priorityFace == priorityFaceCount && priorityFaces != priorityFaceLists[11])
				{
					priorityFace = 0;
					priorityFaceCount = priorityFaceCounts[11];
					priorityFaces = priorityFaceLists[11];
					priorityFaceDepths = priority11FaceDepth;
				}
				if (priorityFace < priorityFaceCount)
				{
					priorityDepth = priorityFaceDepths[priorityFace];
				}
				else
				{
					priorityDepth = -1000;
				}
			}

			while (priority == 5 && priorityDepth > averagePriorityDepthSum6_8)
			{
				order[emitted++] = priorityFaces[priorityFace++];
				if (priorityFace == priorityFaceCount && priorityFaces != priorityFaceLists[11])
				{
					priorityFace = 0;
					priorityFaceCount = priorityFaceCounts[11];
					priorityFaces = priorityFaceLists[11];
					priorityFaceDepths = priority11FaceDepth;
				}
				if (priorityFace < priorityFaceCount)
				{
					priorityDepth = priorityFaceDepths[priorityFace];
				}
				else
				{
					priorityDepth = -1000;
				}
			}

			int count = priorityFaceCounts[priority];
			int[] faces = priorityFaceLists[priority];
			for (int i = 0; i < count; i++)
			{
				order[emitted++] = faces[i];
			}
		}

		while (priorityDepth != -1000)
		{
			order[emitted++] = priorityFaces[priorityFace++];
			if (priorityFace == priorityFaceCount && priorityFaces != priorityFaceLists[11])
			{
				priorityFace = 0;
				priorityFaces = priorityFaceLists[11];
				priorityFaceCount = priorityFaceCounts[11];
				priorityFaceDepths = priority11FaceDepth;
			}
			if (priorityFace < priorityFaceCount)
			{
				priorityDepth = priorityFaceDepths[priorityFace];
			}
			else
			{
				priorityDepth = -1000;
			}
		}

		return java.util.Arrays.copyOf(order, emitted);
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
}
