package edu.stanford.nlp.sempre;

import fig.basic.MapUtils;
import fig.basic.NumUtils;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Utils for <code>ReinforcementParser</code>
 */
public final class ReinforcementUtils
{
	private static double logMaxValue = Math.log(Double.MAX_VALUE);

	private ReinforcementUtils()
	{
	}

	// add to double map after adding prefix to all keys
	public static void addToDoubleMap(final Map<String, Double> mutatedMap, final Map<String, Double> addedMap, final String prefix)
	{
		for (final String key : addedMap.keySet())
			MapUtils.incr(mutatedMap, prefix + key, addedMap.get(key));
	}

	public static void subtractFromDoubleMap(final Map<String, Double> mutatedMap, final Map<String, Double> subtractedMap)
	{
		for (final String key : subtractedMap.keySet())
			MapUtils.incr(mutatedMap, key, -1 * subtractedMap.get(key));
	}

	// subtract from double map after adding prefix to all keys
	public static void subtractFromDoubleMap(final Map<String, Double> mutatedMap, final Map<String, Double> subtractedMap, final String prefix)
	{
		for (final String key : subtractedMap.keySet())
			MapUtils.incr(mutatedMap, prefix + key, -1 * subtractedMap.get(key));
	}

	public static Map<String, Double> multiplyDoubleMap(final Map<String, Double> map, final double factor)
	{
		final Map<String, Double> res = new HashMap<>();
		for (final Map.Entry<String, Double> entry : map.entrySet())
			res.put(entry.getKey(), entry.getValue() * factor);
		return res;
	}

	public static int sampleIndex(final Random rand, final List<? extends HasScore> scorables, final double denominator)
	{
		final double randD = rand.nextDouble();
		double sum = 0;

		for (int i = 0; i < scorables.size(); ++i)
		{
			final HasScore pds = scorables.get(i);
			final double prob = computeProb(pds, denominator);
			sum += prob;
			if (randD < sum)
				return i;
		}
		throw new RuntimeException(sum + " < " + randD);
	}

	public static int sampleIndex(final Random rand, final double[] scores, final double denominator)
	{
		final double randD = rand.nextDouble();
		double sum = 0;

		for (int i = 0; i < scores.length; ++i)
		{
			final double pds = scores[i];
			final double prob = computeProb(pds, denominator);
			sum += prob;
			if (randD < sum)
				return i;
		}
		throw new RuntimeException(sum + " < " + randD);
	}

	public static int sampleIndex(final Random rand, final double[] probs)
	{
		final double randD = rand.nextDouble();
		double sum = 0;

		for (int i = 0; i < probs.length; ++i)
		{
			sum += probs[i];
			if (randD < sum)
				return i;
		}
		throw new RuntimeException(sum + " < " + randD);
	}

	public static double computeProb(final HasScore deriv, final double denominator)
	{
		final double prob = Math.exp(deriv.getScore() - denominator);
		if (prob < -0.0001 || prob > 1.0001)
			throw new RuntimeException("Probability is out of range, prob=" + prob + ",score=" + deriv.getScore() + ", denom=" + denominator);
		return prob;
	}

	public static double computeProb(final double score, final double denominator)
	{
		final double prob = Math.exp(score - denominator);
		if (prob < -0.0001 || prob > 1.0001)
			throw new RuntimeException("Probability is out of range, prob=" + prob + ",score=" + score + ", denom=" + denominator);
		return prob;
	}

	public static double computeLogExpSum(final List<? extends HasScore> scorables)
	{
		double sum = Double.NEGATIVE_INFINITY;
		for (final HasScore scorable : scorables)
			sum = NumUtils.logAdd(sum, scorable.getScore());
		return sum;
	}

	public static double[] expNormalize(final List<? extends HasScore> scorables)
	{
		// Input: log probabilities (unnormalized too)
		// Output: normalized probabilities
		// probs actually contains log probabilities; so we can add an arbitrary constant to make
		// the largest log prob 0 to prevent overflow problems
		final double[] res = new double[scorables.size()];
		double max = Double.NEGATIVE_INFINITY;
		for (int i = 0; i < scorables.size(); i++)
			max = Math.max(max, scorables.get(i).getScore());
		if (Double.isInfinite(max))
			throw new RuntimeException("Scoreables is probably empty");
		for (int i = 0; i < scorables.size(); i++)
			res[i] = Math.exp(scorables.get(i).getScore() - max);
		NumUtils.normalize(res);
		return res;
	}

	public static double[] expNormalize(final ParserAgenda<? extends HasScore> scorables)
	{
		// Input: log probabilities (unnormalized too)
		// Output: normalized probabilities
		// probs actually contains log probabilities; so we can add an arbitrary constant to make
		// the largest log prob 0 to prevent overflow problems
		final double[] res = new double[scorables.size()];
		double max = Double.NEGATIVE_INFINITY;

		for (final HasScore scorable : scorables)
			max = Math.max(max, scorable.getScore());

		if (Double.isInfinite(max))
			throw new RuntimeException("Scoreables is probably empty");

		int i = 0;
		for (final HasScore scorable : scorables)
			res[i++] = Math.exp(scorable.getScore() - max);
		NumUtils.normalize(res);
		return res;
	}

	// Return log(exp(a)-exp(b))
	public static double logSub(final double a, final double b)
	{
		if (a <= b)
			throw new RuntimeException("First argument must be strictly greater than second argument");
		if (Double.isInfinite(b) || a - b > logMaxValue || b - a < 30)
			return a;
		return a + Math.log(1d - Math.exp(b - a));
	}
}
