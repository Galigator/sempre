package edu.stanford.nlp.sempre.tables.lambdadcs;

import edu.stanford.nlp.sempre.AggregateFormula;
import edu.stanford.nlp.sempre.ListValue;
import edu.stanford.nlp.sempre.MergeFormula;
import edu.stanford.nlp.sempre.NumberValue;
import edu.stanford.nlp.sempre.Value;
import edu.stanford.nlp.sempre.tables.lambdadcs.LambdaDCSException.Type;
import fig.basic.LispTree;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * A unary with finite number of elements. Represented as a set of values.
 *
 * @author ppasupat
 */
public class ExplicitUnaryDenotation extends UnaryDenotation
{

	protected final List<Value> values;

	public ExplicitUnaryDenotation()
	{
		values = Collections.emptyList();
	}

	public ExplicitUnaryDenotation(final Value value)
	{
		values = Collections.singletonList(value);
	}

	public ExplicitUnaryDenotation(final Collection<Value> values)
	{
		this.values = new ArrayList<>(values);
	}

	@Override
	public LispTree toLispTree()
	{
		final LispTree tree = LispTree.proto.newList();
		tree.addChild("unary");
		for (final Value value : values)
			tree.addChild(value.toLispTree());
		return tree;
	}

	protected ListValue cachedValue;

	@Override
	public ListValue toValue()
	{
		if (cachedValue != null)
			return cachedValue;
		ListValue result = new ListValue(values);
		if (LambdaDCSExecutor.opts.sortResults)
			result = result.getSorted();
		cachedValue = result;
		return result;
	}

	@Override
	public String toString()
	{
		return toLispTree().toString();
	}

	@Override
	public boolean contains(final Object o)
	{
		return values.contains(o);
	}

	@Override
	public boolean containsAll(final Collection<?> c)
	{
		return values.containsAll(c);
	}

	@Override
	public Iterator<Value> iterator()
	{
		return values.iterator();
	}

	@Override
	public Object[] toArray()
	{
		return values.toArray();
	}

	@Override
	public <T> T[] toArray(final T[] a)
	{
		return values.toArray(a);
	}

	@Override
	public int size()
	{
		return values.size();
	}

	@Override
	public UnaryDenotation merge(final UnaryDenotation that, final MergeFormula.Mode mode)
	{
		if (that.size() == Integer.MAX_VALUE)
			return that.merge(this, mode);
		final Set<Value> merged = new HashSet<>(values);
		switch (mode)
		{
			case and:
				merged.retainAll(that);
				break;
			case or:
				merged.addAll(that);
				break;
			default:
				throw new LambdaDCSException(Type.invalidFormula, "Unknown merge mode: %s", mode);
		}
		return new ExplicitUnaryDenotation(merged);
	}

	@Override
	public UnaryDenotation aggregate(final AggregateFormula.Mode mode)
	{
		if (mode == AggregateFormula.Mode.count)
			// Count the set size, not the list size
			return new ExplicitUnaryDenotation(new NumberValue(new HashSet<>(values).size()));
		return new ExplicitUnaryDenotation(DenotationUtils.aggregate(this, mode));
	}

	@Override
	public UnaryDenotation filter(final UnaryDenotation upperBound)
	{
		final List<Value> filtered = new ArrayList<>();
		for (final Value value : values)
			if (upperBound.contains(value))
				filtered.add(value);
		return new ExplicitUnaryDenotation(filtered);
	}

}
