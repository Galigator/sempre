package edu.stanford.nlp.sempre.tables.lambdadcs;

import edu.stanford.nlp.sempre.AggregateFormula.Mode;
import edu.stanford.nlp.sempre.ListValue;
import edu.stanford.nlp.sempre.Value;
import edu.stanford.nlp.sempre.tables.lambdadcs.LambdaDCSException.Type;
import fig.basic.LispTree;

/**
 * Mapping denotation: a mapping from variable assignment to values. Share the implementation with BinaryDenotation by using PairList.
 *
 * @author ppasupat
 */
public class MappingDenotation<PL extends PairList> implements Unarylike
{

	protected final String domainVar;
	protected final PL pairList;

	protected MappingDenotation(final String domainVar, final PL pairList)
	{
		this.domainVar = domainVar;
		this.pairList = pairList;
	}

	@Override
	public String toString()
	{
		return toLispTree().toString();
	}

	@Override
	public LispTree toLispTree()
	{
		return LispTree.proto.L("mapping", domainVar, pairList.toLispTree());
	}

	@Override
	public ListValue toValue()
	{
		throw new LambdaDCSException(Type.notUnary, "Expected Unary; Mapping found: %s", this);
	}

	@Override
	public String getDomainVar()
	{
		return domainVar;
	}

	public BinaryDenotation<PL> asBinary()
	{
		return new BinaryDenotation<>(pairList);
	}

	@Override
	public UnaryDenotation domain()
	{
		return pairList.domain();
	}

	@Override
	public UnaryDenotation range()
	{
		return pairList.range();
	}

	@Override
	public UnaryDenotation get(final Value key)
	{
		return pairList.get(key);
	}

	@Override
	public UnaryDenotation inverseGet(final Value value)
	{
		return pairList.inverseGet(value);
	}

	@Override
	public Unarylike aggregate(final Mode mode)
	{
		return new MappingDenotation<>(domainVar, pairList.aggregate(mode));
	}

	@Override
	public Unarylike filter(final UnaryDenotation upperBound, final UnaryDenotation domainUpperBound)
	{
		return new MappingDenotation<>(domainVar, pairList.filter(upperBound, domainUpperBound));
	}

}
