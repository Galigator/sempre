package edu.stanford.nlp.sempre.test;

import edu.stanford.nlp.sempre.Builder;
import edu.stanford.nlp.sempre.Dataset;
import edu.stanford.nlp.sempre.Example;
import edu.stanford.nlp.sempre.FormulaMatchExecutor;
import edu.stanford.nlp.sempre.Grammar;
import edu.stanford.nlp.sempre.Learner;
import edu.stanford.nlp.sempre.Params;
import edu.stanford.nlp.sempre.Parser;
import edu.stanford.nlp.sempre.Value;

/**
 * Useful utilities and dummy system components for writing tests.
 *
 * @author Roy Frostig
 */
public final class TestUtils
{
	private TestUtils()
	{
	}

	public static Grammar makeAbcGrammar()
	{
		final Grammar g = new Grammar();
		g.addStatement("(rule $X (a) (ConstantFn (string a)))");
		g.addStatement("(rule $X (b) (ConstantFn (string b)))");
		g.addStatement("(rule $X (c) (ConstantFn (string c)))");
		g.addStatement("(rule $X ($X $X) (ConcatFn ,))");
		g.addStatement("(rule $ROOT ($X) (IdentityFn))");
		return g;
	}

	public static Grammar makeArithmeticGrammar()
	{
		final Grammar g = new Grammar();
		g.addStatement("(rule $Expr ($TOKEN) (NumberFn))");
		g.addStatement("(rule $Expr ($Expr $Partial) (JoinFn backward))");
		g.addStatement("(rule $Partial ($Operator $Expr) (JoinFn forward))");
		g.addStatement("(rule $Operator (plus) (ConstantFn (lambda y (lambda x (call + (var x) (var y))))))");
		g.addStatement("(rule $Operator (times) (ConstantFn (lambda y (lambda x (call * (var x) (var y))))))");
		g.addStatement("(rule $Operator (and) (ConstantFn (lambda y (lambda x (call + (var x) (var y))))))");
		g.addStatement("(rule $Operator (and) (ConstantFn (lambda y (lambda x (call * (var x) (var y))))))");
		g.addStatement("(rule $ROOT ($Expr) (IdentityFn))");
		return g;
	}

	public static Grammar makeArithmeticFloatingGrammar()
	{
		final Grammar g = new Grammar();
		g.addStatement("(rule $Expr ($TOKEN) (NumberFn) (anchored 1))");
		g.addStatement("(rule $Expr ($Expr $Partial) (JoinFn backward))");
		g.addStatement("(rule $Partial ($Operator $Expr) (JoinFn forward))");
		g.addStatement("(rule $Operator (nothing) (ConstantFn (lambda y (lambda x (call + (var x) (var y))))))");
		g.addStatement("(rule $Operator (nothing) (ConstantFn (lambda y (lambda x (call * (var x) (var y))))))");
		g.addStatement("(rule $ROOT ($Expr) (IdentityFn))");
		return g;
	}

	public static Grammar makeNumberConcatGrammar()
	{
		final Grammar g = new Grammar();
		g.addStatement("(rule $Number ($TOKEN) (NumberFn))");
		g.addStatement("(rule $Number ($Number $Number) (ConcatFn ,))");
		g.addStatement("(rule $ROOT ($Number) (IdentityFn))");
		return g;
	}

	public static Builder makeSimpleBuilder()
	{
		final Builder builder = new Builder();
		builder.grammar = makeNumberConcatGrammar();
		builder.executor = new FormulaMatchExecutor();
		builder.buildUnspecified();
		return builder;
	}

	public static Dataset makeSimpleDataset()
	{
		return new Dataset();
	}

	public static Learner makeSimpleLearner(final Parser parser, final Params params, final Dataset dataset)
	{
		return new Learner(parser, params, dataset);
	}

	public static Learner makeSimpleLearner(final Builder builder, final Dataset dataset)
	{
		return makeSimpleLearner(builder.parser, builder.params, dataset);
	}

	public static Learner makeSimpleLearner()
	{
		return makeSimpleLearner(makeSimpleBuilder(), makeSimpleDataset());
	}

	public static Example makeSimpleExample(final String utterance)
	{
		return makeSimpleExample(utterance, null);
	}

	public static Example makeSimpleExample(final String utterance, final Value targetValue)
	{
		final Builder builder = new Builder();
		builder.build();
		final Example ex = new Example.Builder().setId("_id").setUtterance(utterance).setTargetValue(targetValue).createExample();
		ex.preprocess();
		return ex;
	}
}
