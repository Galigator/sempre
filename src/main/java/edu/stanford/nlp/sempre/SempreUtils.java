package edu.stanford.nlp.sempre;

import fig.basic.LogInfo;
import fig.basic.MapUtils;
import java.util.Map;

/**
 * Created by joberant on 10/18/14.
 */
public final class SempreUtils
{
	private SempreUtils()
	{
	}

	// "java.util.ArrayList" => "java.util.ArrayList"
	// "TypeLookup" => "edu.stanford.nlp.sempre.TypeLookup"
	public static String resolveClassName(final String name)
	{
		if (name.startsWith("edu.") || name.startsWith("org.") || name.startsWith("com.") || name.startsWith("net."))
			return name;
		return "edu.stanford.nlp.sempre." + name;
	}

	public static <K, V> void logMap(final Map<K, V> map, final String desc)
	{
		LogInfo.begin_track("Logging %s map", desc);
		for (final K key : map.keySet())
			LogInfo.log(key + "\t" + map.get(key));
		LogInfo.end_track();
	}

	public static void addToDoubleMap(final Map<String, Double> mutatedMap, final Map<String, Double> addedMap)
	{
		for (final String key : addedMap.keySet())
			MapUtils.incr(mutatedMap, key, addedMap.get(key));
	}
}
