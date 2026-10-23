package com.follower.appearance;

import net.runelite.api.ModelData;

/**
 * The colour and texture replacements a cache definition applies to its
 * models, under the dump files' field names. Shared by worn items, body kits
 * and spotanims, which all carry them the same way.
 */
public class Recolours
{
	/** Colours to find / replace with. */
	public short[] cf;
	public short[] cr;
	/** Textures to find / replace with. */
	public short[] tf;
	public short[] tr;

	/**
	 * The model with these replacements applied. loadModelData shares its
	 * arrays between callers, so whatever is changed is cloned first.
	 */
	public ModelData recolour(ModelData data)
	{
		if (cf != null && cr != null && cf.length > 0 && cr.length > 0)
		{
			data = data.cloneColors();
			for (int i = 0; i < cf.length && i < cr.length; i++)
			{
				data.recolor(cf[i], cr[i]);
			}
		}
		if (tf != null && tr != null && tf.length > 0 && tr.length > 0)
		{
			data = data.cloneTextures();
			for (int i = 0; i < tf.length && i < tr.length; i++)
			{
				data.retexture(tf[i], tr[i]);
			}
		}
		return data;
	}
}
