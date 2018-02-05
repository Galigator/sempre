package edu.stanford.nlp.sempre;

import com.google.common.collect.Sets;
import fig.basic.MapUtils;
import fig.basic.Option;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Extracts indicator features that count how many times semantic functions and LHSs have been used in the derivation For now we count how many times MergeFn
 * and JoinFn, also how many time unary, binary and entity lexical entries have been used. The feature is a pair with the operation and the count
 *
 * @author jonathanberant
 */
public class DerivOpCountFeatureComputer implements FeatureComputer
{
	public static class Options
	{
		@Option(gloss = "Count only basic categories and SemanticFns")
		public boolean countBasicOnly = true;
	}

	public static Options opts = new Options();

	public static final String entityCat = "$Entity";
	public static final String unaryCat = "$Unary";
	public static final String binaryCat = "$Binary";
	public static final String joinFn = "JoinFn";
	public static final String mergeFn = "MergeFn";
	public static final String bridgeFn = "BridgeFn";
	public static Set<String> featureNames = Sets.newHashSet(entityCat, unaryCat, binaryCat, joinFn, mergeFn, bridgeFn);

	@Override
	public void extractLocal(final Example ex, final Derivation deriv)
	{
		if (!FeatureExtractor.containsDomain("opCount"))
			return;
		if (!deriv.isRoot(ex.numTokens()))
			return;

		// extract the operation count
		final Map<String, Integer> opCounter = new HashMap<>();
		extractOperationsRecurse(deriv, opCounter);
		addFeatures(deriv, opCounter);
	}

	private void extractOperationsRecurse(final Derivation deriv, final Map<String, Integer> opCounter)
	{
		// Basic case: no rule
		if (deriv.children.isEmpty())
			return;
		// increment counts for current rule
		MapUtils.incr(opCounter, deriv.rule.lhs);
		MapUtils.incr(opCounter, deriv.rule.sem.getClass().getSimpleName());
		// recursive call
		for (final Derivation child : deriv.children)
			extractOperationsRecurse(child, opCounter);
	}

	private void addFeatures(final Derivation deriv, final Map<String, Integer> opCounter)
	{
		for (final String feature : opts.countBasicOnly ? featureNames : opCounter.keySet())
			deriv.addFeature("opCount", "count(" + feature + ")=" + MapUtils.get(opCounter, feature, 0));
	}
}
