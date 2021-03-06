package edu.stanford.nlp.sempre.tables.grow;

import edu.stanford.nlp.sempre.Derivation;
import edu.stanford.nlp.sempre.DerivationStream;
import edu.stanford.nlp.sempre.Example;
import edu.stanford.nlp.sempre.Formula;
import edu.stanford.nlp.sempre.Formulas;
import edu.stanford.nlp.sempre.LambdaFormula;
import edu.stanford.nlp.sempre.SemanticFn;
import edu.stanford.nlp.sempre.SingleDerivationStream;
import edu.stanford.nlp.sempre.TypeInference;
import edu.stanford.nlp.sempre.tables.ScopedFormula;
import fig.basic.LispTree;
import fig.basic.Option;

/**
 * Mapping(s, r) ==> f(s, r)
 *
 * @author ppasupat
 */
public class EndGrowFn extends SemanticFn
{
	public static class Options
	{
		@Option(gloss = "verbosity")
		public int verbose = 0;
	}

	public static Options opts = new Options();

	Formula formula;

	public void init(final LispTree tree)
	{
		super.init(tree);
		formula = Formulas.fromLispTree(tree.child(1));
		if (!(formula instanceof LambdaFormula) || !(((LambdaFormula) formula).body instanceof LambdaFormula))
			throw new RuntimeException("Function for EndGrowFn must take 2 arguments (a set s and a relation r)");
	}

	public Formula getFormula()
	{
		return formula;
	}

	@Override
	public DerivationStream call(final Example ex, final Callable c)
	{
		return new SingleDerivationStream()
		{
			@Override
			public Derivation createDerivation()
			{
				if (c.getChildren().size() != 1)
					throw new RuntimeException("Wrong number of argument: expected 1; got " + c.getChildren().size());
				if (!(c.child(0).formula instanceof ScopedFormula))
					throw new RuntimeException("Wrong argument type: expected ScopedFormula; got " + c.child(0).formula);
				final ScopedFormula scoped = (ScopedFormula) c.child(0).formula;
				Formula result = formula;
				result = Formulas.lambdaApply((LambdaFormula) result, scoped.head);
				result = Formulas.lambdaApply((LambdaFormula) result, scoped.relation);
				return new Derivation.Builder().withCallable(c).formula(result).type(TypeInference.inferType(result)).createDerivation();
			}
		};
	}
}
