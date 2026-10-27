package com.follower;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import net.runelite.client.util.ReflectUtil;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Reads every setting the way the client does.
 *
 * <p>RuneLite never implements FollowerConfig with a class of ours: ConfigManager
 * makes it a java.lang.reflect.Proxy, a class the JDK defines in a package of its
 * own, and runs each item's default method through a MethodHandle
 * (ConfigInvocationHandler.callDefaultMethod). The proxy casts what comes back to
 * the item's declared type, so a type that package cannot see - a package-private
 * enum, say - throws IllegalAccessError on every read, and javac does not mind.
 * The other tests build FollowerConfig as anonymous classes in this package, which
 * see everything, so this is the one that would notice. The public build's export
 * rewrites take "public" off what one package no longer needs; this checks they
 * keep it on every type a setting names.
 */
public class ProxyCheckTest
{
	@Test
	public void everySettingReadsThroughTheClientsProxy() throws Exception
	{
		FollowerConfig config = (FollowerConfig) Proxy.newProxyInstance(FollowerConfig.class.getClassLoader(),
			new Class<?>[]{FollowerConfig.class}, (proxy, method, args) ->
			{
				if (!method.isDefault())
				{
					return null;
				}
				Class<?> declaring = method.getDeclaringClass();
				return ReflectUtil.privateLookupIn(declaring).unreflectSpecial(method, declaring)
					.bindTo(proxy).invokeWithArguments(args);
			});
		List<String> failed = new ArrayList<>();
		int read = 0;
		for (Method item : FollowerConfig.class.getMethods())
		{
			if (!item.isDefault() || item.getParameterCount() != 0)
			{
				continue;
			}
			try
			{
				item.invoke(config);
				read++;
			}
			catch (InvocationTargetException e)
			{
				failed.add(item.getName() + ": " + e.getCause());
			}
		}
		assertEquals("settings the client cannot read: " + failed, 0, failed.size());
		assertTrue("read " + read + " settings", read > 50);
	}
}
