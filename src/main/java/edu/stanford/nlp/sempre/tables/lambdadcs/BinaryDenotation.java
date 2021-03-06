package edu.stanford.nlp.sempre.tables.lambdadcs;

import edu.stanford.nlp.sempre.PairListValue;
import fig.basic.LispTree;

/**
 * Binary denotation: a mapping from value to values. Share the implementation with MappingDenotation by using PairList.
 *
 * @author ppasupat
 */
public class BinaryDenotation<PL extends PairList> implements Binarylike
{

	protected final PL pairList;

	@Override
	public String toString()
	{
		return toLispTree().toString();
	}

	public BinaryDenotation(final PL pairList)
	{
		this.pairList = pairList;
	}

	@Override
	public LispTree toLispTree()
	{
		return LispTree.proto.newList("binary", pairList.toLispTree());
	}

	@Override
	public PairListValue toValue()
	{
		return pairList.toValue();
	}

	public MappingDenotation<PL> asMapping(final String domainVar)
	{
		return new MappingDenotation<>(domainVar, pairList);
	}

	@Override
	public Binarylike reverse()
	{
		return new BinaryDenotation<>(pairList.reverse());
	}

	@Override
	public UnaryDenotation joinOnKey(final UnaryDenotation keys)
	{
		return pairList.joinOnKey(keys);
	}

	@Override
	public UnaryDenotation joinOnValue(final UnaryDenotation values)
	{
		return pairList.joinOnValue(values);
	}

	@Override
	public BinaryDenotation<ExplicitPairList> explicitlyFilterOnKey(final UnaryDenotation keys)
	{
		return new BinaryDenotation<>(pairList.explicitlyFilterOnKey(keys));
	}

	@Override
	public BinaryDenotation<ExplicitPairList> explicitlyFilterOnValue(final UnaryDenotation values)
	{
		return new BinaryDenotation<>(pairList.explicitlyFilterOnValue(values));
	}

}
