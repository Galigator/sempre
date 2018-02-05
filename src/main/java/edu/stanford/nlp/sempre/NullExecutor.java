package edu.stanford.nlp.sempre;

/**
 * Assign null semantics to each formula.
 *
 * @author Percy Liang
 */
public class NullExecutor extends Executor
{
	@Override
	public Response execute(final Formula formula, final ContextValue context)
	{
		return new Response(null);
	}
}
