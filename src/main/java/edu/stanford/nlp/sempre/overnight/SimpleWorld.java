package edu.stanford.nlp.sempre.overnight;

import com.google.common.collect.Lists;
import edu.stanford.nlp.sempre.BooleanValue;
import edu.stanford.nlp.sempre.DateValue;
import edu.stanford.nlp.sempre.ListValue;
import edu.stanford.nlp.sempre.NameValue;
import edu.stanford.nlp.sempre.NumberValue;
import edu.stanford.nlp.sempre.StringValue;
import edu.stanford.nlp.sempre.TimeValue;
import edu.stanford.nlp.sempre.Value;
import fig.basic.IOUtils;
import fig.basic.LogInfo;
import fig.basic.MapUtils;
import fig.basic.Option;
import fig.basic.Pair;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Functions for supporting a simple database. This is very inefficient and it works only for small worlds. Example applications: calendar, blocks world Types:
 * DateValue, TimeValue, Value All arguments are lists of Values.
 *
 * @author Jonathan Berant
 * @author Yushi Wang
 */
public final class SimpleWorld
{
	public static class Options
	{
		@Option(gloss = "Number of entity samples")
		public int numOfValueSamples = 60;
		@Option(gloss = "Domain specifies which predicates/entities exist in the world")
		public String domain;
		@Option(gloss = "Verbosity")
		public int verbose = 0;
		@Option(gloss = "Path to load up the DB from (triples)")
		public String dbPath = null;
		@Option(gloss = "When performing a join with getProperty, do we want to deduplicate?")
		public boolean joinDedup = true;
	}

	public static Options opts = new Options();

	private SimpleWorld()
	{
	}

	// en.person.alice => en.person
	private static String extractType(String id)
	{
		int i = id.lastIndexOf('.');
		if (id.charAt(i + 1) == '_')
		{ //to deal with /fb:en.lake._st_clair and such
			id = id.substring(0, i);
			i = id.lastIndexOf('.');
			return id.substring(0, i);
		}
		else
			return id.substring(0, i);
	}

	private static String getType(final Value v)
	{
		if (v instanceof NumberValue)
		{
			final String unit = ((NumberValue) v)._unit;
			return unit + "_number"; // So we can quickly tell if something is a number or not
		}
		else
			if (v instanceof DateValue)
				return "en.date";
			else
				if (v instanceof TimeValue)
					return "en.time";
				else
					if (v instanceof BooleanValue)
						return "en.boolean";
					else
						if (v instanceof NameValue)
							return extractType(((NameValue) v)._id);
						else
							if (v instanceof ListValue)
								return getType(((ListValue) v).values.get(0));
							else
								throw new RuntimeException("Can't get type of value " + v);
	}

	// Make sure that the types of the objects in the two lists are the same
	private static void checkTypeMatch(final List<Value> l1, final List<Value> l2)
	{
		for (final Value o1 : l1)
			for (final Value o2 : l2)
				if (!getType(o1).equals(getType(o2)))
					throw new RuntimeException("Intersecting objects with non-matching types, object 1: " + o1 + ", object2: " + o2);
	}

	private static <T> boolean intersects(final List<T> l1, final List<T> l2)
	{
		//optimization
		if (l1.size() < l2.size())
		{
			for (final T o1 : l1)
				if (l2.contains(o1))
					return true;
		}
		else
			for (final T o2 : l2)
				if (l1.contains(o2))
					return true;
		return false;
	}

	// Modes of superlatives
	private static final String MIN = "min";
	private static final String MAX = "max";

	////////////////////////////////////////////////////////////
	// Methods exposed to the public.

	public static List<Value> singleton(final Value value)
	{
		return Collections.singletonList(value);
	}

	// Return the set of entities who can be the first argument of property
	public static List<Value> domain(final String property)
	{
		createWorld();
		final String type1 = propertyToType1.get(property);
		if (type1 == null)
			throw new RuntimeException("Property " + property + " has no type1");
		return getProperty(singleton(new NameValue(type1)), reverse("type"));
	}

	public static String reverse(final String property)
	{
		if (property.startsWith("!"))
			return property.substring(1);
		return "!" + property;
	}

	// Return the concatenation of two lists.
	public static List<Value> concat(final Value v1, final Value v2)
	{
		return concat(singleton(v1), singleton(v2));
	}

	public static List<Value> concat(final List<Value> l1, final List<Value> l2)
	{
		checkTypeMatch(l1, l2);
		if (l1.equals(l2)) // Disallow 'alice' or 'alice'
			throw new RuntimeException("Cannot concatenate two copies of the same list: " + l1);
		final List<Value> newList = new ArrayList<>();
		newList.addAll(l1);
		newList.addAll(l2);
		return newList;
	}

	private static void checkType1(final String property, final Value e1)
	{
		final String type1 = propertyToType1.get(property);
		final String type2 = "?";
		if (type1 == null)
			throw new RuntimeException("Property " + property + " has no type1");
		if (!getType(e1).equals(type1))
			throw new RuntimeException("Type check failed: " + property + " : (-> " + type1 + " " + type2 + ") doesn't match arg1 " + e1 + " : " + getType(e1));
	}

	private static void checkType2(final String property, final Value e2)
	{
		final String type1 = "?";
		final String type2 = propertyToType2.get(property);
		if (type2 == null)
			throw new RuntimeException("Property " + property + " has no type2");
		if (!getType(e2).equals(type2))
			throw new RuntimeException("Type check failed: " + property + " : (-> " + type1 + " " + type2 + ") doesn't match arg2 " + e2 + " : " + getType(e2));
	}

	private static void ensureNonnumericType2(final String property)
	{
		createWorld();
		final String type2 = propertyToType2.get(property);
		if (type2 == null)
			throw new RuntimeException("Property " + property + " has no type2");
		if (type2.endsWith("_number") || type2.equals("en.date") || type2.equals("en.time"))
			throw new RuntimeException("Property " + property + " has numeric type2, which is not allowed");
	}

	public static String ensureNumericProperty(final String property)
	{
		createWorld();
		final String type2 = propertyToType2.get(property);
		if (type2.endsWith("number") || type2.equals("en.date") || type2.equals("en.time"))
			return property;
		throw new RuntimeException("Property " + property + " has non-numeric type2, which is not allowed");
	}

	public static List<Value> ensureNumericEntity(final Value value)
	{
		return ensureNumericEntity(singleton(value));
	}

	public static List<Value> ensureNumericEntity(final List<Value> list)
	{
		createWorld();
		final String type = getType(list.get(0));
		if (type.endsWith("number") || type.equals("en.date") || type.equals("en.time"))
			return list;
		throw new RuntimeException("List " + list + " is non-numeric, which is not allowed");
	}

	// Return the subset of |objects| whose |property| |compare| refValues.
	public static List<Value> filter(final List<Value> entities, final String property)
	{ // Unary properties
		return filter(entities, property, "=", singleton(new BooleanValue(true)));
	}

	public static List<Value> filter(final List<Value> entities, final String property, final String compare, final Value refValue)
	{
		return filter(entities, property, compare, singleton(refValue));
	}

	public static List<Value> filter(final List<Value> entities, final String property, final String compare, final List<Value> refValues)
	{
		final List<Value> newEntities = new ArrayList<>();

		for (final Value v : refValues)
			checkType2(property, v);

		for (final Value obj : entities)
		{
			if (!(obj instanceof NameValue))
				continue;
			final NameValue e = (NameValue) obj;

			final List<Value> values = lookupDB(e, property);
			boolean match = false;

			checkType1(property, e);

			if (compare.equals("="))
				match = intersects(values, refValues);
			else
				if (compare.equals("!="))
					match = !intersects(values, refValues); // Note this is not the existential interpretation!
				else
					if (compare.equals("<"))
						match = getDegree(values, MIN) < getDegree(refValues, MAX);
					else
						if (compare.equals(">"))
							match = getDegree(values, MAX) > getDegree(refValues, MIN);
						else
							if (compare.equals("<="))
								match = getDegree(values, MIN) <= getDegree(refValues, MAX);
							else
								if (compare.equals(">="))
									match = getDegree(values, MAX) >= getDegree(refValues, MIN);
			if (match)
				newEntities.add(e);
		}
		return newEntities;
	}

	private static double getDouble(final Value v)
	{
		if (!(v instanceof NumberValue))
			throw new RuntimeException("Not a number: " + v);
		return ((NumberValue) v)._value;
	}

	// Degree is used to compare (either take the max or min).
	private static double getDegree(final List<Value> values, final String mode)
	{
		double deg = Double.NaN;
		for (final Value v : values)
		{
			final double x = getDegree(v);
			if (Double.isNaN(deg) || (mode.equals(MAX) ? x > deg : x < deg))
				deg = x;
		}
		return deg;
	}

	private static double getDegree(final Value value)
	{
		if (value instanceof TimeValue)
		{
			final TimeValue timeValue = (TimeValue) value;
			return timeValue.hour;
		}
		else
			if (value instanceof DateValue)
			{
				final DateValue dateValue = (DateValue) value;
				double dValue = 0;
				if (dateValue.year != -1)
					dValue += dateValue.year * 10000;
				if (dateValue.month != -1)
					dValue += dateValue.month * 100;
				if (dateValue.day != -1)
					dValue += dateValue.day;
				return dValue;
			}
			else
				if (value instanceof NumberValue)
					return ((NumberValue) value)._value;
				else
					throw new RuntimeException("Can't get degree from " + value);
	}

	// Return the subset of entities that obtain the min/max value of property.
	public static List<Value> superlative(final List<Value> entities, final String mode, final String property)
	{
		List<Value> bestEntities = null;
		double bestDegree = Double.NaN;

		for (final Value e : entities)
		{
			final double degree = getDegree(lookupDB(e, property), mode);
			checkType1(property, e);
			if (bestEntities == null || (mode.equals(MAX) ? degree > bestDegree : degree < bestDegree))
			{
				bestEntities = new ArrayList<>();
				bestEntities.add(e);
				bestDegree = degree;
			}
			else
				if (degree == bestDegree)
					bestEntities.add(e);
		}
		return bestEntities;
	}

	// Return the subset of entities that obtain the most/least number of values of property that fall in restrictors.
	public static List<Value> countSuperlative(final List<Value> entities, final String mode, final String property)
	{
		return countSuperlative(entities, mode, property, null);
	}

	//make sure lists are returned in a unique order
	public static String sortAndToString(final Object obj)
	{
		if (obj instanceof List)
		{
			final List<String> strList = new ArrayList<>();
			final List list = (List) obj;
			for (final Object listObj : list)
				strList.add(listObj.toString());
			Collections.sort(strList);
			return strList.toString();
		}
		return obj.toString();
	}

	public static List<Value> countSuperlative(final List<Value> entities, final String mode, final String property, final List<Value> restrictors)
	{
		List<Value> bestEntities = null;
		double bestDegree = Double.NaN;

		if (restrictors != null)
			for (final Value v : restrictors)
				checkType2(property, v);
		ensureNonnumericType2(property);

		for (final Value e : entities)
		{
			final List<Value> values = lookupDB(e, property);
			double degree = 0;
			for (final Value v : values)
				if (restrictors == null || restrictors.contains(v))
					degree++;

			checkType1(property, e);

			if (bestEntities == null || (mode.equals(MAX) ? degree > bestDegree : degree < bestDegree))
			{
				bestEntities = new ArrayList<>();
				bestEntities.add(e);
				bestDegree = degree;
			}
			else
				if (degree == bestDegree)
					bestEntities.add(e);
		}
		return bestEntities;
	}

	// Return subset of entities that have the number of values of property that meet the mode/threshold criteria (e.g. >= 3).
	public static List<Value> countComparative(final List<Value> entities, final String property, final String mode, final NumberValue thresholdValue)
	{
		return countComparative(entities, property, mode, thresholdValue, null);
	}

	public static List<Value> countComparative(final List<Value> entities, final String property, final String mode, final NumberValue thresholdValue, final List<Value> restrictors)
	{
		final List<Value> newEntities = new ArrayList<>();
		final double threshold = getDouble(thresholdValue);

		if (restrictors != null)
			for (final Value v : restrictors)
				checkType2(property, v);
		ensureNonnumericType2(property);

		for (final Value e : entities)
		{
			final List<Value> values = lookupDB(e, property);
			double degree = 0;
			for (final Value v : values)
				if (restrictors == null || restrictors.contains(v))
					degree++;

			checkType1(property, e);

			switch (mode)
			{
				case "=":
					if (degree == threshold)
						newEntities.add(e);
					break;
				case "<":
					if (degree < threshold)
						newEntities.add(e);
					break;
				case ">":
					if (degree > threshold)
						newEntities.add(e);
					break;
				case "<=":
					if (degree <= threshold)
						newEntities.add(e);
					break;
				case ">=":
					if (degree >= threshold)
						newEntities.add(e);
					break;
				default:
					throw new RuntimeException("Illegal mode: " + mode);
			}
		}
		return newEntities;
	}

	// Return sum of values.
	public static List<Value> sum(final List<Value> values)
	{
		double sum = 0;
		for (final Value v : values)
			sum += getDouble(v);
		return Collections.singletonList(new NumberValue(sum));
	}

	// Return sum/mean/min/max of values.
	public static List<Value> aggregate(final String mode, final List<Value> values)
	{
		// Note: this is probably too strong to reject empty lists.
		if (values.size() == 0)
			throw new RuntimeException("Can't aggregate " + mode + " over empty list");
		double sum = 0;
		for (final Value v : values)
		{
			// Note: we're leaving out dates and times, but sum/avg doesn't quite work with them anyway.
			if (!(v instanceof NumberValue))
				throw new RuntimeException("Can only aggregate over numbers, but got " + v);
			final double x = getDouble(v);
			sum += x;
		}
		double result;
		switch (mode)
		{
			case "avg":
				result = sum / values.size();
				break;
			case "sum":
				result = sum;
				break;
			default:
				throw new RuntimeException("Bad mode: " + mode);
		}
		return Collections.singletonList(new NumberValue(result, ((NumberValue) values.get(0))._unit));
	}

	// Return the properties of the entities (database join).
	public static List<Value> getProperty(final Value inObject, final String property)
	{
		return getProperty(singleton(inObject), property);
	}

	public static List<Value> getProperty(final List<Value> inObjects, final String property)
	{
		final List<Value> outObjects = new ArrayList<>();
		final Set<Value> outObjectsCache = new HashSet<>(); //optimization - run "contains" on set and not list
		for (final Value obj : inObjects)
		{
			final List<Value> values = lookupDB(obj, property);
			checkType1(property, obj);
			for (final Value v : values)
				if (!opts.joinDedup || !outObjectsCache.contains(v))
				{
					outObjects.add(v);
					outObjectsCache.add(v);
				}
		}
		if (outObjects.size() == 0)
			throw new RuntimeException("The property " + property + " does not appear in any of the objects " + inObjects);
		return outObjects;
	}

	private static double arithOp(final String op, final double v1, final double v2)
	{
		switch (op)
		{
			case "+":
				return v1 + v2;
			case "-":
				return v1 - v2;
			case "*":
				return v1 * v2;
			case "/":
				return v1 / v2;
			default:
				throw new RuntimeException("Invalid operation: " + op);
		}
	}

	public static List<Value> arithOp(final String op, final Value v1, final Value v2)
	{
		return singleton(new NumberValue(arithOp(op, getDouble(v1), getDouble(v2))));
	}

	public static List<Value> arithOp(final String op, final List<Value> args1, final List<Value> args2)
	{
		// FUTURE: should pay attention to units
		final List<Value> result = new ArrayList<>();
		for (final Value v1 : args1)
			for (final Value v2 : args2)
				result.addAll(arithOp(op, v1, v2));
		return result;
	}

	////////////////////////////////////////////////////////////
	// Internal state of the world

	private static final Random random = new Random(1);

	private static Set<Value> entities; // Keep track of all the entities
	private static Set<String> properties; // Keep track of all the properties
	private static Map<String, String> propertyToType1, propertyToType2; // types
	private static Map<Pair<Value, String>, List<Value>> database; // Database consists of (e1, property, e2) triples

	public static int sizeofDB()
	{
		return database.size();
	}

	public static List<Value> lookupDB(final Value e, final String property)
	{
		createWorld();
		if (!entities.contains(e))
			throw new RuntimeException("DB doesn't contain entity " + e);
		if (!properties.contains(property))
			throw new RuntimeException("DB doesn't contain property " + property);
		final List<Value> values = database.get(new Pair(e, property));
		if (values == null)
			return Collections.EMPTY_LIST;
		return values;
	}

	private static void insertDB(final Value e1, final String property)
	{ // For unary properties
		insertDB(e1, property, new BooleanValue(true));
	}

	private static void insertDB(final Value e1, final String property, final List<Value> e2s)
	{
		for (final Value e2 : e2s)
			insertDB(e1, property, e2);
	}

	private static void insertDB(final Value e1, final String property, final Value e2)
	{
		//LogInfo.logs("insertDB (%s, %s, %s)", e1, property, e2);
		entities.add(e1);
		properties.add(property);
		properties.add(reverse(property));
		entities.add(e2);
		MapUtils.addToList(database, new Pair(e1, property), e2);
		MapUtils.addToList(database, new Pair(e2, reverse(property)), e1);
		propertyToType1.put(property, getType(e1));
		propertyToType2.put(property, getType(e2));
		propertyToType1.put(reverse(property), getType(e2));
		propertyToType2.put(reverse(property), getType(e1));
	}

	public static void dumpDatabase()
	{
		for (final Pair<Value, String> pair : database.keySet())
			LogInfo.logs("%s %s %s", pair.getFirst(), pair.getSecond(), database.get(pair));
	}

	// Used for testing
	public static void recreateWorld()
	{
		database = null;
		createWorld();
	}

	public static void createWorld()
	{
		if (database != null)
			return;
		entities = new HashSet<>();
		properties = new HashSet<>();
		database = new HashMap<>();
		propertyToType1 = new HashMap<>();
		propertyToType2 = new HashMap<>();

		Domain domain = null;
		switch (opts.domain)
		{
			case "blocks":
				domain = new BlocksDomain();
				break;
			case "calendar":
				domain = new CalendarDomain();
				break;
			case "housing":
				domain = new HousingDomain();
				break;
			case "restaurants":
				domain = new RestaurantsDomain();
				break;
			case "publications":
				domain = new PublicationDomain();
				break;
			case "socialnetwork":
				domain = new SocialNetworkDomain();
				break;
			case "basketball":
				domain = new BasketballDomain();
				break;
			case "recipes":
				domain = new RecipesDomain();
				break;
			case "geo880":
				opts.dbPath = "lib/data/overnight/geo880.db";
				domain = new ExternalDomain();
				break;
			case "external":
				domain = new ExternalDomain();
				break;
			default:
				throw new RuntimeException("Unknown domain: " + opts.domain);
		}
		domain.createEntities(opts.numOfValueSamples);

		// Dump the entire database
		LogInfo.begin_track("SimpleWorld.createWorld: domain = %s (%d entity/property pairs)", opts.domain, database.size());
		if (opts.verbose >= 1)
			dumpDatabase();
		LogInfo.end_track();
	}

	private static <T> List<T> L(final T... list)
	{
		return Arrays.asList(list);
	}

	// Convert the Object back to a Value
	private static Value toValue(final Object obj)
	{
		if (obj instanceof Value)
			return (Value) obj;
		if (obj instanceof Boolean)
			return new BooleanValue((Boolean) obj);
		if (obj instanceof Integer)
			return new NumberValue((Integer) obj, "count");
		if (obj instanceof Double)
			return new NumberValue((Double) obj);
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

	// Convert the Object to list value
	public static ListValue listValue(final Object obj)
	{
		final Value value = toValue(obj);
		if (value instanceof ListValue)
		{
			final ListValue lv = (ListValue) value;
			Collections.sort(lv.values, new Value.ValueComparator());
			return (ListValue) value;
		}
		return new ListValue(singleton(value));
	}

	public static int sampleInt(final int min, final int max)
	{
		return random.nextInt(max - min) + min;
	}

	public static boolean sampleBernoulli(final double prob)
	{
		return random.nextDouble() < prob;
	}

	// Choose a single random element from the list
	public static <T> T sampleMultinomial(final List<T> list)
	{
		return list.get(sampleInt(0, list.size()));
	}

	// Choose n random elements from list (duplicates are possible).
	public static <T> List<T> sampleMultinomial(final List<T> list, final int n)
	{
		final List<T> sublist = new ArrayList<>();
		if (list.size() > 0)
			for (int i = 0; i < n; i++)
				sublist.add(sampleMultinomial(list));
		return sublist;
	}

	// Keep each element with probability |prob|.
	public static <T> List<T> subsample(final List<T> list, final double prob)
	{
		final List<T> sublist = new ArrayList<>();
		for (final T x : list)
			if (sampleBernoulli(prob))
				sublist.add(x);
		return sublist;
	}

	// With high probability, return one of the first few to avoid empty denotations.
	public static <T> T focusSampleMultinomial(final List<T> list)
	{
		if (sampleBernoulli(0.5))
			return list.get(0);
		return list.get(sampleInt(0, list.size()));
	}

	public abstract static class Domain
	{
		public abstract void createEntities(int numEntities);
	}

	// Create |numEntities| entities, the first few have ids.
	private static List<Value> makeValues(final List<String> ids)
	{
		return makeValues(ids.size(), ids);
	}

	private static List<Value> makeValues(final int numEntities, final List<String> ids)
	{
		final List<Value> values = new ArrayList<>();
		final String type = extractType(ids.get(0));
		for (int i = 0; i < numEntities; i++)
			values.add(makeValue(i < ids.size() ? ids.get(i) : type + "." + i, type));
		return values;
	}

	private static Value makeValue(final String id)
	{
		return makeValue(id, extractType(id));
	}

	private static Value makeValue(final String id, final String type)
	{
		final Value e = new NameValue(id);
		if (!entities.contains(e))
			insertDB(e, "type", new NameValue(type));
		return e;
	}

	// All dates for all domains are assumed to be in this range.
	private static DateValue sampleDate()
	{
		return new DateValue(sampleInt(2000, 2010), -1, -1);
	}

	////////////////////////////////////////////////////////////
	// Specific domains (important to synchronize the constants with overnight/<domain>grammar!)

	public static class BlocksDomain extends Domain
	{
		public void createEntities(final int numEntities)
		{
			final List<Value> blocks = makeValues(numEntities, L("en.block.block1", "en.block.block2"));
			final List<Value> shapes = makeValues(L("en.shape.pyramid", "en.shape.cube"));
			final List<Value> colors = makeValues(L("en.color.red", "en.color.green"));
			for (final Value e : blocks)
			{
				insertDB(e, "shape", sampleMultinomial(shapes));
				insertDB(e, "color", sampleMultinomial(colors));
				insertDB(e, "length", new NumberValue(sampleInt(2, 8), "en.inch"));
				insertDB(e, "width", new NumberValue(sampleInt(2, 8), "en.inch"));
				insertDB(e, "height", new NumberValue(sampleInt(2, 8), "en.inch"));
				insertDB(e, "left", sampleMultinomial(blocks));
				insertDB(e, "right", sampleMultinomial(blocks));
				insertDB(e, "above", sampleMultinomial(blocks));
				insertDB(e, "below", sampleMultinomial(blocks));
				if (sampleBernoulli(0.5))
					insertDB(e, "is_special");
			}
		}
	}

	public static class CalendarDomain extends Domain
	{
		private static DateValue sampleDate()
		{
			return new DateValue(2015, 1, sampleInt(1, 5));
		}

		private static TimeValue sampleTime()
		{
			return new TimeValue(sampleInt(9, 16), 0);
		}

		public void createEntities(final int numEntities)
		{
			final List<Value> meetings = makeValues(numEntities, L("en.meeting.weekly_standup", "en.meeting.annual_review"));
			final List<Value> people = makeValues(L("en.person.alice", "en.person.bob"));
			final List<Value> locations = makeValues(L("en.location.greenberg_cafe", "en.location.central_office"));
			for (final Value e : meetings)
			{
				insertDB(e, "date", sampleDate());
				insertDB(e, "start_time", sampleTime());
				insertDB(e, "end_time", sampleTime());
				insertDB(e, "length", new NumberValue(sampleInt(1, 4), "en.hour"));
				insertDB(e, "attendee", sampleMultinomial(people, 2));
				insertDB(e, "location", sampleMultinomial(locations));
				if (sampleBernoulli(0.5))
					insertDB(e, "is_important");
			}
		}
	}

	public static class RestaurantsDomain extends Domain
	{
		public static final List<String> RESTAURANT_VPS = Arrays.asList("reserve,credit,outdoor,takeout,delivery,waiter,kids,groups".split(","));
		public static final List<String> RESTAURANT_CUISINES = Arrays.asList("en.cuisine.thai,en.cuisine.french,en.cuisine.italian".split(","));
		public static final List<String> RESTAURANT_MEALS = Arrays.asList("en.food.breakfast,en.food.lunch,en.food.dinner".split(","));
		public static final List<String> NEIGHBORHOODS = Arrays.asList("en.neighborhood.tribeca,en.neighborhood.midtown_west,en.neighborhood.chelsea".split(","));

		public void createEntities(final int numEntities)
		{
			final List<Value> restaurants = makeValues(numEntities, L("en.restaurant.thai_cafe", "en.restaurant.pizzeria_juno"));
			final List<Value> neighborhoods = makeValues(NEIGHBORHOODS);
			final List<Value> cuisines = makeValues(RESTAURANT_CUISINES);
			final List<Value> meals = makeValues(RESTAURANT_MEALS);
			for (final Value e : restaurants)
			{
				insertDB(e, "star_rating", new NumberValue(sampleInt(0, 6), "en.star"));
				insertDB(e, "price_rating", new NumberValue(sampleInt(1, 5), "en.dollar_sign"));
				insertDB(e, "num_reviews", new NumberValue(sampleInt(20, 60), "en.review"));
				insertDB(e, "neighborhood", sampleMultinomial(neighborhoods));
				insertDB(e, "cuisine", sampleMultinomial(cuisines));
				insertDB(e, "meals", subsample(meals, 0.5));
				for (final String vp : RESTAURANT_VPS)
					if (sampleBernoulli(0.5))
						insertDB(e, vp);
			}
		}
	}

	public static class HousingDomain extends Domain
	{
		public static final List<String> HOUSING_VPS = Arrays.asList("allows_cats,allows_dogs,has_private_bath,has_private_room".split(","));
		public static final List<String> HOUSING_TYPES = Arrays.asList("en.housing.apartment,en.housing.condo,en.housing.house,en.housing.flat".split(","));
		public static final List<String> NEIGHBORHOODS = Arrays.asList("en.neighborhood.tribeca,en.neighborhood.midtown_west,en.neighborhood.chelsea".split(","));

		public void createEntities(final int numEntities)
		{
			final List<Value> units = makeValues(numEntities, L("en.housing_unit.123_sesame_street", "en.housing_unit.900_mission_ave"));
			final List<Value> housingTypes = makeValues(HOUSING_TYPES);
			final List<Value> neighborhoods = makeValues(NEIGHBORHOODS);
			for (final Value e : units)
			{
				insertDB(e, "rent", new NumberValue(sampleMultinomial(L(1500, sampleInt(1000, 3000))), "en.dollar"));
				insertDB(e, "size", new NumberValue(sampleMultinomial(L(800, sampleInt(500, 1500))), "en.square_feet"));
				insertDB(e, "posting_date", sampleDate());
				insertDB(e, "neighborhood", sampleMultinomial(neighborhoods));
				insertDB(e, "housing_type", sampleMultinomial(housingTypes));
				for (final String vp : HOUSING_VPS)
					if (sampleBernoulli(0.5))
						insertDB(e, vp);
			}
		}
	}

	public static class PublicationDomain extends Domain
	{
		public void createEntities(final int numEntities)
		{
			final List<Value> articles = makeValues(numEntities, L("en.article.multivariate_data_analysis"));
			final List<Value> people = makeValues(L("en.person.efron", "en.person.lakoff"));
			final List<Value> venues = makeValues(L("en.venue.computational_linguistics", "en.venue.annals_of_statistics"));
			for (final Value e : articles)
			{
				insertDB(e, "author", sampleMultinomial(people, 2));
				insertDB(e, "venue", sampleMultinomial(venues));
				insertDB(e, "publication_date", sampleDate());
				insertDB(e, "cites", sampleMultinomial(articles, sampleInt(1, 10)));
				insertDB(e, "won_award");
			}
		}
	}

	public static class SocialNetworkDomain extends Domain
	{
		public void createEntities(final int numEntities)
		{
			final List<Value> people = makeValues(numEntities, L("en.person.alice", "en.person.bob"));
			final List<Value> genders = makeValues(L("en.gender.male", "en.gender.female"));
			final List<Value> relationshipStatuses = makeValues(L("en.relationship_status.single", "en.relationship_status.married"));
			final List<Value> cities = makeValues(L("en.city.new_york", "en.city.beijing"));
			final List<Value> universities = makeValues(L("en.university.brown", "en.university.berkeley", "en.university.ucla"));
			final List<Value> fields = makeValues(L("en.field.computer_science", "en.field.economics", "en.field.history"));
			final List<Value> companies = makeValues(L("en.company.google", "en.company.mckinsey", "en.company.toyota"));
			final List<Value> jobTitles = makeValues(L("en.job_title.ceo", "en.job_title.software_engineer", "en.job_title.program_manager"));
			for (final Value e : people)
			{
				insertDB(e, "gender", sampleMultinomial(genders));
				insertDB(e, "relationship_status", sampleMultinomial(relationshipStatuses));
				insertDB(e, "height", new NumberValue(sampleInt(150, 210), "en.cm"));
				insertDB(e, "birthdate", sampleDate());
				insertDB(e, "birthplace", sampleMultinomial(cities));
				insertDB(e, "friend", sampleMultinomial(people, sampleInt(0, 3)));
				if (sampleBernoulli(0.5))
					insertDB(e, "logged_in");
			}
			for (final Value e : makeValues(numEntities, L("en.education.0")))
			{
				insertDB(e, "student", focusSampleMultinomial(people));
				insertDB(e, "university", sampleMultinomial(universities));
				insertDB(e, "field_of_study", sampleMultinomial(fields));
				insertDB(e, "education_start_date", sampleDate());
				insertDB(e, "education_end_date", sampleDate());
			}
			for (final Value e : makeValues(numEntities, L("en.employment.0")))
			{
				insertDB(e, "employee", focusSampleMultinomial(people));
				insertDB(e, "employer", sampleMultinomial(companies));
				insertDB(e, "job_title", sampleMultinomial(jobTitles));
				insertDB(e, "employment_start_date", sampleDate());
				insertDB(e, "employment_end_date", sampleDate());
			}
		}
	}

	public static class BasketballDomain extends Domain
	{
		public void createEntities(final int numEntities)
		{
			final List<Value> players = makeValues(numEntities, L("en.player.kobe_bryant", "en.player.lebron_james"));
			final List<Value> teams = makeValues(L("en.team.lakers", "en.team.cavaliers"));
			final List<Value> positions = makeValues(L("en.position.point_guard", "en.position.forward"));
			for (final Value e : makeValues(numEntities, L("en.stats.0")))
			{
				insertDB(e, "player", focusSampleMultinomial(players));
				insertDB(e, "position", sampleMultinomial(positions));
				insertDB(e, "team", sampleMultinomial(teams));
				insertDB(e, "season", sampleDate());

				insertDB(e, "num_points", new NumberValue(sampleInt(0, 10), "point"));
				insertDB(e, "num_assists", new NumberValue(sampleInt(0, 10), "assist"));
				insertDB(e, "num_steals", new NumberValue(sampleInt(0, 10), "steal"));
				insertDB(e, "num_turnovers", new NumberValue(sampleInt(0, 10), "turnover"));
				insertDB(e, "num_rebounds", new NumberValue(sampleInt(0, 10), "rebound"));
				insertDB(e, "num_blocks", new NumberValue(sampleInt(0, 10), "block"));
				insertDB(e, "num_fouls", new NumberValue(sampleInt(0, 10), "foul"));
				insertDB(e, "num_games_played", new NumberValue(sampleInt(0, 10), "game"));
			}
		}
	}

	public static class RecipesDomain extends Domain
	{
		public void createEntities(final int numEntities)
		{
			final List<Value> recipes = makeValues(numEntities, L("en.recipe.rice_pudding", "en.recipe.quiche"));
			final List<Value> cuisines = makeValues(L("en.cuisine.chinese", "en.cuisine.french"));
			final List<Value> ingredients = makeValues(L("en.ingredient.milk", "en.ingredient.spinach"));
			final List<Value> meals = makeValues(L("en.meal.lunch", "en.meal.dinner"));
			for (final Value e : recipes)
			{
				insertDB(e, "preparation_time", new NumberValue(sampleInt(5, 30), "en.minute"));
				insertDB(e, "cooking_time", new NumberValue(sampleInt(5, 30), "en.minute"));
				insertDB(e, "cuisine", sampleMultinomial(cuisines));
				insertDB(e, "requires", sampleMultinomial(ingredients));
				insertDB(e, "meal", sampleMultinomial(meals));
				insertDB(e, "posting_date", sampleDate());
			}
		}
	}

	// Domain that corresponds to reading from a file
	public static class ExternalDomain extends Domain
	{
		public void createEntities(final int numEntities)
		{
			LogInfo.begin_track("ExternalDomain.createEntities: %s", opts.dbPath);
			// Load up from database
			for (final String line : IOUtils.readLinesHard(opts.dbPath))
			{
				final String[] tokens = line.split("\t");
				final String pred = tokens[0];
				final Value e = makeValue(tokens[1]);
				if (tokens.length == 2)
					insertDB(e, pred);
				else
					if (tokens.length == 3)
					{ // Binary
						Value f;
						if (tokens[2].startsWith("fb:en.")) // Named entity
							f = makeValue(tokens[2]);
						else
							f = Value.fromString(tokens[2]); // Number
						insertDB(e, pred, f);
					}
					else
						throw new RuntimeException("Unhandled: " + line);
			}
			LogInfo.end_track();
		}
	}
}
