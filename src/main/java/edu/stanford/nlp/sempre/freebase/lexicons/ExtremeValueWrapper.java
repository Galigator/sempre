package edu.stanford.nlp.sempre.freebase.lexicons;

public abstract class ExtremeValueWrapper
{
	public double distance;

	public abstract boolean add(double other);
}

class MinValueWrapper extends ExtremeValueWrapper
{

	public MinValueWrapper(final double max)
	{
		distance = max;
	}

	@Override
	public boolean add(final double other)
	{
		if (other < distance)
		{
			distance = other;
			return true;
		}
		return false;
	}
}

class MaxValueWrapper extends ExtremeValueWrapper
{

	public MaxValueWrapper(final double min)
	{
		distance = min;
	}

	@Override
	public boolean add(final double other)
	{
		if (other > distance)
		{
			distance = other;
			return true;
		}
		return false;
	}
}
