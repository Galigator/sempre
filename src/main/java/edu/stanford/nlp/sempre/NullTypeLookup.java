package edu.stanford.nlp.sempre;

/**
 * Default implementation of TypeLookup: just return null (I don't know what the type is).
 */
public class NullTypeLookup implements TypeLookup
{
	@Override
	public SemType getEntityType(final String entity)
	{
		return null;
	}

	@Override
	public SemType getPropertyType(final String property)
	{
		return null;
	}
}
