package edu.stanford.nlp.sempre.freebase.utils;

import edu.stanford.nlp.stats.ClassicCounter;
import edu.stanford.nlp.stats.Counter;
import edu.stanford.nlp.stats.Counters;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class MathUtils
{
	private MathUtils()
	{
	}

	public static double jaccard(final double intersection, final double size1, final double size2, final double smoothing)
	{
		return intersection / (size1 + size2 + smoothing - intersection);
	}

	public static <E> double generalizedJensenShannonDivergence(final Counter<E> c1, final Counter<E> c2)
	{

		double sum = 0.0;
		final Set<E> nonZeroEntries = new HashSet<>();
		for (final E entry : nonZeroEntries)
		{
			final double u = c1.getCount(entry);
			final double v = c2.getCount(entry);
			sum += coordinateJsDivergence(u, v);
		}
		return sum / 2.0;
	}

	private static double coordinateJsDivergence(final double u, final double v)
	{
		return u * Math.log(2 * u / (u + v)) + v * Math.log(2 * v / (u + v));
	}

	public static double coordinateJsDiverDeriv(final double x, final double y)
	{
		if (x == 0.0)
			return 0.0;
		if (y == 0.0)
			return Math.log(2) / 2.0;

		final double xPlusY = x + y;
		final double res = -1 * Math.log(xPlusY) - y / xPlusY + x * (1 / x - 1 / xPlusY) + Math.log(x) + Math.log(2);
		return res / 2.0;
	}

	public static Counter<String> prefixCounterKeys(final Counter<String> counter, final String prefix)
	{
		final Counter<String> res = new ClassicCounter<>();
		for (final String key : counter.keySet())
			res.setCount(prefix + "_" + key, counter.getCount(key));
		return res;
	}

	public static double vectorCosine(final List<Double> array1, final List<Double> array2)
	{

		if (array1.size() != array2.size())
			throw new RuntimeException("Cannot compute cosine of arrays of differnt sizes: " + array1.size() + " " + array2.size());
		double dotProd = 0.0;
		double lsq1 = 0.0;
		double lsq2 = 0.0;

		for (int i = 0; i < array1.size(); ++i)
		{
			dotProd += array1.get(i) * array2.get(i);
			lsq1 += array1.get(i) * array1.get(i);
			lsq2 += array2.get(i) * array2.get(i);
		}
		return dotProd / (Math.sqrt(lsq1) * Math.sqrt(lsq2));
	}

	public static double euclidDistance(final List<Double> array1, final List<Double> array2)
	{

		if (array1.size() != array2.size())
			throw new RuntimeException("Cannot compute cosine of arrays of differnt sizes: " + array1.size() + " " + array2.size());

		double sqDistance = 0.0;
		for (int i = 0; i < array1.size(); ++i)
			sqDistance += Math.pow(array1.get(i) - array2.get(i), 2);
		return Math.sqrt(sqDistance);
	}

	public static <T> double sumDoubleMap(final Map<T, DoubleContainer> map)
	{
		double sum = 0.0;
		for (final DoubleContainer d : map.values())
			sum += d.value();
		return sum;
	}

	public static <T> void normalizeDoubleMap(final Map<T, DoubleContainer> map)
	{
		double sum = 0.0;
		for (final DoubleContainer d : map.values())
			sum += d.value();
		for (final T key : map.keySet())
		{
			final double normalizedValue = map.get(key).value() / sum;
			map.get(key).set(normalizedValue);
		}
	}

	/**
	 * Computes jaccard between sets of objects
	 * 
	 * @param x
	 * @param y
	 * @return
	 */
	public static <T> double jaccard(final Set<T> x, final Set<T> y)
	{

		final Set<T> intersection = new HashSet<>(x);
		intersection.retainAll(y);
		final Set<T> union = new HashSet<>(x);
		union.addAll(y);

		final double res = union.size() == 0 ? 1.0 : (double) intersection.size() / union.size();
		return res;
	}

	/**
	 * Computes jaccard between sets of objects
	 * 
	 * @param x
	 * @param y
	 * @return
	 */
	public static <T> double jaccard(final List<T> x, final List<T> y)
	{

		final Set<T> intersection = new HashSet<>(x);
		intersection.retainAll(y);
		final Set<T> union = new HashSet<>(x);
		union.addAll(y);

		final double res = union.size() == 0 ? 1.0 : (double) intersection.size() / union.size();
		return res;
	}

	/**
	 * how many of the tokens in x are covered by y
	 * 
	 * @param x
	 * @param y
	 * @return
	 */
	public static <T> double coverage(final List<T> x, final List<T> y)
	{
		final Set<T> yTokens = new HashSet<>(y);
		int covered = 0;
		for (final T xItem : x)
			if (yTokens.contains(xItem))
				covered++;
		return (double) covered / x.size();
	}

	/**
	 * Geometric average of unigram bigram and trigram precision
	 * 
	 * @param test
	 * @param ref
	 * @return
	 */

	public static double bleu(final List<String> test, final List<String> ref)
	{

		final Set<String> refUnigrams = new HashSet<>();
		final Set<String> refBigrams = new HashSet<>();
		final Set<String> refTrigrams = new HashSet<>();
		for (int i = 0; i < ref.size(); ++i)
		{
			refUnigrams.add(ref.get(i));
			if (i < ref.size() - 1)
				refBigrams.add(ref.get(i) + " " + ref.get(i + 1));
			if (i < ref.size() - 2)
				refTrigrams.add(ref.get(i) + " " + ref.get(i + 1) + " " + ref.get(i + 2));
		}
		int unigramCov = 0;
		int bigramCov = 0;
		int trigramCov = 0;
		for (int i = 0; i < test.size(); ++i)
		{
			if (refUnigrams.contains(test.get(i)))
				unigramCov++;
			if (i < test.size() - 1)
			{
				final String bigram = test.get(i) + " " + test.get(i + 1);
				if (refBigrams.contains(bigram))
					bigramCov++;
			}
			if (i < test.size() - 2)
			{
				final String trigram = test.get(i) + " " + test.get(i + 1) + " " + test.get(i + 2);
				if (refTrigrams.contains(trigram))
					trigramCov++;
			}
		}
		final double unigramPrec = (double) unigramCov / test.size();
		final double bigramPrec = (double) bigramCov / (test.size() - 1);
		final double trigramPrec = (double) trigramCov / (test.size() - 2);
		final double exponent = (double) 1 / 3;
		return Math.pow(unigramPrec * bigramPrec * trigramPrec, exponent);
	}

	public static double tokensCosine(final List<String> x, final List<String> y)
	{

		final Counter<String> xCounter = new ClassicCounter<>();
		for (final String str : x)
			xCounter.incrementCount(str);
		final Counter<String> yCounter = new ClassicCounter<>();
		for (final String str : y)
			yCounter.incrementCount(str);
		return Counters.cosine(xCounter, yCounter);
	}
}
