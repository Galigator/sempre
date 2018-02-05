package edu.stanford.nlp.sempre;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import fig.basic.Fmt;
import fig.basic.LogInfo;
import fig.basic.MapUtils;
import fig.basic.Option;
import fig.basic.Pair;
import fig.basic.ValueComparator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A FeatureVector represents a mapping from feature (string) to value (double). We enforce the convention that each feature is (domain, name), so that the key
 * space isn't a free-for-all.
 *
 * @author Percy Liang
 * @author Jonathan Berant
 */
public class FeatureVector
{
	public static class Options
	{
		@Option(gloss = "When logging, ignore features with zero weight")
		public boolean ignoreZeroWeight = false;
		@Option(gloss = "Log only this number of top and bottom features")
		public int logFeaturesLimit = Integer.MAX_VALUE;
	}

	public static Options opts = new Options();

	// These features map to the value 1 (most common case in NLP).
	private ArrayList<String> indicatorFeatures;
	// General features
	private ArrayList<Pair<String, Double>> generalFeatures;
	// A dense array of features to save memory
	private double[] denseFeatures;
	private static final String DENSE_NAME = "Dns";

	public FeatureVector()
	{
	} // constructor that does nothing

	public FeatureVector(final int numOfDenseFeatures)
	{
		denseFeatures = new double[numOfDenseFeatures];
		Arrays.fill(denseFeatures, 0d);
	}

	private static String toFeature(final String domain, final String name)
	{
		return domain + " :: " + name;
	}

	public void add(final String domain, final String name)
	{
		add(toFeature(domain, name));
	}

	private void add(final String feature)
	{
		if (indicatorFeatures == null)
			indicatorFeatures = new ArrayList<>();
		indicatorFeatures.add(feature);
	}

	public void add(final String domain, final String name, final double value)
	{
		add(toFeature(domain, name), value);
	}

	private void add(final String feature, final double value)
	{
		if (generalFeatures == null)
			generalFeatures = new ArrayList<>();
		generalFeatures.add(Pair.newPair(feature, value));
	}

	public void addWithBias(final String domain, final String name, final double value)
	{
		add(domain, name, value);
		add(domain, name + "-bias", 1);
	}

	// Add histogram features, e.g., domain :: name>=4
	public void addHistogram(final String domain, final String name, final double value)
	{
		addHistogram(domain, name, value, 2, 10, true);
	}

	public void addHistogram(final String domain, final String name, double value, final int initBinSize, final int numBins, final boolean exp)
	{
		double upper = initBinSize;
		String bin = null;
		final int sign = value > 0 ? +1 : -1;
		value = Math.abs(value);
		for (int i = 0; i < numBins; i++)
		{
			final double lastUpper = upper;
			if (i > 0)
				if (exp)
					upper *= initBinSize;
				else
					upper += initBinSize;
			if (value < upper)
			{
				bin = sign > 0 ? lastUpper + ":" + upper : -upper + ":" + -lastUpper;
				break;
			}
		}
		if (bin == null)
			bin = sign > 0 ? ">=" + upper : "<=" + -upper;

		add(domain, name + bin);
	}

	public void addFromString(final String feature, final double value)
	{
		assert feature.contains(" :: ") : feature;
		if (value == 1)
			add(feature);
		else
			add(feature, value);
	}

	public void addDenseFeature(final int index, final double value)
	{
		denseFeatures[index] += value;
	}

	public void add(final FeatureVector that)
	{
		add(that, AllFeatureMatcher.matcher);
	}

	public void add(final double scale, final FeatureVector that)
	{
		add(scale, that, AllFeatureMatcher.matcher);
	}

	public void add(final FeatureVector that, final FeatureMatcher matcher)
	{
		add(1, that, matcher);
	}

	public void add(final double scale, final FeatureVector that, final FeatureMatcher matcher)
	{
		if (that.indicatorFeatures != null)
			for (final String f : that.indicatorFeatures)
				if (matcher.matches(f))
					if (scale == 1)
						add(f);
					else
						add(f, scale);
		if (that.generalFeatures != null)
			for (final Pair<String, Double> pair : that.generalFeatures)
				if (matcher.matches(pair.getFirst()))
					add(pair.getFirst(), scale * pair.getSecond());
		// dense features are always added
		if (that.denseFeatures != null)
			for (int i = 0; i < denseFeatures.length; ++i)
				denseFeatures[i] += scale * that.denseFeatures[i];
	}

	// Return the dot product between this feature vector and the weight vector (parameters).
	public double dotProduct(final Params params)
	{
		double sum = 0;
		if (indicatorFeatures != null)
			for (final String f : indicatorFeatures)
				sum += params.getWeight(f);
		if (generalFeatures != null)
			for (final Pair<String, Double> pair : generalFeatures)
				sum += params.getWeight(pair.getFirst()) * pair.getSecond();
		if (denseFeatures != null)
			for (int i = 0; i < denseFeatures.length; ++i)
				sum += params.getWeight(DENSE_NAME + "_" + i) * denseFeatures[i];
		return sum;
	}

	// Increment |map| by a factor times this feature vector.
	// converts the dense features to a non-dense representation
	public void increment(final double factor, final Map<String, Double> map)
	{
		increment(factor, map, AllFeatureMatcher.matcher);
	}

	public void increment(final double factor, final Map<String, Double> map, final FeatureMatcher matcher)
	{
		if (indicatorFeatures != null)
			for (final String feature : indicatorFeatures)
				if (matcher.matches(feature))
					MapUtils.incr(map, feature, factor);
		if (generalFeatures != null)
			for (final Pair<String, Double> pair : generalFeatures)
				if (matcher.matches(pair.getFirst()))
					MapUtils.incr(map, pair.getFirst(), factor * pair.getSecond());
		if (denseFeatures != null)
			for (int i = 0; i < denseFeatures.length; ++i)
				MapUtils.incr(map, DENSE_NAME + "_" + i, factor * denseFeatures[i]);
	}

	// returns a feature vector where all features are prefixed
	public FeatureVector addPrefix(final String prefix)
	{
		final FeatureVector res = new FeatureVector();
		if (indicatorFeatures != null)
			for (final String feature : indicatorFeatures)
				res.add(prefix + feature);
		if (generalFeatures != null)
			for (final Pair<String, Double> pair : generalFeatures)
				res.add(prefix + pair.getFirst(), pair.getSecond());
		return res;
	}

	@JsonValue
	public Map<String, Double> toMap()
	{
		final HashMap<String, Double> map = new HashMap<>();
		increment(1, map);
		if (denseFeatures != null)
			for (int i = 0; i < denseFeatures.length; ++i)
				map.put(DENSE_NAME + "_" + i, denseFeatures[i]);
		return map;
	}

	@JsonCreator
	public static FeatureVector fromMap(final Map<String, Double> m)
	{
		// TODO (rf):
		// Encoding is lossy.  We guess that value of 1 means indicator, but we
		// could be wrong.
		// TODO(joberant) - takes care of dense features in a non efficient way
		int maxDenseFeaturesIndex = -1;
		for (final Map.Entry<String, Double> entry : m.entrySet())
			if (isDenseFeature(entry.getKey()))
			{
				final int index = denseFeatureIndex(entry.getKey());
				if (index > maxDenseFeaturesIndex)
					maxDenseFeaturesIndex = index;
			}

		final FeatureVector fv = maxDenseFeaturesIndex == -1 ? new FeatureVector() : new FeatureVector(maxDenseFeaturesIndex + 1);
		for (final Map.Entry<String, Double> entry : m.entrySet())
			if (isDenseFeature(entry.getKey()))
				fv.addDenseFeature(denseFeatureIndex(entry.getKey()), entry.getValue());
			else
				if (entry.getValue() == 1.0d)
					fv.add(entry.getKey());
				else
					fv.add(entry.getKey(), entry.getValue());
		return fv;
	}

	private static boolean isDenseFeature(final String f)
	{
		return f.startsWith(DENSE_NAME);
	}

	private static int denseFeatureIndex(final String denseFeature)
	{
		assert denseFeature.startsWith(DENSE_NAME);
		return Integer.parseInt(denseFeature.split("_")[1]);
	}

	public static void logChoices(final String prefix, final Map<String, Integer> choices)
	{
		LogInfo.begin_track("%s choices", prefix);
		for (final Map.Entry<String, Integer> e : choices.entrySet())
		{
			final int value = e.getValue();
			if (value == 0)
				continue;
			LogInfo.logs("%s %s", value > 0 ? "+" + value : value, e.getKey());
		}
		LogInfo.end_track();
	}

	public static void logFeatureWeights(final String prefix, final Map<String, Double> features, final Params params)
	{
		final List<Map.Entry<String, Double>> entries = new ArrayList<>();
		double sumValue = 0;
		for (final Map.Entry<String, Double> entry : features.entrySet())
		{
			final String feature = entry.getKey();
			if (entry.getValue() == 0)
				continue;
			final double value = entry.getValue() * params.getWeight(feature);
			if (opts.ignoreZeroWeight && value == 0)
				continue;
			sumValue += value;
			entries.add(new java.util.AbstractMap.SimpleEntry<>(feature, value));
		}
		Collections.sort(entries, new ValueComparator<String, Double>(false));
		LogInfo.begin_track_printAll("%s features [sum = %s] (format is feature value * weight)", prefix, Fmt.D(sumValue));
		if (entries.size() / 2 > opts.logFeaturesLimit)
		{
			for (final Map.Entry<String, Double> entry : entries.subList(0, opts.logFeaturesLimit))
			{
				final String feature = entry.getKey();
				final double value = entry.getValue();
				final double weight = params.getWeight(feature);
				LogInfo.logs("%-50s %6s = %s * %s", "[ " + feature + " ]", Fmt.D(value), Fmt.D(MapUtils.getDouble(features, feature, 0)), Fmt.D(weight));
			}
			LogInfo.logs("... (%d more features) ...", entries.size() - 2 * opts.logFeaturesLimit);
			for (final Map.Entry<String, Double> entry : entries.subList(entries.size() - opts.logFeaturesLimit, entries.size()))
			{
				final String feature = entry.getKey();
				final double value = entry.getValue();
				final double weight = params.getWeight(feature);
				LogInfo.logs("%-50s %6s = %s * %s", "[ " + feature + " ]", Fmt.D(value), Fmt.D(MapUtils.getDouble(features, feature, 0)), Fmt.D(weight));
			}
		}
		else
			for (final Map.Entry<String, Double> entry : entries)
			{
				final String feature = entry.getKey();
				final double value = entry.getValue();
				final double weight = params.getWeight(feature);
				LogInfo.logs("%-50s %6s = %s * %s", "[ " + feature + " ]", Fmt.D(value), Fmt.D(MapUtils.getDouble(features, feature, 0)), Fmt.D(weight));
			}
		LogInfo.end_track();
	}

	public static void logFeatures(final Map<String, Double> features)
	{
		for (final String key : features.keySet())
			LogInfo.logs("%s\t%s", key, features.get(key));
	}

	public void clear()
	{
		if (indicatorFeatures != null)
			indicatorFeatures.clear();
		if (generalFeatures != null)
			generalFeatures.clear();
		denseFeatures = null;
	}
}
