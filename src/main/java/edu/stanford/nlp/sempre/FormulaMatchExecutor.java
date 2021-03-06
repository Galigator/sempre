package edu.stanford.nlp.sempre;

/**
 * Return a string representation of a formula as the value. This enables evaluation against exact match of logical forms. This is overly stringent right now.
 */
public class FormulaMatchExecutor extends Executor
{
	@SuppressWarnings("unchecked")
	@Override
	public Response execute(final Formula formula_, final ContextValue context)
	{
		Formula formula = formula_;
		formula = Formulas.betaReduction(formula);
		Value value;
		if (formula instanceof ValueFormula)
			value = ((ValueFormula<Value>) formula).value;
		else
			value = new StringValue(formula.toLispTree().toString());
		return new Response(value);
	}
}
