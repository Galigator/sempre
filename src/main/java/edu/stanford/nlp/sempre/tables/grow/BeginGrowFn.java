package edu.stanford.nlp.sempre.tables.grow;

import edu.stanford.nlp.sempre.Derivation;
import edu.stanford.nlp.sempre.DerivationStream;
import edu.stanford.nlp.sempre.Example;
import edu.stanford.nlp.sempre.Formula;
import edu.stanford.nlp.sempre.LambdaFormula;
import edu.stanford.nlp.sempre.SemanticFn;
import edu.stanford.nlp.sempre.SingleDerivationStream;
import edu.stanford.nlp.sempre.TypeInference;
import edu.stanford.nlp.sempre.VariableFormula;
import edu.stanford.nlp.sempre.tables.ScopedFormula;
import fig.basic.LispTree;
import fig.basic.Option;

/**
 * Formula s [finite set] ==> ScopedFormula(s, identity function)
 *
 * @author ppasupat
 */
public class BeginGrowFn extends SemanticFn
{
	public static class Options
	{
		@Option(gloss = "verbosity")
		public int verbose = 0;
	}

	public static Options opts = new Options();

	public void init(final LispTree tree)
	{
		super.init(tree);
	}

	public static final Formula IDENTITY = new LambdaFormula("x", new VariableFormula("x"));

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
				final ScopedFormula scoped = new ScopedFormula(c.child(0).formula, IDENTITY);
				return new Derivation.Builder().withCallable(c).formula(scoped).type(TypeInference.inferType(scoped.relation)).createDerivation();
			}
		};
	}
}
