package edu.stanford.nlp.sempre;

import com.google.common.base.Function;
import fig.basic.LispTree;
import java.util.List;

/**
 * 'Aggregate' takes a set and computes some number on that set.
 *
 * @author Percy Liang
 */
public class AggregateFormula extends Formula
{
	public enum Mode
	{
		count, sum, avg, min, max
	};

	public final Mode mode;
	public final Formula child;

	public AggregateFormula(final Mode mode, final Formula child)
	{
		this.mode = mode;
		this.child = child;
	}

	public LispTree toLispTree()
	{
		final LispTree tree = LispTree.proto.newList();
		tree.addChild(mode.toString());
		tree.addChild(child.toLispTree());
		return tree;
	}

	public static Mode parseMode(final String mode)
	{
		if ("count".equals(mode))
			return Mode.count;
		if ("sum".equals(mode))
			return Mode.sum;
		if ("avg".equals(mode))
			return Mode.avg;
		if ("min".equals(mode))
			return Mode.min;
		if ("max".equals(mode))
			return Mode.max;
		return null;
	}

	@Override
	public void forEach(final Function<Formula, Boolean> func)
	{
		if (!func.apply(this))
			child.forEach(func);
	}

	@Override
	public Formula map(final Function<Formula, Formula> func)
	{
		final Formula result = func.apply(this);
		return result == null ? new AggregateFormula(mode, child.map(func)) : result;
	}

	@Override
	public List<Formula> mapToList(final Function<Formula, List<Formula>> func, final boolean alwaysRecurse)
	{
		final List<Formula> res = func.apply(this);
		if (res.isEmpty() || alwaysRecurse)
			res.addAll(child.mapToList(func, alwaysRecurse));
		return res;
	}

	@SuppressWarnings({ "equalshashcode" })
	@Override
	public boolean equals(final Object thatObj)
	{
		if (!(thatObj instanceof AggregateFormula))
			return false;
		final AggregateFormula that = (AggregateFormula) thatObj;
		if (!mode.equals(that.mode))
			return false;
		if (!child.equals(that.child))
			return false;
		return true;
	}

	public int computeHashCode()
	{
		int hash = 0x7ed55d16;
		hash = hash * 0xd3a2646c + mode.toString().hashCode();
		hash = hash * 0xd3a2646c + child.hashCode();
		return hash;
	}
}
