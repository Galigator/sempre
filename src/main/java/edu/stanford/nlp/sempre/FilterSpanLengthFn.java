package edu.stanford.nlp.sempre;

import fig.basic.LispTree;

public class FilterSpanLengthFn extends SemanticFn
{

	private int minLength;
	private int maxLength;

	private static final int NO_MAXIMUM = -1;

	public FilterSpanLengthFn()
	{
	}

	public FilterSpanLengthFn(final int minLength_)
	{
		init(LispTree.proto.newList("FilterSpanLengthFn", "" + minLength_));
	}

	@Override
	public void init(final LispTree tree)
	{
		super.init(tree);
		minLength = Integer.parseInt(tree.child(1).value);
		if (tree.children.size() > 2)
			maxLength = Integer.parseInt(tree.child(2).value);
		else
			maxLength = NO_MAXIMUM;
	}

	@Override
	public DerivationStream call(final Example ex, final Callable c)
	{
		return new SingleDerivationStream()
		{
			@Override
			public Derivation createDerivation()
			{
				if (c.getEnd() - c.getStart() < minLength)
					return null;
				if (maxLength != NO_MAXIMUM && c.getEnd() - c.getStart() > maxLength)
					return null;
				return new Derivation.Builder().withCallable(c).withFormulaFrom(c.child(0)).createDerivation();
			}
		};
	}
}
