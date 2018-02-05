package edu.stanford.nlp.sempre.tables.lambdadcs;

import edu.stanford.nlp.sempre.AggregateFormula.Mode;
import edu.stanford.nlp.sempre.PairListValue;
import edu.stanford.nlp.sempre.Value;
import fig.basic.LispTree;
import fig.basic.MapUtils;
import fig.basic.Pair;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ExplicitPairList implements PairList
{

	// Following LambdaDCS convention, pair (v, k) means k maps to v.
	protected final List<Pair<Value, Value>> pairs;
	protected final Map<Value, UnaryDenotation> mapping;
	protected final Map<Value, UnaryDenotation> reverseMapping;

	// ============================================================
	// Constructors
	// ============================================================

	public ExplicitPairList()
	{
		pairs = Collections.emptyList();
		mapping = Collections.emptyMap();
		reverseMapping = Collections.emptyMap();
	}

	public ExplicitPairList(final Value key, final Value value)
	{
		pairs = Collections.singletonList(new Pair<>(value, key));
		mapping = Collections.singletonMap(key, new ExplicitUnaryDenotation(value));
		reverseMapping = Collections.singletonMap(value, new ExplicitUnaryDenotation(key));
	}

	public ExplicitPairList(final Pair<Value, Value> pair)
	{
		pairs = Collections.singletonList(pair);
		mapping = Collections.singletonMap(pair.getSecond(), new ExplicitUnaryDenotation(pair.getFirst()));
		reverseMapping = Collections.singletonMap(pair.getFirst(), new ExplicitUnaryDenotation(pair.getSecond()));
	}

	public ExplicitPairList(final List<Pair<Value, Value>> pairs)
	{
		this.pairs = pairs;
		final Map<Value, List<Value>> mappingBuilder = new HashMap<>(), reverseMappingBuilder = new HashMap<>();
		for (final Pair<Value, Value> pair : pairs)
		{
			MapUtils.addToList(mappingBuilder, pair.getSecond(), pair.getFirst());
			MapUtils.addToList(reverseMappingBuilder, pair.getFirst(), pair.getSecond());
		}
		mapping = new HashMap<>();
		for (final Map.Entry<Value, List<Value>> entry : mappingBuilder.entrySet())
			mapping.put(entry.getKey(), new ExplicitUnaryDenotation(entry.getValue()));
		reverseMapping = new HashMap<>();
		for (final Map.Entry<Value, List<Value>> entry : reverseMappingBuilder.entrySet())
			reverseMapping.put(entry.getKey(), new ExplicitUnaryDenotation(entry.getValue()));
	}

	public <T extends Collection<Value>> ExplicitPairList(final Map<Value, T> keyToValues)
	{
		pairs = new ArrayList<>();
		final Map<Value, List<Value>> reverseMappingBuilder = new HashMap<>();
		for (final Map.Entry<Value, T> entry : keyToValues.entrySet())
			for (final Value value : entry.getValue())
			{
				pairs.add(new Pair<>(value, entry.getKey()));
				MapUtils.addToList(reverseMappingBuilder, value, entry.getKey());
			}
		mapping = new HashMap<>();
		for (final Map.Entry<Value, T> entry : keyToValues.entrySet())
			mapping.put(entry.getKey(), new ExplicitUnaryDenotation(entry.getValue()));
		reverseMapping = new HashMap<>();
		for (final Map.Entry<Value, List<Value>> entry : reverseMappingBuilder.entrySet())
			reverseMapping.put(entry.getKey(), new ExplicitUnaryDenotation(entry.getValue()));
	}

	// ============================================================
	// Representation
	// ============================================================

	@Override
	public String toString()
	{
		return toLispTree().toString();
	}

	protected static final LispTree NULL_LEAF = LispTree.proto.newLeaf(null);

	@Override
	public LispTree toLispTree()
	{
		final LispTree tree = LispTree.proto.newList();
		for (final Pair<Value, Value> pair : pairs)
		{
			final Value first = pair.getFirst(), second = pair.getSecond();
			tree.addChild(LispTree.proto.newList(first == null ? NULL_LEAF : first.toLispTree(), second == null ? NULL_LEAF : second.toLispTree()));
		}
		return tree;
	}

	@Override
	public PairListValue toValue()
	{
		PairListValue result = new PairListValue(pairs);
		if (LambdaDCSExecutor.opts.sortResults)
			result = result.getSorted();
		return result;
	}

	// ============================================================
	// Getter
	// ============================================================

	@Override
	public UnaryDenotation domain()
	{
		return new ExplicitUnaryDenotation(mapping.keySet());
	}

	@Override
	public UnaryDenotation range()
	{
		return new ExplicitUnaryDenotation(reverseMapping.keySet());
	}

	@Override
	public UnaryDenotation get(final Value key)
	{
		UnaryDenotation values = mapping.get(key);
		if (values == null)
			values = mapping.get(null);
		return values == null ? UnaryDenotation.EMPTY : values;
	}

	@Override
	public UnaryDenotation inverseGet(final Value value)
	{
		final UnaryDenotation keys = reverseMapping.get(value);
		return keys == null ? UnaryDenotation.EMPTY : keys;
	}

	// ============================================================
	// Operations
	// ============================================================

	@Override
	public PairList aggregate(final Mode mode)
	{
		final Map<Value, UnaryDenotation> aggregated = new HashMap<>();
		for (final Map.Entry<Value, UnaryDenotation> entry : mapping.entrySet())
			aggregated.put(entry.getKey(), entry.getValue().aggregate(mode));
		if (mode == Mode.count && !aggregated.containsKey(null))
			aggregated.put(null, UnaryDenotation.ZERO);
		return new ExplicitPairList(aggregated);
	}

	@Override
	public PairList filter(final UnaryDenotation upperBound, final UnaryDenotation domainUpperBound)
	{
		return explicitlyFilter(upperBound, domainUpperBound);
	}

	@Override
	public ExplicitPairList reverse()
	{
		final List<Pair<Value, Value>> reversed = new ArrayList<>();
		for (final Pair<Value, Value> pair : pairs)
			reversed.add(new Pair<>(pair.getSecond(), pair.getFirst()));
		return new ExplicitPairList(reversed);
	}

	@Override
	public UnaryDenotation joinOnKey(final UnaryDenotation keys)
	{
		final List<Value> values = new ArrayList<>();
		for (final Map.Entry<Value, UnaryDenotation> entry : mapping.entrySet())
			if (keys.contains(entry.getKey()))
				values.addAll(entry.getValue());
		return new ExplicitUnaryDenotation(values);
	}

	@Override
	public UnaryDenotation joinOnValue(final UnaryDenotation values)
	{
		final List<Value> keys = new ArrayList<>();
		for (final Map.Entry<Value, UnaryDenotation> entry : reverseMapping.entrySet())
			if (values.contains(entry.getKey()))
				keys.addAll(entry.getValue());
		return new ExplicitUnaryDenotation(keys);
	}

	@Override
	public ExplicitPairList explicitlyFilterOnKey(final UnaryDenotation keys)
	{
		final List<Pair<Value, Value>> filtered = new ArrayList<>();
		for (final Pair<Value, Value> pair : pairs)
			if (keys.contains(pair.getSecond()))
				filtered.add(pair);
		return new ExplicitPairList(filtered);
	}

	@Override
	public ExplicitPairList explicitlyFilterOnValue(final UnaryDenotation values)
	{
		final List<Pair<Value, Value>> filtered = new ArrayList<>();
		for (final Pair<Value, Value> pair : pairs)
			if (values.contains(pair.getFirst()))
				filtered.add(pair);
		return new ExplicitPairList(filtered);
	}

	public ExplicitPairList explicitlyFilter(final UnaryDenotation values, final UnaryDenotation keys)
	{
		final List<Pair<Value, Value>> filtered = new ArrayList<>();
		for (final Pair<Value, Value> pair : pairs)
			if (values.contains(pair.getFirst()) && keys.contains(pair.getSecond()))
				filtered.add(pair);
		return new ExplicitPairList(filtered);
	}

}
