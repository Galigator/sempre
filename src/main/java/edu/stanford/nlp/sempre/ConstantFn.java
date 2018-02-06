package edu.stanford.nlp.sempre;

import fig.basic.LispTree;

/**
 * Just returns a fixed logical formula.
 *
 * @author Percy Liang
 */
public class ConstantFn extends SemanticFn
{
	Formula formula; // Formula to return
	SemType type;

	public ConstantFn()
	{
	}

	public ConstantFn(final Formula formula_)
	{
		init(LispTree.proto.newList("ConstantFn", formula_.toLispTree()));
	}

	@Override
	public void init(final LispTree tree)
	{
		super.init(tree);
		formula = Formulas.fromLispTree(tree.child(1));
		if (2 < tree.children.size())
			type = SemType.fromLispTree(tree.child(2));
		else
			type = TypeInference.inferType(formula);
		if (!type.isValid())
			throw new RuntimeException("ConstantFn: " + formula + " does not type check");
	}

	@Override
	public DerivationStream call(final Example ex, final Callable c)
	{
		return new SingleDerivationStream()
		{
			@Override
			public Derivation createDerivation()
			{
				final Derivation res = new Derivation.Builder().withCallable(c).formula(formula).type(type).createDerivation();
				// don't generate feature if it is not grounded to a string
				if (FeatureExtractor.containsDomain("constant") && c.getStart() != -1)
					res.addFeature("constant", ex.phraseString(c.getStart(), c.getEnd()) + " --- " + formula.toString());
				return res;
			}
		};
	}
}
