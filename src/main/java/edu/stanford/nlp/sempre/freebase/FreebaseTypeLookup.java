package edu.stanford.nlp.sempre.freebase;

import edu.stanford.nlp.sempre.SemType;
import edu.stanford.nlp.sempre.SemTypeHierarchy;
import edu.stanford.nlp.sempre.TypeLookup;
import edu.stanford.nlp.sempre.cache.StringCache;
import edu.stanford.nlp.sempre.cache.StringCacheUtils;
import fig.basic.Option;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Provides types of Freebase entities and properties. For entities, look them up (requires access to the cache file). For properties, just look them up in the
 * FreebaseInfo schema.
 */
public class FreebaseTypeLookup implements TypeLookup
{
	public static class Options
	{
		@Option(gloss = "Cache path to the types path")
		public String entityTypesPath;
	}

	public static Options opts = new Options();

	// Given those ids, we retrieve the set of types
	private static StringCache entityTypesCache;

	public Set<String> getEntityTypes(final String entity)
	{
		if (opts.entityTypesPath == null)
			return Collections.singleton(FreebaseInfo.ENTITY);

		// Read types from cache
		if (entityTypesCache == null)
			entityTypesCache = StringCacheUtils.create(opts.entityTypesPath);
		final Set<String> types = new HashSet<>();
		final String typesStr = entityTypesCache.get(entity);
		if (typesStr != null)
			Collections.addAll(types, typesStr.split(","));
		else
			types.add(FreebaseInfo.ENTITY);
		return types;
	}

	@Override
	public SemType getEntityType(final String entity)
	{
		final Set<String> types = getEntityTypes(entity);
		// Remove supertypes
		// TODO(pliang): this is inefficient!
		final Set<String> resultTypes = new HashSet<>(types);
		for (final String entityType : types)
			for (final String supertype : SemTypeHierarchy.singleton.getSupertypes(entityType))
				if (!supertype.equals(entityType))
					resultTypes.remove(supertype);
		return SemType.newUnionSemType(resultTypes);
	}

	@Override
	public SemType getPropertyType(final String property)
	{
		// property = fb:location.location.area
		// arg1Type = fb:location.location       --> becomes retType (head of formula)
		// arg2Type = fb:type.float              --> becomes argType
		final FreebaseInfo info = FreebaseInfo.getSingleton();
		final String arg1Type = info.getArg1Type(property), arg2Type = info.getArg2Type(property);
		if (arg1Type == null || arg2Type == null)
			return null;
		return SemType.newFuncSemType(arg2Type, arg1Type);
	}
}
