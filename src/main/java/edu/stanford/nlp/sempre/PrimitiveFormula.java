package edu.stanford.nlp.sempre;

import com.google.common.base.Function;
import java.util.List;

/**
 * A PrimitiveFormula represents an atomic value which is cannot be decomposed into further symbols. Either a ValueFormula or a VariableFormula.
 *
 * @author Percy Liang
 */
public abstract class PrimitiveFormula extends Formula
{

	@Override
	public void forEach(final Function<Formula, Boolean> func)
	{
		func.apply(this);
	}

	@Override
	public Formula map(final Function<Formula, Formula> func)
	{
		final Formula result = func.apply(this);
		return result == null ? this : result;
	}

	@Override
	public List<Formula> mapToList(final Function<Formula, List<Formula>> func, final boolean alwaysRecurse)
	{
		return func.apply(this);
	}
}
