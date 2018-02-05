package edu.stanford.nlp.sempre;

import fig.basic.LispTree;

/**
 * Takes two strings and returns their concatenation.
 *
 * @author Percy Liang
 */
public class ConcatFn extends SemanticFn
{
	String delim;

	public ConcatFn()
	{
	}

	public ConcatFn(final String delim)
	{
		this.delim = delim;
	}

	public void init(final LispTree tree)
	{
		super.init(tree);
		delim = tree.child(1).value;
	}

	public DerivationStream call(final Example ex, final Callable c)
	{
		return new SingleDerivationStream()
		{
			@Override
			public Derivation createDerivation()
			{
				final StringBuilder out = new StringBuilder();
				for (int i = 0; i < c.getChildren().size(); i++)
				{
					if (i > 0)
						out.append(delim);
					out.append(c.childStringValue(i));
				}
				return new Derivation.Builder().withCallable(c).withStringFormulaFrom(out.toString()).createDerivation();
			}
		};
	}
}
