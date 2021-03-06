package edu.stanford.nlp.sempre;

import com.google.common.base.Function;
import fig.basic.LispTree;
import java.util.List;

/**
 * Lambda abstraction (lambda |var| |body|) Percy Liang
 */
public class LambdaFormula extends Formula
{
	public final String var;
	public final Formula body;

	public LambdaFormula(final String var_, final Formula body_)
	{
		var = var_;
		body = body_;
	}

	@Override
	public LispTree toLispTree()
	{
		final LispTree tree = LispTree.proto.newList();
		tree.addChild("lambda");
		tree.addChild(var);
		tree.addChild(body.toLispTree());
		return tree;
	}

	@Override
	public void forEach(final Function<Formula, Boolean> func)
	{
		if (!func.apply(this))
			body.forEach(func);
	}

	@Override
	public Formula map(final Function<Formula, Formula> func)
	{
		final Formula result = func.apply(this);
		return result == null ? new LambdaFormula(var, body.map(func)) : result;
	}

	@Override
	public List<Formula> mapToList(final Function<Formula, List<Formula>> func, final boolean alwaysRecurse)
	{
		final List<Formula> res = func.apply(this);
		if (res.isEmpty() || alwaysRecurse)
			res.addAll(body.mapToList(func, alwaysRecurse));
		return res;
	}

	@Override
	public boolean equals(final Object thatObj)
	{
		if (!(thatObj instanceof LambdaFormula))
			return false;
		final LambdaFormula that = (LambdaFormula) thatObj;
		return var.equals(that.var) && body.equals(that.body);
	}

	@Override
	public int computeHashCode()
	{
		int hash = 0x7ed55d16;
		hash = hash * 0xd3a2646c + var.hashCode();
		hash = hash * 0xd3a2646c + body.hashCode();
		return hash;
	}
}
