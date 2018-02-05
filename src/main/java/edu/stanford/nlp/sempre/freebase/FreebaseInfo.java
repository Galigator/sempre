package edu.stanford.nlp.sempre.freebase;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import edu.stanford.nlp.sempre.CanonicalNames;
import edu.stanford.nlp.sempre.Formula;
import edu.stanford.nlp.sempre.Formulas;
import edu.stanford.nlp.sempre.JoinFormula;
import edu.stanford.nlp.sempre.NameValue;
import edu.stanford.nlp.sempre.NumberValue;
import edu.stanford.nlp.sempre.SemTypeHierarchy;
import edu.stanford.nlp.sempre.Value;
import edu.stanford.nlp.sempre.ValueFormula;
import edu.stanford.nlp.sempre.freebase.FbFormulasInfo.BinaryFormulaInfo;
import edu.stanford.nlp.sempre.freebase.FbFormulasInfo.UnaryFormulaInfo;
import fig.basic.IOUtils;
import fig.basic.LispTree;
import fig.basic.LogInfo;
import fig.basic.MapUtils;
import fig.basic.Option;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Class for keeping info from Freebase schema
 * 
 * @author jonathanberant
 */
public final class FreebaseInfo
{
	private static FreebaseInfo singleton;

	public static FreebaseInfo getSingleton()
	{
		if (singleton == null)
			singleton = new FreebaseInfo();
		return singleton;
	}

	public static class Options
	{
		@Option(gloss = "ttl file with schema information")
		public String schemaPath = "lib/fb_data/93.exec/schema2.ttl";
	}

	public static Options opts = new Options();

	// any
	// - number (boolean, int, float, date)
	// - text
	// - entity (people, loc, org, ...)
	// - cvt

	// Concrete primitive types
	public static final String BOOLEAN = CanonicalNames.BOOLEAN;
	public static final String INT = CanonicalNames.INT;
	public static final String FLOAT = CanonicalNames.FLOAT;
	public static final String DATE = CanonicalNames.DATE;
	public static final String TEXT = CanonicalNames.TEXT;
	public static final String NUMBER = CanonicalNames.NUMBER;
	public static final String ENTITY = CanonicalNames.ENTITY;
	public static final String ANY = CanonicalNames.ANY;

	// Common entity types
	public static final String PERSON = "fb:people.person";

	// Non-standard abstract types
	public static final String CVT = "fb:type.cvt";

	// Common relations
	public static final String TYPE = CanonicalNames.TYPE;
	public static final String NAME = CanonicalNames.NAME;
	public static final String PROF = "fb:people.person.profession";
	public static final String ALIAS = "fb:common.topic.alias";

	// mapping from master property to its opposite (e.g., fb:people.person.place_of_birth => fb:location.location.people_born_here)
	private final BiMap<String, String> masterToOppositeMap = HashBiMap.create();

	private final Set<String> cvts = new HashSet<>();
	private final Map<String, String> type1Map = new HashMap<>(); // property => type of arg1
	private final Map<String, String> type2Map = new HashMap<>(); // property => type of arg2
	private final Map<String, String> unit2Map = new HashMap<>(); // property => unit of arg2 (if exists)
	private final Map<String, List<String>> bDescriptionsMap = new HashMap<>(); // property => descriptions
	private final Map<String, Integer> bPopularityMap = new HashMap<>(); // property => popularity
	// unary maps
	private final Map<String, Integer> professionPopularityMap = new HashMap<>(); // property => popularity
	private final Map<String, Integer> typePopularityMap = new HashMap<>(); // property => popularity
	private final Map<String, List<String>> professionDescriptionsMap = new HashMap<>(); // property => descriptions
	private final Map<String, List<String>> typeDescriptionsMap = new HashMap<>(); // property => descriptions

	private final Map<String, String> nameMap = new HashMap<>(); // id => name of id

	public String getArg1Type(final String property)
	{
		return type1Map.get(property);
	}

	public String getArg2Type(final String property)
	{
		return type2Map.get(property);
	}

	private FreebaseInfo()
	{
		try
		{
			readSchema();
		}
		catch (final NumberFormatException e)
		{
			throw new RuntimeException(e);
		}
		catch (final IOException e)
		{
			throw new RuntimeException(e);
		}
	}

	/**
	 * Go over schema twice - once to populate all fields except descriptions, the second time we populate descriptions after we now what are the properties we
	 * are interested in
	 * 
	 * @throws NumberFormatException
	 * @throws IOException
	 */
	public void readSchema() throws IOException
	{
		LogInfo.begin_track("Loading Freebase schema: %s", opts.schemaPath);
		BufferedReader in = IOUtils.openInHard(opts.schemaPath);

		// Include mediator types
		SemTypeHierarchy.singleton.addSupertype(CVT, CVT);
		SemTypeHierarchy.singleton.addSupertype(CVT, ANY);

		String line;
		while ((line = in.readLine()) != null)
		{
			final String[] tokens = edu.stanford.nlp.sempre.freebase.Utils.parseTriple(line);
			if (tokens == null)
				continue;
			final String arg1 = tokens[0];
			final String property = tokens[1];
			final String arg2 = tokens[2];

			if (property.equals("fb:type.property.reverse_property"))
			{ // reverse_property => opposite_property
				// Duplicates logically really shouldn't happen but the Freebase RDF
				// reverse properties are not 1:1.  We should monitor this and make
				// sure we don't lose any alignments.
				if (masterToOppositeMap.containsKey(arg1))
					// LogInfo.errors("arg1 exists multiple times: %s", line);
					continue;
				if (masterToOppositeMap.inverse().containsKey(arg2))
					// LogInfo.errors("arg2 exists multiple times: %s", line);
					continue;
				masterToOppositeMap.put(arg1, arg2);
			}
			else
				if (property.equals("fb:freebase.type_hints.included_types"))
				{ // included_types => supertypes
					SemTypeHierarchy.singleton.addSupertype(arg1, arg2);
					SemTypeHierarchy.singleton.addEntitySupertypes(arg1);
					SemTypeHierarchy.singleton.addEntitySupertypes(arg2);
				}
				else
					if (property.equals("fb:freebase.type_hints.mediator"))
					{ // mediator => cvt
						if (arg2.equals("\"true\"^^xsd:boolean"))
							cvts.add(arg1);
						else
							if (arg2.equals("\"false\"^^xsd:boolean"))
								cvts.remove(arg1);
							else
								throw new RuntimeException("Invalid xsd:boolean: " + arg2);
					}
					else
						if (property.equals("fb:type.property.schema"))
						{ // schema => type1
							if (type1Map.containsKey(arg1))
								LogInfo.errors("%s already has type1 %s, assigning %s", arg1, type1Map.get(arg1), arg2);
							type1Map.put(arg1, arg2);
						}
						else
							if (property.equals("fb:type.property.expected_type"))
							{ // expected_type => type2
								if (type2Map.containsKey(arg1))
									LogInfo.errors("%s already has type2 %s, assigning %s", arg1, type2Map.get(arg1), arg2);
								type2Map.put(arg1, arg2);
							}
							else
								if (property.equals("fb:type.property.unit"))
									unit2Map.put(arg1, arg2);
								else
									if (property.equals("fb:user.custom.type.property.num_instances"))
										bPopularityMap.put(arg1, edu.stanford.nlp.sempre.freebase.Utils.parseInt(arg2));
									else
										if (property.equals("fb:user.custom.people.person.profession.num_instances"))
											professionPopularityMap.put(arg1, edu.stanford.nlp.sempre.freebase.Utils.parseInt(arg2));
										else
											if (property.equals("fb:user.custom.type.object.type.num_instances"))
												typePopularityMap.put(arg1, edu.stanford.nlp.sempre.freebase.Utils.parseInt(arg2));
		}
		in.close();

		// Second iteration - populate descriptions assumes all properties have the fb:type.property.num_instances field
		in = IOUtils.openInHard(opts.schemaPath);
		while ((line = in.readLine()) != null)
		{
			final String[] tokens = edu.stanford.nlp.sempre.freebase.Utils.parseTriple(line);
			if (tokens == null)
				continue;
			final String arg1 = tokens[0];
			final String property = tokens[1];
			final String arg2 = tokens[2];

			if (property.equals(NAME) || property.equals(ALIAS))
				if (bPopularityMap.containsKey(arg1))
					MapUtils.addToList(bDescriptionsMap, arg1, edu.stanford.nlp.sempre.freebase.Utils.parseStr(arg2).toLowerCase());
				else
					if (professionPopularityMap.containsKey(arg1))
						MapUtils.addToList(professionDescriptionsMap, arg1, edu.stanford.nlp.sempre.freebase.Utils.parseStr(arg2).toLowerCase());
					else
						if (typePopularityMap.containsKey(arg1))
							MapUtils.addToList(typeDescriptionsMap, arg1, edu.stanford.nlp.sempre.freebase.Utils.parseStr(arg2).toLowerCase());

			if (property.equals(NAME))
				nameMap.put(arg1, edu.stanford.nlp.sempre.freebase.Utils.parseStr(arg2));
		}
		LogInfo.logs("%d CVTs, (%d,%d) property types, %d property units", cvts.size(), type1Map.size(), type2Map.size(), unit2Map.size());
		LogInfo.end_track();
	}

	public Map<Formula, BinaryFormulaInfo> createBinaryFormulaInfoMap()
	{

		final Map<Formula, FbFormulasInfo.BinaryFormulaInfo> res = new HashMap<>();
		for (final String property : bPopularityMap.keySet())
		{
			final Formula f = Formulas.fromLispTree(LispTree.proto.parseFromString(property));
			final BinaryFormulaInfo info = new BinaryFormulaInfo(f, type1Map.get(property), type2Map.get(property), unit2Map.get(property), "", bDescriptionsMap.get(property), bPopularityMap.get(property));
			if (!info.isComplete())
				continue;
			res.put(f, info);
		}
		return res;
	}

	public Map<Formula, UnaryFormulaInfo> createUnaryFormulaInfoMap()
	{

		final Map<Formula, FbFormulasInfo.UnaryFormulaInfo> res = new HashMap<>();
		// professions
		for (final String profession : professionPopularityMap.keySet())
		{
			final Formula f = new JoinFormula(PROF, new ValueFormula<Value>(new NameValue(profession)));
			final UnaryFormulaInfo info = new UnaryFormulaInfo(f, professionPopularityMap.get(profession), MapUtils.get(professionDescriptionsMap, profession, new LinkedList<String>()), Collections.singleton(PERSON));
			if (!info.isComplete())
				continue;
			res.put(f, info);
		}
		// types
		for (final String type : typePopularityMap.keySet())
		{
			final Formula f = new JoinFormula(TYPE, new ValueFormula<Value>(new NameValue(type)));
			final UnaryFormulaInfo info = new UnaryFormulaInfo(f, typePopularityMap.get(type), MapUtils.get(typeDescriptionsMap, type, new LinkedList<String>()), Collections.singleton(type));
			if (!info.isComplete())
				continue;
			res.put(f, info);
		}
		return res;
	}

	// fb:people.person.place_of_birth => true
	public boolean propertyHasOpposite(final String property)
	{
		return masterToOppositeMap.containsKey(property) || masterToOppositeMap.inverse().containsKey(property);
	}

	// fb:people.person.place_of_birth => fb:location.location.people_born_here
	public String getOppositeFbProperty(final String property)
	{
		if (masterToOppositeMap.containsKey(property))
			return masterToOppositeMap.get(property);
		if (masterToOppositeMap.inverse().containsKey(property))
			return masterToOppositeMap.inverse().get(property);
		throw new RuntimeException("Property does not have an opposite: " + property);
	}

	public String getUnit1(final String property)
	{
		return typeToUnit(type1Map.get(property), property);
	}

	public String getUnit2(final String property)
	{
		return typeToUnit(type2Map.get(property), property);
	}

	// Get the measurement unit associated with this type.
	// If something is not a number, then return something crude (e.g. fb:type.cvt).
	// Return null if we don't know anything.
	public String typeToUnit(final String type, final String property)
	{
		if (type == null)
			// LogInfo.errors("No type information for property: %s", property);
			return null;
		if (type.equals(INT) || type.equals(FLOAT))
		{
			final String unit = unit2Map.get(property);
			if (unit == null)
				// LogInfo.errors("No unit information for property: %s", property);
				return NumberValue.unitless;
			return unit;
		}
		if (type.equals(BOOLEAN) || type.equals(TEXT) || type.equals(DATE)) // Use the type as the unit
			return type;
		if (isCvt(type))
			return CVT; // CVT
		return ENTITY; // Entity
	}

	public boolean isCvt(final String type)
	{
		return cvts.contains(type);
	}

	public String getPropertyName(final String property)
	{
		final List<String> names = bDescriptionsMap.get(property);
		if (names == null)
			return null;
		return names.get(0);
	}

	public String getName(final String id)
	{
		return nameMap.get(id);
	}

	public static boolean isReverseProperty(final String property)
	{
		return CanonicalNames.isReverseProperty(property);
	}

	public static String reverseProperty(final String property)
	{
		return CanonicalNames.reverseProperty(property);
	}

	// fb:en.barack_obama => http://rdf.freebase.com/ns/en/barack_obama
	public static final String freebaseNamespace = "http://rdf.freebase.com/ns/";

	public static String id2uri(final String id)
	{
		assert id.startsWith("fb:") : id;
		return freebaseNamespace + id.substring(3).replaceAll("\\.", "/");
	}

	public static String uri2id(final String uri)
	{
		if (!uri.startsWith(freebaseNamespace))
		{
			LogInfo.logs("Warning: invalid Freebase uri: %s", uri);
			// Don't do any conversion; this is not necessarily the best thing to do.
			return uri;
		}
		return "fb:" + uri.substring(freebaseNamespace.length()).replaceAll("/", ".");
	}
}
