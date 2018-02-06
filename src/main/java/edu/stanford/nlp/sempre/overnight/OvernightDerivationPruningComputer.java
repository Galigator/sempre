package edu.stanford.nlp.sempre.overnight;

import edu.stanford.nlp.sempre.Derivation;
import edu.stanford.nlp.sempre.DerivationPruner;
import edu.stanford.nlp.sempre.DerivationPruningComputer;
import edu.stanford.nlp.sempre.ErrorValue;
import edu.stanford.nlp.sempre.ListValue;
import edu.stanford.nlp.sempre.NumberValue;
import edu.stanford.nlp.sempre.SemType;
import edu.stanford.nlp.sempre.StringValue;
import edu.stanford.nlp.sempre.Value;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Hard-coded hacks for pruning derivations in floating parser for overnight domains.
 */

public class OvernightDerivationPruningComputer extends DerivationPruningComputer
{

	public OvernightDerivationPruningComputer(final DerivationPruner pruner)
	{
		super(pruner);
	}

	@Override
	public Collection<String> getAllStrategyNames()
	{
		return Arrays.asList("violateHardConstraints");
	}

	@Override
	public String isPruned(final Derivation deriv)
	{
		if (containsStrategy("violateHardConstraints") && violateHardConstraints(deriv))
			return "violateHardConstraints";
		return null;
	}

	// Check a few hard constraints on each derivation
	private static boolean violateHardConstraints(final Derivation deriv)
	{
		if (deriv.value != null)
		{
			if (deriv.value instanceof ErrorValue)
				return true;
			if (deriv.value instanceof StringValue)
				if (((StringValue) deriv.value).value.equals("[]"))
					return true;
			if (deriv.value instanceof ListValue)
			{
				final List<Value> values = ((ListValue) deriv.value).values;
				// empty lists
				if (values.size() == 0)
					return true;
				// NaN
				if (values.size() == 1 && values.get(0) instanceof NumberValue)
					if (Double.isNaN(((NumberValue) values.get(0))._value))
						return true;
				// If we are supposed to get a number but we get a string (some sparql weirdness)
				if (deriv.type.equals(SemType.numberType) && values.size() == 1 && !(values.get(0) instanceof NumberValue))
					return true;
			}
		}
		return false;
	}

}
