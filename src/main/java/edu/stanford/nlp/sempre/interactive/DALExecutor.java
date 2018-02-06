package edu.stanford.nlp.sempre.interactive;

import com.google.common.collect.Lists;
import com.google.common.collect.ObjectArrays;
import com.google.common.collect.Sets;
import edu.stanford.nlp.sempre.ActionFormula;
import edu.stanford.nlp.sempre.AggregateFormula;
import edu.stanford.nlp.sempre.ArithmeticFormula;
import edu.stanford.nlp.sempre.BooleanValue;
import edu.stanford.nlp.sempre.CallFormula;
import edu.stanford.nlp.sempre.ContextValue;
import edu.stanford.nlp.sempre.ErrorValue;
import edu.stanford.nlp.sempre.Executor;
import edu.stanford.nlp.sempre.Formula;
import edu.stanford.nlp.sempre.Formulas;
import edu.stanford.nlp.sempre.JoinFormula;
import edu.stanford.nlp.sempre.ListValue;
import edu.stanford.nlp.sempre.MergeFormula;
import edu.stanford.nlp.sempre.NameValue;
import edu.stanford.nlp.sempre.NotFormula;
import edu.stanford.nlp.sempre.NumberValue;
import edu.stanford.nlp.sempre.ReverseFormula;
import edu.stanford.nlp.sempre.StringValue;
import edu.stanford.nlp.sempre.SuperlativeFormula;
import edu.stanford.nlp.sempre.Value;
import edu.stanford.nlp.sempre.ValueFormula;
import fig.basic.LogInfo;
import fig.basic.Option;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Handles action lambda DCS where the world has a flat structure, i.e. a list of allitems all supporting the same operations supports ActionFormula here, and
 * does conversions of singleton sets
 * 
 * @author sidaw
 */
public class DALExecutor extends Executor
{
	public static class Options
	{
		@Option(gloss = "Whether to convert NumberValue to int/double")
		public boolean convertNumberValues = true;
		@Option(gloss = "Whether to convert name values to string literal")
		public boolean convertNameValues = true;

		@Option(gloss = "Print stack trace on exception")
		public boolean printStackTrace = false;
		// the actual function will be called with the current ContextValue as its
		// last argument if marked by contextPrefix
		@Option(gloss = "Reduce verbosity by automatically appending, for example, edu.stanford.nlp.sempre to java calls")
		public String classPathPrefix = "edu.stanford.nlp.sempre";

		@Option(gloss = "The type of world used")
		public String worldType = "VoxelWorld";

		@Option(gloss = "the maximum number of primitive calls until we stop executing")
		public int maxSteps = 1000;

		@Option(gloss = "The maximum number of while calls")
		public int maxWhile = 20;
	}

	public static Options opts = new Options();

	@Override
	public Response execute(final Formula formula_, final ContextValue context)
	{
		Formula formula = formula_;
		// We can do beta reduction here since macro substitution preserves the
		// denotation (unlike for lambda DCS).
		final World world = World.fromContext(opts.worldType, context);
		formula = Formulas.betaReduction(formula);
		try
		{
			performActions((ActionFormula) formula, world);
			return new Response(new StringValue(world.toJSON()));
		}
		catch (final Exception e)
		{
			// Comment this out if we expect lots of innocuous type checking failures
			if (opts.printStackTrace)
			{
				LogInfo.log("Failed to execute " + formula.toString());
				e.printStackTrace();
			}
			return new Response(ErrorValue.badJava(e.toString()));
		}
	}

	@SuppressWarnings("rawtypes")
	private void performActions(final ActionFormula f, final World world)
	{
		if (f.mode == ActionFormula.Mode.primitive)
		{
			// use reflection to call primitive stuff
			final Value method = ((ValueFormula) f.args.get(0)).value;
			final String id = ((NameValue) method)._id;
			// all actions takes a fixed set as argument
			invoke(id, world, f.args.subList(1, f.args.size()).stream().map(x -> processSetFormula(x, world)).toArray());
			world.merge();
		}
		else
			if (f.mode == ActionFormula.Mode.sequential)
				for (final Formula child : f.args)
					performActions((ActionFormula) child, world);
			else
				if (f.mode == ActionFormula.Mode.repeat)
				{
					final Set<Object> arg = toSet(processSetFormula(f.args.get(0), world));
					if (arg.size() > 1)
						throw new RuntimeException("repeat has to take a single number");
					int times;
					if (!opts.convertNumberValues)
						times = (int) ((NumberValue) arg.iterator().next())._value;
					else
						times = (int) arg.iterator().next();

					for (int i = 0; i < times; i++)
						performActions((ActionFormula) f.args.get(1), world);
				}
				else
					if (f.mode == ActionFormula.Mode.conditional)
					{
						// using the empty set to represent false
						final boolean cond = toSet(processSetFormula(f.args.get(0), world)).iterator().hasNext();
						if (cond)
							performActions((ActionFormula) f.args.get(1), world);
					}
					else
						if (f.mode == ActionFormula.Mode.whileloop)
						{
							// using the empty set to represent false
							boolean cond = toSet(processSetFormula(f.args.get(0), world)).iterator().hasNext();
							for (int i = 0; i < opts.maxWhile; i++)
							{
								if (cond)
									performActions((ActionFormula) f.args.get(1), world);
								else
									break;
								cond = toSet(processSetFormula(f.args.get(0), world)).iterator().hasNext();
							}
						}
						else
							if (f.mode == ActionFormula.Mode.forset)
							{
								// mostly deprecated
								final Set<Object> selected = toSet(processSetFormula(f.args.get(0), world));
								final Set<Item> prevSelected = world.selected;

								world.selected = toItemSet(selected);
								performActions((ActionFormula) f.args.get(1), world);

								world.selected = prevSelected;
								world.merge();
							}
							else
								if (f.mode == ActionFormula.Mode.foreach)
								{
									final Set<Item> selected = toItemSet(toSet(processSetFormula(f.args.get(0), world)));
									final Set<Item> prevSelected = world.selected;
									// CopyOnWriteArraySet<Object> fixedset =
									// Sets.newCopyOnWriteArraySet(selected);
									final Iterator<Item> iterator = selected.iterator();
									while (iterator.hasNext())
									{
										world.selected = toItemSet(toSet(iterator.next()));
										performActions((ActionFormula) f.args.get(1), world);
									}
									world.selected = prevSelected;
									world.merge();

								}
								else
									if (f.mode == ActionFormula.Mode.isolate)
									{
										final Set<Item> prevAll = world.allItems;
										// Set<Item> prevSelected = world.selected;
										// Set<Item> prevPrevious = world.previous;
										if (f.args.size() > 1)
											throw new RuntimeException("No longer supporting this isolate formula: " + f);

										world.allItems = Sets.newHashSet(world.selected);
										// world.selected = scope;
										// world.previous = scope;
										performActions((ActionFormula) f.args.get(0), world);

										world.allItems.addAll(prevAll); // merge, overriding;
										// world.selected = prevSelected;
										// world.previous = prevPrevious;
										world.merge();

									}
									else
										if (f.mode == ActionFormula.Mode.block || f.mode == ActionFormula.Mode.blockr)
										{
											// we should never mutate selected in actions
											final Set<Item> prevSelected = world.selected;
											final Set<Item> prevPrevious = world.previous;
											world.previous = world.selected;

											for (final Formula child : f.args)
												performActions((ActionFormula) child, world);

											// restore on default blocks
											if (f.mode == ActionFormula.Mode.block)
											{
												world.selected = prevSelected;
												world.merge();
											}
											// LogInfo.logs("CBlocking prevselected=%s selected=%s all=%s",
											// prevSelected, world.selected, world.allitems);
											// LogInfo.logs("BlockingWorldIs %s", world.toJSON());
											world.previous = prevPrevious;
										}
		// } else if (f.mode == ActionFormula.Mode.let) {
		// // let declares a new local variable
		// // set access and reassigns the value of some variable
		// // block determines what is considered local scope
		// // for now the use case is just (:blk (:let x this) (:blah) (:set this
		// x))
		// Set<Item> varset = toItemSet(toSet(processSetFormula(f.args.get(1),
		// world)));
		// Value method = ((ValueFormula)f.args.get(0)).value;
		// String varname = ((NameValue)method).id;
		// world.variables.put(varname, varset);
		// } else if (f.mode == ActionFormula.Mode.set) {
		// Set<Item> varset = toItemSet(toSet(processSetFormula(f.args.get(1),
		// world)));
		// Value method = ((ValueFormula)f.args.get(0)).value;
		// String varname = ((NameValue)method).id;
		// world.variables.get(varname).clear();
		// world.variables.get(varname).addAll(varset);
		// }

	}

	@SuppressWarnings("unchecked")
	private Set<Object> toSet(final Object maybeSet)
	{
		if (maybeSet instanceof Set)
			return (Set<Object>) maybeSet;
		else
			return Sets.newHashSet(maybeSet);
	}

	private Object toElement(final Set<Object> set)
	{
		if (set.size() == 1)
			return set.iterator().next();
		return set;
	}

	private Set<Item> toItemSet(final Set<Object> maybeItems)
	{
		final Set<Item> itemset = maybeItems.stream().map(i -> (Item) i).collect(Collectors.toSet());
		return itemset;
	}

	static class SpecialSets
	{
		static String All = "*";
		static String EmptySet = "nothing";
		static String This = "this"; // current scope if it exists, otherwise the
										// globally marked object
		static String Previous = "prev"; // global variable for selected
		static String Selected = "selected"; // global variable for selected
	}

	// a subset of lambda dcs. no types, and no marks
	// if this gets any more complicated, you should consider the
	// LambdaDCSExecutor
	@SuppressWarnings("unchecked")
	private Object processSetFormula(final Formula formula, final World world)
	{
		if (formula instanceof ValueFormula<?>)
		{
			final Value v = ((ValueFormula<?>) formula).value;
			// special unary
			if (v instanceof NameValue)
			{
				final String id = ((NameValue) v)._id;
				// LogInfo.logs("%s : this %s, all: %s", id,
				// world.selected().toString(), world.allitems.toString());
				if (id.equals(SpecialSets.All))
					return world.all();
				if (id.equals(SpecialSets.This))
					return world.selected();
				if (id.equals(SpecialSets.Selected))
					return world.selected();
				if (id.equals(SpecialSets.EmptySet))
					return world.empty();
				if (id.equals(SpecialSets.Previous))
					return world.previous();
			}
			return toObject(((ValueFormula<?>) formula).value);
		}

		if (formula instanceof JoinFormula)
		{
			final JoinFormula joinFormula = (JoinFormula) formula;
			if (joinFormula.relation instanceof ValueFormula)
			{
				final String rel = ((ValueFormula<NameValue>) joinFormula.relation).value._id;
				final Set<Object> unary = toSet(processSetFormula(joinFormula.child, world));
				return world.has(rel, unary);
			}
			else
				if (joinFormula.relation instanceof ReverseFormula)
				{
					final ReverseFormula reverse = (ReverseFormula) joinFormula.relation;
					final String rel = ((ValueFormula<NameValue>) reverse.child).value._id;
					final Set<Object> unary = toSet(processSetFormula(joinFormula.child, world));
					return world.get(rel, toItemSet(unary));
				}
				else
					throw new RuntimeException("relation can either be a value, or its reverse");
		}

		if (formula instanceof MergeFormula)
		{
			final MergeFormula mergeFormula = (MergeFormula) formula;
			final MergeFormula.Mode mode = mergeFormula.mode;
			final Set<Object> set1 = toSet(processSetFormula(mergeFormula.child1, world));
			final Set<Object> set2 = toSet(processSetFormula(mergeFormula.child2, world));

			if (mode == MergeFormula.Mode.or)
				return Sets.union(set1, set2);
			if (mode == MergeFormula.Mode.and)
				return Sets.intersection(set1, set2);

		}

		if (formula instanceof NotFormula)
		{
			final NotFormula notFormula = (NotFormula) formula;
			final Set<Item> set1 = toItemSet(toSet(processSetFormula(notFormula.child, world)));
			return Sets.difference(world.allItems, set1);
		}

		if (formula instanceof AggregateFormula)
		{
			final AggregateFormula aggregateFormula = (AggregateFormula) formula;
			final Set<Object> set = toSet(processSetFormula(aggregateFormula.child, world));
			final AggregateFormula.Mode mode = aggregateFormula.mode;
			if (mode == AggregateFormula.Mode.count)
				return Sets.newHashSet(set.size());
			if (mode == AggregateFormula.Mode.max)
				return Sets.newHashSet(set.stream().max((s, t) -> ((NumberValue) s)._value > ((NumberValue) t)._value ? 1 : -1));
			if (mode == AggregateFormula.Mode.min)
				return Sets.newHashSet(set.stream().max((s, t) -> ((NumberValue) s)._value < ((NumberValue) t)._value ? 1 : -1));
		}

		if (formula instanceof ArithmeticFormula)
		{
			final ArithmeticFormula arithmeticFormula = (ArithmeticFormula) formula;
			final Integer arg1 = (Integer) processSetFormula(arithmeticFormula.child1, world);
			final Integer arg2 = (Integer) processSetFormula(arithmeticFormula.child2, world);
			final ArithmeticFormula.Mode mode = arithmeticFormula.mode;
			if (mode == ArithmeticFormula.Mode.add)
				return arg1 + arg2;
			if (mode == ArithmeticFormula.Mode.sub)
				return arg1 - arg2;
			if (mode == ArithmeticFormula.Mode.mul)
				return arg1 * arg2;
			if (mode == ArithmeticFormula.Mode.div)
				return arg1 / arg2;
		}

		if (formula instanceof CallFormula)
		{
			final CallFormula callFormula = (CallFormula) formula;
			@SuppressWarnings("rawtypes")
			final Value method = ((ValueFormula) callFormula.func).value;
			final String id = ((NameValue) method)._id;
			// all actions takes a fixed set as argument
			return invoke(id, world, callFormula.args.stream().map(x -> processSetFormula(x, world)).toArray());
		}
		if (formula instanceof SuperlativeFormula)
			throw new RuntimeException("SuperlativeFormula is not implemented");
		throw new RuntimeException("ActionExecutor does not handle this formula type: " + formula.getClass());
	}

	// Example: id = "Math.cos". similar to JavaExecutor's invoke,
	// but matches arg by building singleton set as needed
	private Object invoke(final String id, final World thisObj, Object... args)
	{
		Method[] methods;
		Class<?> cls;
		String methodName;
		final boolean isStatic = thisObj == null;

		if (isStatic)
		{ // Static methods
			final int i = id.lastIndexOf('.');
			if (i == -1)
				throw new RuntimeException("Expected <class>.<method>, but got: " + id);
			final String className = id.substring(0, i);
			methodName = id.substring(i + 1);

			try
			{
				cls = Class.forName(className);
			}
			catch (final ClassNotFoundException e)
			{
				throw new RuntimeException(e);
			}
			methods = cls.getMethods();
		}
		else
		{ // Instance methods
			cls = thisObj.getClass();
			methodName = id;
			methods = cls.getMethods();
		}

		// Find a suitable method
		final List<Method> nameMatches = Lists.newArrayList();
		Method bestMethod = null;
		int bestCost = INVALID_TYPE_COST;
		for (final Method m : methods)
		{
			if (!m.getName().equals(methodName))
				continue;
			m.setAccessible(true);
			nameMatches.add(m);
			if (isStatic != Modifier.isStatic(m.getModifiers()))
				continue;
			int cost = typeCastCost(m.getParameterTypes(), args);

			// append optional selected parameter when needed:
			if (cost == INVALID_TYPE_COST && args.length + 1 == m.getParameterCount())
			{
				args = ObjectArrays.concat(args, thisObj.selected);
				cost = typeCastCost(m.getParameterTypes(), args);
			}

			if (cost < bestCost)
			{
				bestCost = cost;
				bestMethod = m;
			}
		}

		if (bestMethod != null)
			try
			{
				return bestMethod.invoke(thisObj, args);
			}
			catch (final InvocationTargetException e)
			{
				throw new RuntimeException(e.getCause());
			}
			catch (final IllegalAccessException e)
			{
				throw new RuntimeException(e);
			}
		final List<String> types = Lists.newArrayList();
		for (final Object arg : args)
			types.add(arg.getClass().toString());
		throw new RuntimeException("Method " + methodName + " not found in class " + cls + " with arguments " + Arrays.asList(args) + " having types " + types + "; candidates: " + nameMatches);
	}

	private int typeCastCost(final Class[] types, final Object[] args)
	{
		if (types.length != args.length)
			return INVALID_TYPE_COST;
		int cost = 0;
		for (int i = 0; i < types.length; i++)
		{

			// deal with singleton sets
			if (types[i] == Set.class)
				args[i] = toSet(args[i]);
			if (types[i] != Set.class && args[i].getClass() == Set.class)
				args[i] = toElement((Set<Object>) args[i]);

			cost += typeCastCost(types[i], args[i]);
			if (cost >= INVALID_TYPE_COST)
			{
				LogInfo.dbgs("NOT COMPATIBLE: want %s, got %s with type %s", types[i], args[i], args[i].getClass());
				break;
			}
		}
		return cost;
	}

	private static Object toObject(final Value value)
	{
		if (value instanceof NumberValue && opts.convertNumberValues)
		{
			// Unfortunately, NumberValues don't make a distinction between ints and
			// doubles, so this is a hack.
			final double x = ((NumberValue) value)._value;
			if (x == (int) x)
				return new Integer((int) x);
			return new Double(x);
		}
		else
			if (value instanceof NameValue && opts.convertNameValues)
			{
				final String id = ((NameValue) value)._id;
				return id;
			}
			else
				if (value instanceof BooleanValue)
					return ((BooleanValue) value).value;
				else
					if (value instanceof StringValue)
						return ((StringValue) value).value;
					else
						if (value instanceof ListValue)
						{
							final List<Object> list = Lists.newArrayList();
							for (final Value elem : ((ListValue) value).values)
								list.add(toObject(elem));
							return list;
						}
						else
							return value; // Preserve the Value (which can be an object)
	}

	// Return whether the object |arg| is compatible with |type|.
	// 0: perfect match
	// 1: don't match, but don't lose anything
	// 2: don't match, and can lose something
	// INVALID_TYPE_COST: impossible
	private int typeCastCost(final Class<?> type, final Object arg)
	{
		if (arg == null)
			return !type.isPrimitive() ? 0 : INVALID_TYPE_COST;
		if (type.isInstance(arg))
			return 0;
		if (type == Boolean.TYPE)
			return arg instanceof Boolean ? 0 : INVALID_TYPE_COST;
		else
			if (type == Integer.TYPE)
			{
				if (arg instanceof Integer)
					return 0;
				if (arg instanceof Long)
					return 1;
				return INVALID_TYPE_COST;
			}
		if (type == Long.TYPE)
		{
			if (arg instanceof Integer)
				return 1;
			if (arg instanceof Long)
				return 0;
			return INVALID_TYPE_COST;
		}
		if (type == Float.TYPE)
		{
			if (arg instanceof Integer)
				return 1;
			if (arg instanceof Long)
				return 1;
			if (arg instanceof Float)
				return 0;
			if (arg instanceof Double)
				return 2;
			return INVALID_TYPE_COST;
		}
		if (type == Double.TYPE)
		{
			if (arg instanceof Integer)
				return 1;
			if (arg instanceof Long)
				return 1;
			if (arg instanceof Float)
				return 1;
			if (arg instanceof Double)
				return 0;
			return INVALID_TYPE_COST;
		}
		return INVALID_TYPE_COST;
	}

	private static final int INVALID_TYPE_COST = 1000;
}
