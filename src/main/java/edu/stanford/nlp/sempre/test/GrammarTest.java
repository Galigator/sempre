package edu.stanford.nlp.sempre.test;

import edu.stanford.nlp.sempre.Grammar;
import edu.stanford.nlp.sempre.Rule;
import java.util.List;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Test that the grammar correctly parsers rules.
 */
public class GrammarTest
{

	public static Grammar makeTernaryGrammar()
	{
		final Grammar g = new Grammar();
		g.addStatement("(rule $ROOT ($X) (IdentityFn))");
		g.addStatement("(rule $X ($A $B $C) (IdentityFn))");
		g.addStatement("(rule $A (a) (ConstantFn (string a)))");
		g.addStatement("(rule $B (b) (ConstantFn (string b)))");
		g.addStatement("(rule $C (c) (ConstantFn (string c)))");
		return g;
	}

	/**
	 * Checks that each rule is one of the following: $Cat => token $Cat => $Cat $Cat => token token $Cat => token $Cat $Cat => $Cat token $Cat => $Cat $Cat
	 */
	public boolean isValidBinaryGrammar(final Grammar g)
	{
		for (final Rule rule : g.getRules())
		{
			if (!Rule.isCat(rule.lhs))
				return false;
			if (rule.rhs.size() != 1 && rule.rhs.size() != 2)
				return false;
		}

		return true;
	}

	@Test
	public void testBinarizationOfTernaryGrammar()
	{
		final Grammar g = makeTernaryGrammar();
		final List<Rule> rules = g.getRules();
		Assert.assertEquals(6, rules.size());
		Assert.assertTrue(isValidBinaryGrammar(g));
	}

}
