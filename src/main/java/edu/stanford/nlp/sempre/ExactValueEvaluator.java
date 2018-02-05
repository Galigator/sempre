package edu.stanford.nlp.sempre;

// This is the simplest evaluator, but exact match can sometimes be too harsh.
public class ExactValueEvaluator implements ValueEvaluator
{
	@Override
	public double getCompatibility(final Value target, final Value pred)
	{
		return target.equals(pred) ? 1 : 0;
	}
}
