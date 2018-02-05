package edu.stanford.nlp.sempre.geo880;

import edu.stanford.nlp.sempre.DescriptionValue;
import edu.stanford.nlp.sempre.ErrorValue;
import edu.stanford.nlp.sempre.ListValue;
import edu.stanford.nlp.sempre.NameValue;
import edu.stanford.nlp.sempre.NumberValue;
import edu.stanford.nlp.sempre.Value;
import edu.stanford.nlp.sempre.ValueEvaluator;
import fig.basic.LogInfo;
import java.util.List;

/**
 * This is only used because the data does not mention when a city is in the usa, but the kg returns usa, and we want to use exact match, so we add this logic
 * here. Created by joberant on 03/12/2016.
 */
public class Geo880ValueEvaluator implements ValueEvaluator
{

	public double getCompatibility(final Value target, final Value pred)
	{
		final List<Value> targetList = ((ListValue) target).values;
		if (!(pred instanceof ListValue))
			return 0;
		final List<Value> predList = ((ListValue) pred).values;

		// In geo880, if we return that something is contained in a state, there is no need to return fb:country.usa
		Value toDelete = null;
		if (predList.size() > 1 && predList.get(0) instanceof NameValue)
			for (final Value v : predList)
			{
				final String id = ((NameValue) v).id;
				if (id.equals("fb:country.usa"))
				{
					toDelete = v;
					break;
				}
			}
		if (toDelete != null)
			predList.remove(toDelete);

		if (targetList.size() != predList.size())
			return 0;

		for (final Value targetValue : targetList)
		{
			boolean found = false;
			for (final Value predValue : predList)
				if (getItemCompatibility(targetValue, predValue))
				{
					found = true;
					break;
				}
			if (!found)
				return 0;
		}
		return 1;
	}

	// ============================================================
	// Item Compatibility
	// ============================================================

	// Compare one element of the list.
	protected boolean getItemCompatibility(final Value target, final Value pred)
	{
		if (pred instanceof ErrorValue)
			return false; // Never award points for error
		if (pred == null)
		{
			LogInfo.warning("Predicted value is null!");
			return false;
		}

		if (target instanceof DescriptionValue)
		{
			final String targetText = ((DescriptionValue) target).value;
			if (pred instanceof NameValue)
			{
				// Just has to match the description
				String predText = ((NameValue) pred).description;
				if (predText == null)
					predText = "";
				return targetText.equals(predText);
			}
		}
		else
			if (target instanceof NumberValue)
			{
				final NumberValue targetNumber = (NumberValue) target;
				if (pred instanceof NumberValue)
					return compareNumberValues(targetNumber, (NumberValue) pred);
			}

		return target.equals(pred);
	}

	protected boolean compareNumberValues(final NumberValue target, final NumberValue pred)
	{
		return Math.abs(target.value - pred.value) < 1e-6;
	}

}
