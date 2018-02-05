package edu.stanford.nlp.sempre.interactive;

import edu.stanford.nlp.sempre.ActionFormula;
import edu.stanford.nlp.sempre.Derivation;
import edu.stanford.nlp.sempre.DerivationStream;
import edu.stanford.nlp.sempre.Example;
import edu.stanford.nlp.sempre.FeatureExtractor;
import edu.stanford.nlp.sempre.FeatureVector;
import edu.stanford.nlp.sempre.SemanticFn;
import edu.stanford.nlp.sempre.SingleDerivationStream;
import fig.basic.LispTree;
import fig.basic.Option;
import java.util.List;
import org.testng.collections.Lists;

/**
 * Generates formula scoped in various modes sequential: just perform in sequence, no scoping block: basic scoping block blockr: returns selected isolate:
 * scopes allItems instead of selected
 * 
 * @author sidaw
 */
public class BlockFn extends SemanticFn
{
	public static class Options
	{
		@Option(gloss = "verbosity")
		public int verbose = 0;
	}

	public static Options opts = new Options();

	List<ActionFormula.Mode> scopingModes = Lists.newArrayList(ActionFormula.Mode.block, ActionFormula.Mode.blockr, ActionFormula.Mode.isolate);
	ActionFormula.Mode mode = ActionFormula.Mode.block;
	boolean optional = true;

	@Override
	public void init(final LispTree tree)
	{
		super.init(tree);
		if (tree.child(1).value.equals("sequential"))
			mode = ActionFormula.Mode.sequential;
		else
			if (tree.child(1).value.equals("block"))
				mode = ActionFormula.Mode.block;
			else
				if (tree.child(1).value.equals("blockr"))
					mode = ActionFormula.Mode.blockr;
				else
					if (tree.child(1).value.equals("isolate"))
						mode = ActionFormula.Mode.isolate;
					else
						mode = ActionFormula.Mode.sequential;
	}

	public BlockFn(final ActionFormula.Mode mode)
	{
		this.mode = mode;
	}

	public BlockFn()
	{
		mode = ActionFormula.Mode.sequential;
	}

	@Override
	public DerivationStream call(final Example ex, final Callable c)
	{
		return new SingleDerivationStream()
		{
			@Override
			public Derivation createDerivation()
			{
				final List<Derivation> args = c.getChildren();
				if (args.size() == 1)
				{
					final Derivation onlyChild = args.get(0);
					// LogInfo.logs("1 BlockFn %s : %s Example.size=%d, callInfo(%d,%d)",
					// onlyChild, mode, ex.getTokens().size(), onlyChild.getStart(),
					// onlyChild.getEnd());

					if (onlyChild == null)
						return null;
					if (onlyChild.getStart() != 0 || onlyChild.getEnd() != ex.getTokens().size())
						return null;
					// do not do anything to the core language
					if (onlyChild.allAnchored())
						return null;
					// if (!ILUtils.stripBlock(onlyChild).rule.isInduced()) return null;

					// if already blocked explicitly, do not do anything
					if (scopingModes.contains(((ActionFormula) onlyChild.formula).mode))
						return null;

					// do not repeat any blocks
					if (((ActionFormula) onlyChild.formula).mode == mode)
						return null;

					final FeatureVector features = new FeatureVector();
					if (FeatureExtractor.containsDomain(":scope"))
					{
						features.add(":scope", mode.toString() + "::" + !InteractiveUtils.stripBlock(onlyChild).rule.isInduced());
						features.add(":scope", mode.toString() + "::" + ex.id);
					}

					final Derivation deriv = new Derivation.Builder().formula(new ActionFormula(mode, Lists.newArrayList(onlyChild.formula))).withCallable(c).localFeatureVector(features).createDerivation();
					return deriv;
				}
				else
					return null;
			}
		};
	}
}
