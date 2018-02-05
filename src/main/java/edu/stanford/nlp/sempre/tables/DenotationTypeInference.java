package edu.stanford.nlp.sempre.tables;

import edu.stanford.nlp.sempre.AtomicSemType;
import edu.stanford.nlp.sempre.BooleanValue;
import edu.stanford.nlp.sempre.CanonicalNames;
import edu.stanford.nlp.sempre.DateValue;
import edu.stanford.nlp.sempre.ErrorValue;
import edu.stanford.nlp.sempre.FuncSemType;
import edu.stanford.nlp.sempre.ListValue;
import edu.stanford.nlp.sempre.NameValue;
import edu.stanford.nlp.sempre.NumberValue;
import edu.stanford.nlp.sempre.PairListValue;
import edu.stanford.nlp.sempre.SemType;
import edu.stanford.nlp.sempre.SemTypeHierarchy;
import edu.stanford.nlp.sempre.StringValue;
import edu.stanford.nlp.sempre.TimeValue;
import edu.stanford.nlp.sempre.TypeInference;
import edu.stanford.nlp.sempre.TypeLookup;
import edu.stanford.nlp.sempre.Value;
import edu.stanford.nlp.sempre.Values;
import fig.basic.LispTree;
import fig.basic.LogInfo;
import fig.basic.Option;
import fig.basic.Pair;
import java.util.Set;

/**
 * Infer the type of a Value object.
 *
 * @author ppasupat
 */
public class DenotationTypeInference
{
	public static class Options
	{
		@Option(gloss = "Allow unknown Value type")
		public boolean allowUnknownValueType = true;
	}

	public static Options opts = new Options();

	private DenotationTypeInference()
	{
	};

	/**
	 * Return the type of the given Value as a String. If the Value contains Values of different types (e.g., if the Value is a ListValue), return the least
	 * common ancestor. For a Value that represents a mapping (e.g., PairListValue), return the type of the "values" as opposed to the keys.
	 */
	public static String getValueType(final Value value)
	{
		if (value instanceof NumberValue)
			return CanonicalNames.NUMBER;
		else
			if (value instanceof DateValue)
				return CanonicalNames.DATE;
			else
				if (value instanceof TimeValue)
					return CanonicalNames.TIME;
				else
					if (value instanceof StringValue)
						return CanonicalNames.TEXT;
					else
						if (value instanceof BooleanValue)
							return CanonicalNames.BOOLEAN;
						else
							if (value instanceof ErrorValue)
								return "ERROR";
							else
								if (value instanceof NameValue)
								{
									final SemType type = getNameValueSemType((NameValue) value);
									if (type instanceof AtomicSemType)
										return ((AtomicSemType) type).name;
								}
								else
									if (value instanceof ListValue)
									{
										final ListValue listValue = (ListValue) value;
										if (listValue.values.isEmpty())
											return "EMPTY";
										String commonType = null;
										for (final Value x : listValue.values)
										{
											final String type = getValueType(x);
											if (commonType == null)
												commonType = type;
											else
												if (!commonType.equals(type))
													commonType = findLowestCommonAncestor(commonType, type);
										}
										return commonType;
									}
									else
										if (value instanceof InfiniteListValue)
										{
											final LispTree tree = ((InfiniteListValue) value).toLispTree();
											if (tree.children.size() >= 2)
												// (comparison value) or (comparison value comparison value)
												return getValueType(Values.fromLispTree(tree.child(1)));
											else
												// STAR = (*)
												return CanonicalNames.ANY;
										}
										else
											if (value instanceof PairListValue)
											{
												final PairListValue pairListValue = (PairListValue) value;
												if (pairListValue.pairs.isEmpty())
													return "EMPTY";
												String commonType = null;
												for (final Pair<Value, Value> pair : pairListValue.pairs)
												{
													final String type = getValueType(pair.getSecond());
													if (commonType == null)
														commonType = type;
													else
														if (!commonType.equals(type))
															commonType = findLowestCommonAncestor(commonType, type);
												}
												return commonType;
											}
											else
												if (value instanceof ScopedValue)
													return getValueType(((ScopedValue) value).relation);
		if (opts.allowUnknownValueType)
			return "UNKNOWN VALUE: " + value;
		else
			throw new RuntimeException("Unhandled value: " + value);
	}

	public static String getKeyType(final Value value)
	{
		if (value instanceof NameValue)
		{
			final SemType type = getNameValueSemType((NameValue) value);
			if (type instanceof FuncSemType)
				return type.getArgType().toString();
		}
		else
			if (value instanceof PairListValue)
			{
				String commonType = null;
				for (final Pair<Value, Value> pair : ((PairListValue) value).pairs)
				{
					final String type = getValueType(pair.getFirst());
					if (commonType == null)
						commonType = type;
					else
						if (!commonType.equals(type))
							commonType = findLowestCommonAncestor(commonType, type);
				}
				return commonType;
			}
			else
				if (value instanceof ScopedValue)
					return getValueType(((ScopedValue) value).head);
		if (opts.allowUnknownValueType)
			return "UNKNOWN KEY: " + value;
		else
			throw new RuntimeException("Unhandled value: " + value);
	}

	/**
	 * Helper function: get the type of NameValue using TypeLookup.
	 */
	public static SemType getNameValueSemType(final NameValue value)
	{
		final String id = value.id;
		final TypeLookup typeLookup = TypeInference.getTypeLookup();
		if (CanonicalNames.isUnary(id))
		{ // Unary
			final SemType unaryType = typeLookup.getEntityType(id);
			return unaryType == null ? SemType.entityType : unaryType;
		}
		else
		{ // Binary
			// Careful of the reversal.
			SemType propertyType = null;
			if (CanonicalNames.SPECIAL_SEMTYPES.containsKey(id))
				propertyType = CanonicalNames.SPECIAL_SEMTYPES.get(id);
			else
				if (!CanonicalNames.isReverseProperty(id))
					propertyType = typeLookup.getPropertyType(id);
				else
				{
					propertyType = typeLookup.getPropertyType(CanonicalNames.reverseProperty(id));
					if (propertyType != null)
						propertyType = propertyType.reverse();
				}
			return propertyType == null ? SemType.anyAnyFunc : propertyType;
		}
	}

	/**
	 * Find the lowest common ancestor of the two given types using the type hierarchy.
	 */
	public static String findLowestCommonAncestor(final String type1, final String type2)
	{
		final SemTypeHierarchy hierarchy = SemTypeHierarchy.singleton;
		final Set<String> sup1 = hierarchy.getSupertypes(type1), sup2 = hierarchy.getSupertypes(type2);
		String lca = CanonicalNames.ANY;
		for (final String type : sup1)
			if (sup2.contains(type))
				if (hierarchy.getSupertypes(type).contains(lca))
					lca = type;
		return lca;
	}

	/**
	 * Check if two objects have the same type.
	 */
	public static boolean typeCheck(final Value v1, final Value v2)
	{
		final String t1 = getValueType(v1), t2 = getValueType(v2);
		if (t1 == null)
		{
			LogInfo.logs("NULL type occurred: %s %s", v1, t1);
			return false;
		}
		if (t2 == null)
		{
			LogInfo.logs("NULL type occurred: %s %s", v2, t2);
			return false;
		}
		return t1.equals(t2);
	}

}
