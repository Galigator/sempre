package edu.stanford.nlp.sempre.freebase;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import edu.stanford.nlp.sempre.Formula;
import edu.stanford.nlp.sempre.Formulas;
import fig.basic.IOUtils;
import fig.basic.LispTree;
import fig.basic.LogInfo;
import fig.basic.Option;
import fig.basic.TDoubleMap;
import fig.exec.Execution;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Input: canonicalized Freebase ttl file. Input: example files. Output: subset of the ttl file that only involves the referenced properties.
 *
 * @author Percy Liang
 */
public class FilterFreebase implements Runnable
{
	@Option(required = true, gloss = "Canonicalized Freebase dump")
	public String inPath;
	@Option
	public int maxInputLines = Integer.MAX_VALUE;
	@Option(gloss = "Examples files (keep properties that show up in these files)")
	public List<String> examplesPaths = new ArrayList<>();

	@Option(gloss = "Keep only type entries involving these")
	public List<String> keepTypesPaths = new ArrayList<>();
	@Option(gloss = "Keep these properties")
	public List<String> keepPropertiesPaths = new ArrayList<>();
	@Option(gloss = "Ignore these properties")
	public List<String> notKeepPropertiesPaths = new ArrayList<>();

	@Option(gloss = "Schema properties to keep")
	public HashSet<String> schemaProperties = new HashSet<>(ImmutableList.of("fb:type.property.schema", "fb:type.property.unit", "fb:type.property.expected_type", "fb:type.property.reverse_property", "fb:freebase.type_hints.mediator", "fb:freebase.type_hints.included_types"));

	@Option(gloss = "General properties that we should keep")
	public HashSet<String> generalProperties = new HashSet<>(ImmutableList.of("fb:type.object.type", "fb:type.object.name", "fb:measurement_unit.dated_integer.number", "fb:measurement_unit.dated_integer.year"));

	// Set this if we want to make a small Freebase.
	@Option(gloss = "If true, keep general properties only for entities seen with the other keepProperties (uses much more memory, but results in smaller output)")
	public boolean keepGeneralPropertiesOnlyForSeenEntities = false;

	@Option
	public boolean keepAllProperties = false;

	// Keep only type assertions involving these types.
	// If empty, don't filter.
	Set<String> keepTypes = new LinkedHashSet<>();

	// These are the properties for which we should keep all entity pairs.  Derived from many sources.
	// Should never be empty.
	Set<String> keepProperties = new LinkedHashSet<>();

	// Entities that we saw (only needed if we need to use them to filter general properties later).
	Set<String> seenEntities = new HashSet<>();

	// Fill out |keepProperties|
	private void readKeep()
	{
		LogInfo.begin_track("readKeep");

		// Always keep schema
		keepProperties.addAll(schemaProperties);

		// General properties to keep
		if (!keepGeneralPropertiesOnlyForSeenEntities)
			keepProperties.addAll(generalProperties);

		// Keep properties mentioned in examples
		for (final String path : examplesPaths)
		{
			LogInfo.logs("Reading %s", path);
			final Iterator<LispTree> it = LispTree.proto.parseFromFile(path);
			while (it.hasNext())
			{
				final LispTree tree = it.next();
				if (!"example".equals(tree.child(0).value))
					throw new RuntimeException("Bad: " + tree);
				for (int i = 1; i < tree.children.size(); i++)
					if ("targetFormula".equals(tree.child(i).child(0).value))
					{
						final Formula formula = Formulas.fromLispTree(tree.child(i).child(1));
						keepProperties.addAll(Formulas.extractAtomicFreebaseElements(formula));
					}
			}
		}

		// Keep types
		for (final String path : keepTypesPaths)
			for (final String type : IOUtils.readLinesHard(path))
				keepTypes.add(type);

		// Keep and not keep properties
		for (final String path : keepPropertiesPaths)
			for (final String property : IOUtils.readLinesHard(path))
				keepProperties.add(property);
		for (final String path : notKeepPropertiesPaths)
			for (final String property : IOUtils.readLinesHard(path))
				keepProperties.remove(property);

		final PrintWriter out = IOUtils.openOutHard(Execution.getFile("keepProperties"));
		for (final String property : keepProperties)
			out.println(property);
		out.close();
		LogInfo.logs("Keeping %s properties", keepProperties.size());
		LogInfo.end_track();
	}

	private void filterTuples()
	{
		LogInfo.begin_track("filterTuples");
		final TDoubleMap<String> propertyCounts = new TDoubleMap<>();

		final PrintWriter out = IOUtils.openOutHard(Execution.getFile("0.ttl"));
		out.println(Utils.ttlPrefix);

		try
		{
			final BufferedReader in = IOUtils.openIn(inPath);
			String line;
			int numInputLines = 0;
			int numOutputLines = 0;
			while (numInputLines < maxInputLines && (line = in.readLine()) != null)
			{
				numInputLines++;
				if (numInputLines % 10000000 == 0)
					LogInfo.logs("filterTuples: Read %s lines, written %d lines", numInputLines, numOutputLines);
				final String[] tokens = Utils.parseTriple(line);
				if (tokens == null)
					continue;
				final String arg1 = tokens[0];
				final String property = tokens[1];
				final String arg2 = tokens[2];
				if (!keepAllProperties && !keepProperties.contains(property))
					continue;

				if (keepGeneralPropertiesOnlyForSeenEntities)
				{
					seenEntities.add(arg1);
					seenEntities.add(arg2);
				}

				// Additional filtering of characters that Virtuoso can't index (we would need to be escape these).
				if (Utils.isUrl(arg2))
					continue;
				if (Utils.identifierContainsStrangeCharacters(arg1) || Utils.identifierContainsStrangeCharacters(arg2))
					continue;

				Utils.writeTriple(out, arg1, property, arg2);

				propertyCounts.incr(property, 1);
				numOutputLines++;
			}
		}
		catch (final IOException e)
		{
			throw new RuntimeException(e);
		}

		// Make a second pass to only output general properties.
		if (keepGeneralPropertiesOnlyForSeenEntities)
		{
			LogInfo.begin_track("Second pass to output general properties for the %d seen entities", seenEntities.size());
			try
			{
				final BufferedReader in = IOUtils.openIn(inPath);
				String line;
				int numInputLines = 0;
				int numOutputLines = 0;
				while (numInputLines < maxInputLines && (line = in.readLine()) != null)
				{
					numInputLines++;
					if (numInputLines % 10000000 == 0)
						LogInfo.logs("filterTuples: Read %s lines, written %d lines", numInputLines, numOutputLines);
					final String[] tokens = Utils.parseTriple(line);
					if (tokens == null)
						continue;
					final String arg1 = tokens[0];
					final String property = tokens[1];
					final String arg2 = tokens[2];
					if (!generalProperties.contains(property))
						continue;
					if (!seenEntities.contains(arg1))
						continue;

					// Only keep types that matter
					if (keepTypes.size() != 0 && property.equals("fb:type.object.type") && !keepTypes.contains(arg2))
						continue;

					Utils.writeTriple(out, arg1, property, arg2);

					numOutputLines++;
				}
			}
			catch (final IOException e)
			{
				throw new RuntimeException(e);
			}
			LogInfo.end_track();
		}

		out.close();

		// Output property statistics
		final PrintWriter propertyCountsOut = IOUtils.openOutHard(Execution.getFile("propertyCounts"));
		final List<TDoubleMap<String>.Entry> entries = Lists.newArrayList(propertyCounts.entrySet());
		Collections.sort(entries, propertyCounts.entryValueComparator());
		for (final TDoubleMap<String>.Entry e : entries)
			propertyCountsOut.println(e.getKey() + "\t" + e.getValue());
		propertyCountsOut.close();

		LogInfo.end_track();
	}

	public void run()
	{
		readKeep();
		filterTuples();
	}

	public static void main(final String[] args)
	{
		Execution.run(args, new FilterFreebase());
	}
}
