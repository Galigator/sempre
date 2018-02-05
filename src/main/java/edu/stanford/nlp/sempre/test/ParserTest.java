package edu.stanford.nlp.sempre.test;

import static org.testng.AssertJUnit.assertEquals;

import edu.stanford.nlp.sempre.BeamParser;
import edu.stanford.nlp.sempre.Derivation;
import edu.stanford.nlp.sempre.ExactValueEvaluator;
import edu.stanford.nlp.sempre.Example;
import edu.stanford.nlp.sempre.Executor;
import edu.stanford.nlp.sempre.FeatureExtractor;
import edu.stanford.nlp.sempre.FloatingParser;
import edu.stanford.nlp.sempre.Grammar;
import edu.stanford.nlp.sempre.JavaExecutor;
import edu.stanford.nlp.sempre.Params;
import edu.stanford.nlp.sempre.Parser;
import edu.stanford.nlp.sempre.ParserState;
import edu.stanford.nlp.sempre.ReinforcementParser;
import edu.stanford.nlp.sempre.Value;
import edu.stanford.nlp.sempre.ValueEvaluator;
import fig.basic.LogInfo;
import java.util.HashMap;
import java.util.Map;
import org.testng.annotations.Test;

/**
 * Test parsers.
 *
 * @author Roy Frostig
 * @author Percy Liang
 */
public class ParserTest
{
	// Collects a grammar, and some input/output test pairs
	public abstract static class ParseTest
	{
		public Grammar grammar;

		ParseTest(final Grammar g)
		{
			grammar = g;
		}

		public Parser.Spec getParserSpec()
		{
			final Executor executor = new JavaExecutor();
			final FeatureExtractor extractor = new FeatureExtractor(executor);
			FeatureExtractor.opts.featureDomains.add("rule");
			final ValueEvaluator valueEvaluator = new ExactValueEvaluator();
			return new Parser.Spec(grammar, extractor, executor, valueEvaluator);
		}

		public abstract void test(Parser parser);
	}

	private static void checkNumDerivations(final Parser parser, final Params params, final String utterance, final String targetValue, final int numExpected)
	{
		Parser.opts.verbose = 5;
		final Example ex = TestUtils.makeSimpleExample(utterance, targetValue != null ? Value.fromString(targetValue) : null);
		final ParserState state = parser.parse(params, ex, targetValue != null);

		// Debug information
		for (final Derivation deriv : state.predDerivations)
		{
			LogInfo.dbg(deriv.getAllFeatureVector());
			LogInfo.dbg(params.getWeights());
			LogInfo.dbgs("Score %f", deriv.computeScore(params));
		}
		// parser.extractor.extractLocal();
		assertEquals(numExpected, ex.getPredDerivations().size());
		if (numExpected > 0 && targetValue != null)
			assertEquals(targetValue, ex.getPredDerivations().get(0).value.toString());
	}

	private static void checkNumDerivations(final Parser parser, final String utterance, final String targetValue, final int numExpected)
	{
		checkNumDerivations(parser, new Params(), utterance, targetValue, numExpected);
	}

	static ParseTest ABCTest()
	{
		return new ParseTest(TestUtils.makeAbcGrammar())
		{
			@Override
			public void test(final Parser parser)
			{
				checkNumDerivations(parser, "a +", null, 0);
				checkNumDerivations(parser, "a", "(string a)", 1);
				checkNumDerivations(parser, "a b", "(string a,b)", 1);
				checkNumDerivations(parser, "a b c", "(string a,b,c)", 2);
				checkNumDerivations(parser, "a b c a b c", "(string a,b,c,a,b,c)", 42);
			}
		};
	}

	static ParseTest ArithmeticTest()
	{
		return new ParseTest(TestUtils.makeArithmeticGrammar())
		{
			@Override
			public void test(final Parser parser)
			{
				checkNumDerivations(parser, "1 + ", null, 0);
				checkNumDerivations(parser, "1 plus 2", "(number 3)", 1);
				checkNumDerivations(parser, "2 times 3", "(number 6)", 1);
				checkNumDerivations(parser, "1 plus times 3", null, 0);
				checkNumDerivations(parser, "times", null, 0);
			}
		};
	};

	// Create parsers
	@Test
	public void checkBeamNumDerivationsForABCGrammar()
	{
		Parser.opts.coarsePrune = false;
		ParseTest p;
		p = ABCTest();
		p.test(new BeamParser(p.getParserSpec()));
		p = ArithmeticTest();
		p.test(new BeamParser(p.getParserSpec()));
	}

	@Test
	public void checkCoarseBeamNumDerivations()
	{
		Parser.opts.coarsePrune = true;
		ParseTest p;
		p = ABCTest();
		p.test(new BeamParser(p.getParserSpec()));
		p = ArithmeticTest();
		p.test(new BeamParser(p.getParserSpec()));
	}

	@Test(groups = "reinforcement")
	public void checkReinforcementNumDerivations()
	{
		ParseTest p;
		p = ABCTest();
		p.test(new ReinforcementParser(p.getParserSpec()));
		p = ArithmeticTest();
		p.test(new ReinforcementParser(p.getParserSpec()));
		// TODO(chaganty): test more thoroughly
	}

	@Test(groups = "floating")
	public void checkFloatingNumDerivations()
	{
		FloatingParser.opts.defaultIsFloating = true;
		FloatingParser.opts.useSizeInsteadOfDepth = true;
		final Parser parser = new FloatingParser(ABCTest().getParserSpec());
		FloatingParser.opts.maxDepth = 2;
		checkNumDerivations(parser, "ignore", null, 3);
		FloatingParser.opts.maxDepth = 4;
		checkNumDerivations(parser, "ignore", null, 3 + 3 * 3);
	}

	// TODO(chaganty): verify that things are ranked appropriately
	public void checkRankingArithmetic(final Parser parser)
	{
		Params params = new Params();
		final Map<String, Double> features = new HashMap<>();
		features.put("rule :: $Operator -> and (ConstantFn (lambda y (lambda x (call + (var x) (var y)))))", 1.0);
		features.put("rule :: $Operator -> and (ConstantFn (lambda y (lambda x (call * (var x) (var y)))))", -1.0);
		params.update(features);
		checkNumDerivations(parser, params, "2 and 3", "(number 5)", 2);

		params = new Params();
		features.put("rule :: $Operator -> and (ConstantFn (lambda y (lambda x (call + (var x) (var y)))))", -1.0);
		features.put("rule :: $Operator -> and (ConstantFn (lambda y (lambda x (call * (var x) (var y)))))", 1.0);
		params.update(features);
		checkNumDerivations(parser, params, "2 and 3", "(number 6)", 2);
	}

	@Test
	void checkRankingSimple()
	{
		checkRankingArithmetic(new BeamParser(ArithmeticTest().getParserSpec()));
	}

	@Test(groups = "reinforcement")
	void checkRankingReinforcement()
	{
		checkRankingArithmetic(new ReinforcementParser(ArithmeticTest().getParserSpec()));
	}

	@Test(groups = "floating")
	public void checkRankingFloating()
	{
		FloatingParser.opts.defaultIsFloating = true;
		FloatingParser.opts.maxDepth = 4;
		FloatingParser.opts.useAnchorsOnce = true;
		final Parser parser = new FloatingParser(new ParseTest(TestUtils.makeArithmeticFloatingGrammar())
		{
			@Override
			public void test(final Parser parser)
			{
			}
		}.getParserSpec());
		Params params = new Params();
		final Map<String, Double> features = new HashMap<>();
		features.put("rule :: $Operator -> nothing (ConstantFn (lambda y (lambda x (call + (var x) (var y)))))", 1.0);
		features.put("rule :: $Operator -> nothing (ConstantFn (lambda y (lambda x (call * (var x) (var y)))))", -1.0);
		params.update(features);
		/*
		 * Expected LFs:
		 *    2          3
		 *    2 + 3      3 + 2
		 *    2 * 3      3 * 2
		 */
		checkNumDerivations(parser, params, "2 and 3", "(number 5)", 6);

		params = new Params();
		features.put("rule :: $Operator -> nothing (ConstantFn (lambda y (lambda x (call + (var x) (var y)))))", -1.0);
		features.put("rule :: $Operator -> nothing (ConstantFn (lambda y (lambda x (call * (var x) (var y)))))", 1.0);
		params.update(features);
		checkNumDerivations(parser, params, "2 and 3", "(number 6)", 6);
	}

	// TODO(chaganty): verify the parser gradients

}
