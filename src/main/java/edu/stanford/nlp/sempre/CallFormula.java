package edu.stanford.nlp.sempre;

import com.google.common.base.Function;
import com.google.common.collect.Lists;
import fig.basic.LispTree;
import java.util.List;

/**
 * A CallFormula represents a function call. See JavaExecutor for the semantics of this formula. (call func arg_1 ... arg_k)
 *
 * @author Percy Liang
 */
public class CallFormula extends Formula
{
	public final Formula func;
	public final List<Formula> args;

	public CallFormula(final String func, final List<Formula> args)
	{
		this(Formulas.newNameFormula(func), args);
	}

	public CallFormula(final Formula func, final List<Formula> args)
	{
		this.func = func;
		this.args = args;
	}

	public LispTree toLispTree()
	{
		final LispTree tree = LispTree.proto.newList();
		tree.addChild("call");
		tree.addChild(func.toLispTree());
		for (final Formula arg : args)
			tree.addChild(arg.toLispTree());
		return tree;
	}

	@Override
	public void forEach(final Function<Formula, Boolean> func)
	{
		if (!func.apply(this))
		{
			this.func.forEach(func);
			for (final Formula arg : args)
				arg.forEach(func);
		}
	}

	@Override
	public Formula map(final Function<Formula, Formula> transform)
	{
		final Formula result = transform.apply(this);
		if (result != null)
			return result;
		final Formula newFunc = func.map(transform);
		final List<Formula> newArgs = Lists.newArrayList();
		for (final Formula arg : args)
			newArgs.add(arg.map(transform));
		return new CallFormula(newFunc, newArgs);
	}

	@Override
	public List<Formula> mapToList(final Function<Formula, List<Formula>> transform, final boolean alwaysRecurse)
	{
		final List<Formula> res = transform.apply(this);
		if (res.isEmpty() || alwaysRecurse)
		{
			res.addAll(func.mapToList(transform, alwaysRecurse));
			for (final Formula arg : args)
				res.addAll(arg.mapToList(transform, alwaysRecurse));
		}
		return res;
	}

	@SuppressWarnings({ "equalshashcode" })
	@Override
	public boolean equals(final Object thatObj)
	{
		if (!(thatObj instanceof CallFormula))
			return false;
		final CallFormula that = (CallFormula) thatObj;
		if (!func.equals(that.func))
			return false;
		if (!args.equals(that.args))
			return false;
		return true;
	}

	public int computeHashCode()
	{
		int hash = 0x7ed55d16;
		hash = hash * 0xd3a2646c + func.hashCode();
		hash = hash * 0xd3a2646c + args.hashCode();
		return hash;
	}
}
