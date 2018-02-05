package edu.stanford.nlp.sempre.tables.serialize;

import edu.stanford.nlp.sempre.ContextValue;
import edu.stanford.nlp.sempre.Derivation;
import edu.stanford.nlp.sempre.Example;
import edu.stanford.nlp.sempre.FeatureVector;
import edu.stanford.nlp.sempre.Formulas;
import edu.stanford.nlp.sempre.LanguageInfo;
import edu.stanford.nlp.sempre.ListValue;
import edu.stanford.nlp.sempre.Rule;
import edu.stanford.nlp.sempre.SemType;
import edu.stanford.nlp.sempre.Value;
import edu.stanford.nlp.sempre.Values;
import fig.basic.LispTree;
import fig.basic.LogInfo;
import fig.basic.Option;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * Lazily read and construct examples from a dump file. The process is fast if the examples are read sequentially.
 *
 * @author ppasupat
 */
public class LazyLoadedExampleList implements List<Example>
{
	public static class Options
	{
		@Option(gloss = "whether to ensure thread safety (makes things slower)")
		public boolean threadSafe = false;
	}

	public static Options opts = new Options();

	private final List<String> paths;
	private final List<Integer> sizes;
	private final List<Integer> offsets;
	private final List<Integer> exampleIndexToPathIndex;
	private final int size;
	// Whether each file contains only a single example (faster)
	private final boolean single;

	private final LazyLoadedExampleListIterator defaultIterator;

	public LazyLoadedExampleList(final String path, final int maxSize)
	{
		this(Collections.singletonList(path), maxSize);
	}

	public LazyLoadedExampleList(final List<String> paths, final int maxSize)
	{
		this(paths, maxSize, false);
	}

	public LazyLoadedExampleList(final List<String> paths, final int maxSize, final boolean single)
	{
		this.paths = new ArrayList<>(paths);
		this.single = single;
		// Combined the number of examples from all files
		sizes = new ArrayList<>();
		offsets = new ArrayList<>();
		exampleIndexToPathIndex = new ArrayList<>();
		int size = 0;
		for (int pathIndex = 0; pathIndex < paths.size(); pathIndex++)
		{
			final String path = paths.get(pathIndex);
			if (single)
			{
				sizes.add(1);
				exampleIndexToPathIndex.add(pathIndex);
				offsets.add(size);
				size++;
			}
			else
			{
				final int thisSize = readSizeFromMetadata(LispTree.proto.parseFromFile(path).next());
				sizes.add(thisSize);
				for (int i = 0; i < thisSize; i++)
					exampleIndexToPathIndex.add(pathIndex);
				offsets.add(size);
				size += thisSize;
			}
		}
		this.size = Math.min(size, maxSize);
		LogInfo.logs("(LazyLoadedExampleList) Dataset size: %d", this.size);
		defaultIterator = new LazyLoadedExampleListIterator();
	}

	public List<String> getPaths()
	{
		return paths;
	}

	@Override
	public int size()
	{
		return size;
	}

	@Override
	public boolean isEmpty()
	{
		return size == 0;
	}

	// ============================================================
	// Iterator
	// ============================================================

	public class LazyLoadedExampleListIterator implements Iterator<Example>
	{
		Iterator<LispTree> trees = null;
		private int currentPathIndex = -1, currentIndex = -1;
		private Example currentExample = null;

		@Override
		public boolean hasNext()
		{
			return currentIndex + 1 < size; // size could be affected by MaxExampleForGroup
		}

		@Override
		public Example next()
		{
			currentIndex++;
			while (trees == null || !trees.hasNext())
			{
				trees = LispTree.proto.parseFromFile(paths.get(++currentPathIndex));
				trees.next(); // Skip metadata
			}
			return currentExample = readExample(trees.next());
		}

		public Example seek(final int index)
		{
			if (index < 0 || index >= size)
				throw new IndexOutOfBoundsException("Array size: " + size + "; No index " + index);
			final int pathIndex = exampleIndexToPathIndex.get(index);
			if (pathIndex != currentPathIndex || currentIndex > index)
			{
				currentPathIndex = pathIndex;
				trees = LispTree.proto.parseFromFile(paths.get(currentPathIndex));
				trees.next(); // Skip metadata
				currentIndex = offsets.get(pathIndex) - 1;
			}
			while (currentIndex < index)
			{
				currentIndex++;
				final LispTree tree = trees.next();
				if (currentIndex == index)
					currentExample = readExample(tree);
			}
			return currentExample;
		}

		public int getCurrentIndex()
		{
			return currentIndex;
		}

		public Example getCurrentExample()
		{
			return currentExample;
		}
	}

	@Override
	public Iterator<Example> iterator()
	{
		return new LazyLoadedExampleListIterator();
	}

	@Override
	public Example get(final int index)
	{
		if (opts.threadSafe)
			return new LazyLoadedExampleListIterator().seek(index);
		return defaultIterator.seek(index);
	}

	public List<Example> loadAll()
	{
		final List<Example> examples = new ArrayList<>();
		final Iterator<Example> itr = iterator();
		while (itr.hasNext())
			examples.add(itr.next());
		return examples;
	}

	public List<String> getAllIds()
	{
		final List<String> ids = new ArrayList<>();
		for (final String path : paths)
			if (single)
			{
				final Matcher matcher = SerializedDataset.GZ_PATTERN.matcher(new File(path).getName());
				matcher.matches();
				ids.add(matcher.group(3));
			}
			else
			{
				final Iterator<LispTree> trees = LispTree.proto.parseFromFile(path);
				while (trees.hasNext())
				{
					final LispTree tree = trees.next();
					final String exampleId = getExampleId(tree);
					if (exampleId != null)
						ids.add(exampleId);
				}
			}
		return ids;
	}

	// ============================================================
	// Read Metadata
	// ============================================================

	private int readSizeFromMetadata(final LispTree tree)
	{
		if (!"metadata".equals(tree.child(0).value))
			throw new RuntimeException("Not metadata: " + tree);
		for (int i = 1; i < tree.children.size(); i++)
		{
			final LispTree arg = tree.child(i);
			if ("size".equals(arg.child(0).value))
				return Integer.parseInt(arg.child(1).value);
		}
		throw new RuntimeException("Size not specified: " + tree);
	}

	// ============================================================
	// LispTree --> Example
	// ============================================================

	private static final Set<String> finalFields = new HashSet<>(Arrays.asList("id", "utterance", "targetFormula", "targetValue", "targetValues", "context"));

	private String getExampleId(final LispTree tree)
	{
		if (!"example".equals(tree.child(0).value))
			return null;
		for (int i = 1; i < tree.children.size(); i++)
		{
			final LispTree arg = tree.child(i);
			if ("id".equals(arg.child(0).value))
				return arg.child(1).value;
		}
		// The ID is missing. Throw an error.
		String treeS = tree.toString();
		treeS = treeS.substring(0, Math.min(140, treeS.length()));
		throw new RuntimeException("Example does not have an ID: " + treeS);
	}

	private Example readExample(final LispTree tree)
	{
		final Example.Builder b = new Example.Builder();
		if (!"example".equals(tree.child(0).value))
			LogInfo.fails("Not an example: %s", tree);

		// final fields
		for (int i = 1; i < tree.children.size(); i++)
		{
			final LispTree arg = tree.child(i);
			final String label = arg.child(0).value;
			if ("id".equals(label))
				b.setId(arg.child(1).value);
			else
				if ("utterance".equals(label))
					b.setUtterance(arg.child(1).value);
				else
					if ("targetFormula".equals(label))
						b.setTargetFormula(Formulas.fromLispTree(arg.child(1)));
					else
						if ("targetValue".equals(label) || "targetValues".equals(label))
						{
							if (arg.children.size() != 2)
								throw new RuntimeException("Expect one target value");
							b.setTargetValue(Values.fromLispTree(arg.child(1)));
						}
						else
							if ("context".equals(label))
								b.setContext(new ContextValue(arg));
		}
		b.setLanguageInfo(new LanguageInfo());

		final Example ex = b.createExample();

		// other fields
		for (int i = 1; i < tree.children.size(); i++)
		{
			final LispTree arg = tree.child(i);
			final String label = arg.child(0).value;
			if ("tokens".equals(label))
			{
				final int n = arg.child(1).children.size();
				for (int j = 0; j < n; j++)
					ex.languageInfo.tokens.add(arg.child(1).child(j).value);
			}
			else
				if ("lemmaTokens".equals(label))
				{
					final int n = arg.child(1).children.size();
					for (int j = 0; j < n; j++)
						ex.languageInfo.lemmaTokens.add(arg.child(1).child(j).value);
				}
				else
					if ("posTags".equals(label))
					{
						final int n = arg.child(1).children.size();
						for (int j = 0; j < n; j++)
							ex.languageInfo.posTags.add(arg.child(1).child(j).value);
					}
					else
						if ("nerTags".equals(label))
						{
							final int n = arg.child(1).children.size();
							for (int j = 0; j < n; j++)
								ex.languageInfo.nerTags.add(arg.child(1).child(j).value);
						}
						else
							if ("nerValues".equals(label))
							{
								final int n = arg.child(1).children.size();
								for (int j = 0; j < n; j++)
								{
									String value = arg.child(1).child(j).value;
									if ("null".equals(value))
										value = null;
									ex.languageInfo.nerValues.add(value);
								}
							}
							else
								if ("derivations".equals(label))
								{
									ex.predDerivations = new ArrayList<>();
									for (int j = 1; j < arg.children.size(); j++)
										ex.predDerivations.add(readDerivation(arg.child(j)));
								}
								else
									if (!finalFields.contains(label))
										throw new RuntimeException("Invalid example argument: " + arg);
		}

		return ex;
	}

	public static final String SERIALIZED_ROOT = "$SERIALIZED_ROOT";

	private Derivation readDerivation(final LispTree tree)
	{
		final Derivation.Builder b = new Derivation.Builder().cat(SERIALIZED_ROOT).start(-1).end(-1).localFeatureVector(new FeatureVector()).rule(Rule.nullRule).children(new ArrayList<Derivation>());
		if (!"derivation".equals(tree.child(0).value))
			LogInfo.fails("Not a derivation: %s", tree);

		for (int i = 1; i < tree.children.size(); i++)
		{
			final LispTree arg = tree.child(i);
			final String label = arg.child(0).value;
			if ("formula".equals(label))
				b.formula(Formulas.fromLispTree(arg.child(1)));
			else
				if ("type".equals(label))
					b.type(SemType.fromLispTree(arg.child(1)));
				else
					if ("value".equals(label))
						b.value(Values.fromLispTree(arg.child(1)));
					else
						if (label.endsWith("values"))
						{
							final List<Value> values = new ArrayList<>();
							for (int j = 1; j < arg.children.size(); j++)
								values.add(Values.fromLispTree(arg.child(j)));
							b.value(new ListValue(values));
						}
						else
							if ("canonicalUtterance".equals(label))
								b.canonicalUtterance(arg.child(1).value);
							else
								throw new RuntimeException("Invalid derivation argument: " + arg);
		}
		return b.createDerivation();
	}

	// ============================================================
	// Unimplemented methods
	// ============================================================

	@Override
	public boolean contains(final Object o)
	{
		throw new RuntimeException("Not implemented!");
	}

	@Override
	public Object[] toArray()
	{
		throw new RuntimeException("Not implemented!");
	}

	@Override
	public <T> T[] toArray(final T[] a)
	{
		throw new RuntimeException("Not implemented!");
	}

	@Override
	public boolean add(final Example e)
	{
		throw new RuntimeException("Not implemented!");
	}

	@Override
	public boolean remove(final Object o)
	{
		throw new RuntimeException("Not implemented!");
	}

	@Override
	public boolean containsAll(final Collection<?> c)
	{
		throw new RuntimeException("Not implemented!");
	}

	@Override
	public boolean addAll(final Collection<? extends Example> c)
	{
		throw new RuntimeException("Not implemented!");
	}

	@Override
	public boolean addAll(final int index, final Collection<? extends Example> c)
	{
		throw new RuntimeException("Not implemented!");
	}

	@Override
	public boolean removeAll(final Collection<?> c)
	{
		throw new RuntimeException("Not implemented!");
	}

	@Override
	public boolean retainAll(final Collection<?> c)
	{
		throw new RuntimeException("Not implemented!");
	}

	@Override
	public void clear()
	{
		throw new RuntimeException("Not implemented!");
	}

	@Override
	public Example set(final int index, final Example element)
	{
		throw new RuntimeException("Not implemented!");
	}

	@Override
	public void add(final int index, final Example element)
	{
		throw new RuntimeException("Not implemented!");
	}

	@Override
	public Example remove(final int index)
	{
		throw new RuntimeException("Not implemented!");
	}

	@Override
	public int indexOf(final Object o)
	{
		throw new RuntimeException("Not implemented!");
	}

	@Override
	public int lastIndexOf(final Object o)
	{
		throw new RuntimeException("Not implemented!");
	}

	@Override
	public ListIterator<Example> listIterator()
	{
		throw new RuntimeException("Not implemented!");
	}

	@Override
	public ListIterator<Example> listIterator(final int index)
	{
		throw new RuntimeException("Not implemented!");
	}

	@Override
	public List<Example> subList(final int fromIndex, final int toIndex)
	{
		throw new RuntimeException("Not implemented!");
	}
}
