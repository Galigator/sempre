package edu.stanford.nlp.sempre;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import fig.basic.MapUtils;
import fig.basic.Option;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * JavaExecutor takes a Formula which is composed recursively of CallFormulas, does reflection, and returns a Value.
 *
 * @author Percy Liang
 */
public class JavaExecutor extends Executor
{
	public static class Options
	{
		@Option(gloss = "Whether to convert NumberValue to int/double")
		public boolean convertNumberValues = true;
		@Option(gloss = "Print stack trace on exception")
		public boolean printStackTrace = false;
		// the actual function will be called with the current ContextValue as its last argument if marked by contextPrefix
		@Option(gloss = "Formula in the grammar whose name startsWith contextPrefix is context sensitive")
		public String contextPrefix = "context:";
		@Option(gloss = "Reduce verbosity by automatically appending, for example, edu.stanford.nlp.sempre to java calls")
		public String classPathPrefix = ""; // e.g. "edu.stanford.nlp.sempre";
	}

	public static Options opts = new Options();

	private static JavaExecutor defaultExecutor = new JavaExecutor();

	// To simplify logical forms, define some shortcuts.
	private final Map<String, String> shortcuts = Maps.newHashMap();

	public JavaExecutor()
	{
		final String className = BasicFunctions.class.getName();

		shortcuts.put("+", className + ".plus");
		shortcuts.put("-", className + ".minus");
		shortcuts.put("*", className + ".times");
		shortcuts.put("/", className + ".divide");
		shortcuts.put("%", className + ".mod");
		shortcuts.put("!", className + ".not");

		shortcuts.put("<", className + ".lessThan");
		shortcuts.put("<=", className + ".lessThanEq");
		shortcuts.put("==", className + ".equals");
		shortcuts.put(">", className + ".greaterThan");
		shortcuts.put(">=", className + ".greaterThanEq");

		shortcuts.put("if", className + ".ifThenElse");
		shortcuts.put("map", className + ".map");
		shortcuts.put("reduce", className + ".reduce");
		shortcuts.put("select", className + ".select");
		shortcuts.put("range", className + ".range");
	}

	public static class BasicFunctions
	{
		public static double plus(final double x, final double y)
		{
			return x + y;
		}

		public static int plus(final int x, final int y)
		{
			return x + y;
		}

		public static int minus(final int x, final int y)
		{
			return x - y;
		}

		public static double minus(final double x, final double y)
		{
			return x - y;
		}

		public static int times(final int x, final int y)
		{
			return x * y;
		}

		public static double times(final double x, final double y)
		{
			return x * y;
		}

		public static int divide(final int x, final int y)
		{
			return x / y;
		}

		public static double divide(final double x, final double y)
		{
			return x / y;
		}

		public static int mod(final int x, final int y)
		{
			return x % y;
		}

		public static boolean not(final boolean x)
		{
			return !x;
		}

		public static boolean lessThan(final double x, final double y)
		{
			return x < y;
		}

		public static boolean lessThanEq(final double x, final double y)
		{
			return x <= y;
		}

		public static boolean equals(final double x, final double y)
		{
			return x == y;
		}

		public static boolean greaterThan(final double x, final double y)
		{
			return x > y;
		}

		public static boolean greaterThanEq(final double x, final double y)
		{
			return x >= y;
		}

		public static Object ifThenElse(final boolean b, final Object x, final Object y)
		{
			return b ? x : y;
		}

		// For very simple string concatenation
		public static String plus(final String a, final String b)
		{
			return a + b;
		}

		public static String plus(final String a, final String b, final String c)
		{
			return a + b + c;
		}

		public static String plus(final String a, final String b, final String c, final String d)
		{
			return a + b + c + d;
		}

		public static String plus(final String a, final String b, final String c, final String d, final String e)
		{
			return a + b + c + d + e;
		}

		public static String plus(final String a, final String b, final String c, final String d, final String e, final String f)
		{
			return a + b + c + d + e + f;
		}

		public static String plus(final String a, final String b, final String c, final String d, final String e, final String f, final String g)
		{
			return a + b + c + d + e + f + g;
		}

		public static String plus(final String a, final String b, final String c, final String d, final String e, final String f, final String g, final String h)
		{
			return a + b + c + d + e + f + g + h;
		}

		public static String plus(final String a, final String b, final String c, final String d, final String e, final String f, final String g, final String h, final String i)
		{
			return a + b + c + d + e + f + g + h + i;
		}

		public static String plus(final String a, final String b, final String c, final String d, final String e, final String f, final String g, final String h, final String i, final String j)
		{
			return a + b + c + d + e + f + g + h + i + j;
		}

		public static String plus(final String a, final String b, final String c, final String d, final String e, final String f, final String g, final String h, final String i, final String j, final String k)
		{
			return a + b + c + d + e + f + g + h + i + j + k;
		}

		public static String plus(final String a, final String b, final String c, final String d, final String e, final String f, final String g, final String h, final String i, final String j, final String k, final String l)
		{
			return a + b + c + d + e + f + g + h + i + j + k + l;
		}

		public static String plus(final String a, final String b, final String c, final String d, final String e, final String f, final String g, final String h, final String i, final String j, final String k, final String l, final String m)
		{
			return a + b + c + d + e + f + g + h + i + j + k + l + m;
		}

		public static String plus(final String a, final String b, final String c, final String d, final String e, final String f, final String g, final String h, final String i, final String j, final String k, final String l, final String m, final String n)
		{
			return a + b + c + d + e + f + g + h + i + j + k + l + m + n;
		}

		public static String plus(final String a, final String b, final String c, final String d, final String e, final String f, final String g, final String h, final String i, final String j, final String k, final String l, final String m, final String n, final String o)
		{
			return a + b + c + d + e + f + g + h + i + j + k + l + m + n + o;
		}

		private static String toString(final Object x)
		{
			if (x instanceof String)
				return (String) x;
			else
				if (x instanceof Value)
					return x instanceof NameValue ? ((NameValue) x)._id : ((StringValue) x).value;
				else
					return null;
		}

		// Apply func to each element of |list| and return the resulting list.
		public static List<Object> map(final List<Object> list, final LambdaFormula func)
		{
			final List<Object> newList = new ArrayList<>();
			for (final Object elem : list)
			{
				final Object newElem = apply(func, elem);
				newList.add(newElem);
			}
			return newList;
		}

		// list = [3, 5, 2], func = (lambda x (lambda y (call + (var x) (var y))))
		// Returns (3 + 5) + 2 = 10
		public static Object reduce(final List<Object> list, final LambdaFormula func)
		{
			if (list.size() == 0)
				return null;
			Object x = list.get(0);
			for (int i = 1; i < list.size(); i++)
				x = apply(func, x, list.get(i));
			return x;
		}

		// Return elements x of |list| such that func(x) is true.
		public static List<Object> select(final List<Object> list, final LambdaFormula func)
		{
			final List<Object> newList = new ArrayList<>();
			for (final Object elem : list)
			{
				final Object test = apply(func, elem);
				if ((Boolean) test)
					newList.add(elem);
			}
			return newList;
		}

		private static Object apply(final LambdaFormula func, final Object x)
		{
			// Apply the function func to x.  In order to do that, need to convert x into a value.
			final Formula formula = Formulas.lambdaApply(func, new ValueFormula<>(toValue(x)));
			return defaultExecutor.processFormula(formula, null);
		}

		private static Object apply(final LambdaFormula func, final Object x, final Object y)
		{
			// Apply the function func to x and y.  In order to do that, need to convert x into a value.
			Formula formula = Formulas.lambdaApply(func, new ValueFormula<>(toValue(x)));
			formula = Formulas.lambdaApply((LambdaFormula) formula, new ValueFormula<>(toValue(y)));
			return defaultExecutor.processFormula(formula, null);
		}

		public static List<Integer> range(final int start, final int end)
		{
			final List<Integer> result = new ArrayList<>();
			for (int i = start; i < end; i++)
				result.add(i);
			return result;
		}
	}

	public Response execute(Formula formula, final ContextValue context)
	{
		// We can do beta reduction here since macro substitution preserves the
		// denotation (unlike for lambda DCS).
		formula = Formulas.betaReduction(formula);
		try
		{
			return new Response(toValue(processFormula(formula, context)));
		}
		catch (final Exception e)
		{
			// Comment this out if we expect lots of innocuous type checking failures
			if (opts.printStackTrace)
				e.printStackTrace();
			return new Response(ErrorValue.badJava(e.toString()));
		}
	}

	private Object processFormula(final Formula formula, final ContextValue context)
	{
		if (formula instanceof ValueFormula) // Unpack value and convert to object (e.g., for ints)
			return toObject(((ValueFormula) formula).value);

		if (formula instanceof CallFormula)
		{ // Invoke the function.
			// Recurse
			final CallFormula call = (CallFormula) formula;
			final Object func = processFormula(call.func, context);
			final List<Object> args = Lists.newArrayList();
			for (final Formula arg : call.args)
				args.add(processFormula(arg, context));

			if (!(func instanceof NameValue))
				throw new RuntimeException("Invalid func: " + call.func + " => " + func);

			String id = ((NameValue) func)._id;
			if (id.indexOf(opts.contextPrefix) != -1)
			{
				args.add(context);
				id = id.replace(opts.contextPrefix, "");
			}
			id = MapUtils.get(shortcuts, id, id);

			// classPathPrefix, like edu.stanford.nlp.sempre.interactive
			if (!Strings.isNullOrEmpty(opts.classPathPrefix) && !id.startsWith(".") && !id.startsWith(opts.classPathPrefix))
				id = opts.classPathPrefix + "." + id;

			if (id.startsWith(".")) // Instance method
				return invoke(id.substring(1), args.get(0), args.subList(1, args.size()).toArray(new Object[0]));

			else // Static method
				return invoke(id, null, args.toArray(new Object[0]));
		}

		// Just pass it through...
		return formula;
	}

	// Convert the Object back to a Value
	private static Value toValue(final Object obj)
	{
		if (obj instanceof Value)
			return (Value) obj;
		if (obj instanceof Boolean)
			return new BooleanValue((Boolean) obj);
		if (obj instanceof Integer)
			return new NumberValue(((Integer) obj).intValue());
		if (obj instanceof Double)
			return new NumberValue(((Double) obj).doubleValue());
		if (obj instanceof String)
			return new StringValue((String) obj);
		if (obj instanceof List)
		{
			final List<Value> list = Lists.newArrayList();
			for (final Object elem : (List) obj)
				list.add(toValue(elem));
			return new ListValue(list);
		}
		throw new RuntimeException("Unhandled object: " + obj + " with class " + obj.getClass());
	}

	// Convert a Value (which are specified in the formulas) to an Object (which
	// many Java functions take).
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

	// Example: id = "Math.cos"
	private Object invoke(final String id, final Object thisObj, final Object[] args)
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
			final int cost = typeCastCost(m.getParameterTypes(), args);
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
			cost += typeCastCost(types[i], args[i]);
			if (cost >= INVALID_TYPE_COST)
				// LogInfo.dbgs("NOT COMPATIBLE: want %s, got %s with type %s", types[i], args[i], args[i].getClass());
				break;
		}
		return cost;
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
