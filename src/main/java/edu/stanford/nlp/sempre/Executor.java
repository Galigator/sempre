package edu.stanford.nlp.sempre;

import fig.basic.Evaluation;

/**
 * An Executor takes a logical form (Formula) and computes its denotation (Value).
 *
 * @author Percy Liang
 */
public abstract class Executor
{
	public static class Response
	{
		public Response(final Value value_)
		{
			this(value_, new Evaluation());
		}

		public Response(final Value value_, final Evaluation stats_)
		{
			value = value_;
			stats = stats_;
		}

		public final Value value;
		public final Evaluation stats;
	}

	// Execute the formula in the given context.
	public abstract Response execute(Formula formula, ContextValue context);
}
