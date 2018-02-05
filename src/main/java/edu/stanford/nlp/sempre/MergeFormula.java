package edu.stanford.nlp.sempre;

import com.google.common.base.Function;
import fig.basic.LispTree;
import java.util.List;

/**
 * Takes two unary formulas and performs either the intersection or union.
 *
 * @author Percy Liang
 */
public class MergeFormula extends Formula
{
	public enum Mode
	{
		and, or
	}

	public final Mode mode;
	public final Formula child1;
	public final Formula child2;

	public MergeFormula(final Mode mode_, final Formula child1_, final Formula child2_)
	{
		mode = mode_;
		child1 = child1_;
		child2 = child2_;
	}

	@Override
	public LispTree toLispTree()
	{
		final LispTree tree = LispTree.proto.newList();
		tree.addChild(mode.toString());
		tree.addChild(child1.toLispTree());
		tree.addChild(child2.toLispTree());
		return tree;
	}

	@Override
	public void forEach(final Function<Formula, Boolean> func)
	{
		if (!func.apply(this))
		{
			child1.forEach(func);
			child2.forEach(func);
		}
	}

	@Override
	public Formula map(final Function<Formula, Formula> func)
	{
		final Formula result = func.apply(this);
		return result == null ? new MergeFormula(mode, child1.map(func), child2.map(func)) : result;
	}

	@Override
	public List<Formula> mapToList(final Function<Formula, List<Formula>> func, final boolean alwaysRecurse)
	{
		final List<Formula> res = func.apply(this);
		if (res.isEmpty() || alwaysRecurse)
		{
			res.addAll(child1.mapToList(func, alwaysRecurse));
			res.addAll(child2.mapToList(func, alwaysRecurse));
		}
		return res;
	}

	public static Mode parseMode(final String mode)
	{
		if ("and".equals(mode))
			return Mode.and;
		if ("or".equals(mode))
			return Mode.or;
		return null;
	}

	@Override
	public boolean equals(final Object thatObj)
	{
		if (!(thatObj instanceof MergeFormula))
			return false;
		final MergeFormula that = (MergeFormula) thatObj;
		if (mode != that.mode)
			return false;
		if (!child1.equals(that.child1))
			return false;
		if (!child2.equals(that.child2))
			return false;
		return true;
	}

	@Override
	public int computeHashCode()
	{
		int hash = 0x7ed55d16;
		hash = hash * 0xd3a2646c + mode.toString().hashCode(); // Note: don't call hashCode() on mode directly.
		hash = hash * 0xd3a2646c + child1.hashCode();
		hash = hash * 0xd3a2646c + child2.hashCode();
		return hash;
	}
}
