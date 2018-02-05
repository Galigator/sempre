package edu.stanford.nlp.sempre;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.collect.Lists;
import fig.basic.IOUtils;
import fig.basic.LispTree;
import fig.basic.LogInfo;
import fig.basic.Option;
import fig.basic.Pair;
import fig.basic.StatFig;
import fig.exec.Execution;
import fig.prob.SampleUtils;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * A dataset contains a set of examples, which are keyed by group (e.g., train, dev, test).
 *
 * @author Percy Liang
 */
public class Dataset
{
	public static class Options
	{
		@Option(gloss = "Paths to read input files (format: <group>:<file>)")
		public ArrayList<Pair<String, String>> inPaths = new ArrayList<>();
		@Option(gloss = "Maximum number of examples to read")
		public ArrayList<Pair<String, Integer>> maxExamples = new ArrayList<>();

		// Training file gets split into:
		// |  trainFrac  -->  |           | <-- devFrac |
		@Option(gloss = "Fraction of trainExamples (from the beginning) to keep for training")
		public double trainFrac = 1;
		@Option(gloss = "Fraction of trainExamples (from the end) to keep for development")
		public double devFrac = 0;
		@Option(gloss = "Used to randomly divide training examples")
		public Random splitRandom = new Random(1);
		@Option(gloss = "whether to split dev from train")
		public boolean splitDevFromTrain = true;

		@Option(gloss = "Only keep examples which have at most this number of tokens")
		public int maxTokens = Integer.MAX_VALUE;

		@Option(gloss = "Path to a knowledge graph that will be uploaded as global context")
		public String globalGraphPath;
	}

	public static Options opts = new Options();

	// Group id -> examples in that group
	private LinkedHashMap<String, List<Example>> allExamples = new LinkedHashMap<>();

	// General statistics about the examples.
	private final HashSet<String> tokenTypes = new HashSet<>();
	private final StatFig numTokensFig = new StatFig(); // For each example, number of tokens

	public Set<String> groups()
	{
		return allExamples.keySet();
	}

	public List<Example> examples(final String group)
	{
		return allExamples.get(group);
	}

	/** For JSON. */
	static class GroupInfo
	{
		@JsonProperty
		final String group;
		@JsonProperty
		final List<Example> examples;
		String path; // Optional, used if this was read from a path.

		@JsonCreator
		public GroupInfo(@JsonProperty("group") final String group, @JsonProperty("examples") final List<Example> examples)
		{
			this.group = group;
			this.examples = examples;
		}
	}

	/** For JSON. */
	@JsonProperty("groups")
	public List<GroupInfo> getAllGroupInfos()
	{
		final List<GroupInfo> all = Lists.newArrayList();
		for (final Map.Entry<String, List<Example>> entry : allExamples.entrySet())
			all.add(new GroupInfo(entry.getKey(), entry.getValue()));
		return all;
	}

	/** For JSON. */
	// Allows us to creates dataset from arbitrary JSON, not requiring a
	// path from which to read.
	@JsonCreator
	public static Dataset fromGroupInfos(@JsonProperty("groups") final List<GroupInfo> groups)
	{
		final Dataset d = new Dataset();
		d.readFromGroupInfos(groups);
		return d;
	}

	public void read()
	{
		readFromPathPairs(opts.inPaths);
	}

	public void readFromPathPairs(final List<Pair<String, String>> pathPairs)
	{
		// Try to detect whether we need JSON.
		for (final Pair<String, String> pathPair : pathPairs)
			if (pathPair.getSecond().endsWith(".json"))
			{
				readJsonFromPathPairs(pathPairs);
				return;
			}
		readLispTreeFromPathPairs(pathPairs);
		updateGlobalContext();
	}

	private void updateGlobalContext()
	{
		if (opts.globalGraphPath != null)
		{
			final KnowledgeGraph graph = NaiveKnowledgeGraph.fromFile(opts.globalGraphPath);
			for (final String group : allExamples.keySet())
				for (final Example ex : allExamples.get(group))
					ex.setContext(new ContextValue(graph));
		}
	}

	private void readJsonFromPathPairs(final List<Pair<String, String>> pathPairs)
	{
		final List<GroupInfo> groups = Lists.newArrayListWithCapacity(pathPairs.size());
		for (final Pair<String, String> pathPair : pathPairs)
		{
			final String group = pathPair.getFirst();
			final String path = pathPair.getSecond();
			final List<Example> examples = Json.readValueHard(IOUtils.openInHard(path), new TypeReference<List<Example>>()
			{
			});
			final GroupInfo gi = new GroupInfo(group, examples);
			gi.path = path;
			groups.add(gi);
		}
		readFromGroupInfos(groups);
	}

	private void readFromGroupInfos(final List<GroupInfo> groupInfos)
	{
		LogInfo.begin_track_printAll("Dataset.read");

		for (final GroupInfo groupInfo : groupInfos)
		{
			final int maxExamples = getMaxExamplesForGroup(groupInfo.group);
			List<Example> examples = allExamples.get(groupInfo.group);
			if (examples == null)
				allExamples.put(groupInfo.group, examples = new ArrayList<>());
			readHelper(groupInfo.examples, maxExamples, examples, groupInfo.path);
		}
		if (opts.splitDevFromTrain)
			splitDevFromTrain();
		collectStats();

		LogInfo.end_track();
	}

	private void splitDevFromTrain()
	{
		// Split original training examples randomly into train and dev.
		final List<Example> origTrainExamples = allExamples.get("train");
		if (origTrainExamples != null)
		{
			final int split1 = (int) (opts.trainFrac * origTrainExamples.size());
			final int split2 = (int) ((1 - opts.devFrac) * origTrainExamples.size());
			final int[] perm = SampleUtils.samplePermutation(opts.splitRandom, origTrainExamples.size());

			final List<Example> trainExamples = new ArrayList<>();
			allExamples.put("train", trainExamples);
			List<Example> devExamples = allExamples.get("dev");
			if (devExamples == null)
			{
				// Preserve order
				final LinkedHashMap<String, List<Example>> newAllExamples = new LinkedHashMap<>();
				for (final Map.Entry<String, List<Example>> entry : allExamples.entrySet())
				{
					newAllExamples.put(entry.getKey(), entry.getValue());
					if (entry.getKey().equals("train"))
						newAllExamples.put("dev", devExamples = new ArrayList<>());
				}
				allExamples = newAllExamples;
			}
			for (int i = 0; i < split1; i++)
				trainExamples.add(origTrainExamples.get(perm[i]));
			for (int i = split2; i < origTrainExamples.size(); i++)
				devExamples.add(origTrainExamples.get(perm[i]));
		}
	}

	private void readHelper(final List<Example> incoming, final int maxExamples, final List<Example> examples, final String path)
	{
		if (examples.size() >= maxExamples)
			return;

		int i = 0;
		for (Example ex : incoming)
		{
			if (examples.size() >= maxExamples)
				break;

			if (ex.id == null)
			{
				final String id = (path != null ? path : "<nopath>") + ":" + i;
				ex = new Example.Builder().withExample(ex).setId(id).createExample();
			}
			i++;
			ex.preprocess();

			// Skip example if too long
			if (ex.numTokens() > opts.maxTokens)
				continue;

			LogInfo.logs("Example %s (%d): %s => %s", ex.id, examples.size(), ex.getTokens(), ex.targetValue);

			examples.add(ex);
			numTokensFig.add(ex.numTokens());
			for (final String token : ex.getTokens())
				tokenTypes.add(token);
		}
	}

	private void readLispTreeFromPathPairs(final List<Pair<String, String>> pathPairs)
	{
		LogInfo.begin_track_printAll("Dataset.read");
		for (final Pair<String, String> pathPair : pathPairs)
		{
			final String group = pathPair.getFirst();
			final String path = pathPair.getSecond();
			final int maxExamples = getMaxExamplesForGroup(group);
			List<Example> examples = allExamples.get(group);
			if (examples == null)
				allExamples.put(group, examples = new ArrayList<>());
			readLispTreeHelper(path, maxExamples, examples);
		}
		if (opts.splitDevFromTrain)
			splitDevFromTrain();
		LogInfo.end_track();
	}

	private void readLispTreeHelper(final String path, final int maxExamples, final List<Example> examples)
	{
		if (examples.size() >= maxExamples)
			return;
		LogInfo.begin_track("Reading %s", path);

		final Iterator<LispTree> trees = LispTree.proto.parseFromFile(path);
		int n = 0;
		while (examples.size() < maxExamples && trees.hasNext())
		{
			// Format: (example (id ...) (utterance ...) (targetFormula ...) (targetValue ...))
			final LispTree tree = trees.next();
			if (tree.children.size() < 2 || !"example".equals(tree.child(0).value))
			{
				if ("metadata".equals(tree.child(0).value))
					continue;
				throw new RuntimeException("Invalid example: " + tree);
			}

			final Example ex = Example.fromLispTree(tree, path + ":" + n); // Specify a default id if it doesn't exist
			n++;
			ex.preprocess();

			// Skip example if too long
			if (ex.numTokens() > opts.maxTokens)
				continue;

			LogInfo.logs("Example %s (%d): %s => %s", ex.id, examples.size(), ex.getTokens(), ex.targetValue);

			examples.add(ex);
			numTokensFig.add(ex.numTokens());
			for (final String token : ex.getTokens())
				tokenTypes.add(token);
		}
		LogInfo.end_track();
	}

	private void collectStats()
	{
		LogInfo.begin_track_printAll("Dataset stats");
		Execution.putLogRec("numTokenTypes", tokenTypes.size());
		Execution.putLogRec("numTokensPerExample", numTokensFig);
		for (final Map.Entry<String, List<Example>> e : allExamples.entrySet())
			Execution.putLogRec("numExamples." + e.getKey(), e.getValue().size());
		LogInfo.end_track();
	}

	public static int getMaxExamplesForGroup(final String group)
	{
		int maxExamples = Integer.MAX_VALUE;
		for (final Pair<String, Integer> maxPair : opts.maxExamples)
			if (maxPair.getFirst().equals(group))
				maxExamples = maxPair.getSecond();
		return maxExamples;
	}

	public static void appendExampleToFile(final String path, final Example ex)
	{
		// JSON is an annoying format because we can't just append.
		// So currently we have to read the entire file in and write it out.
		List<Example> examples;
		if (new File(path).exists())
			examples = Json.readValueHard(IOUtils.openInHard(path), new TypeReference<List<Example>>()
			{
			});
		else
			examples = new ArrayList<>();
		examples.add(ex);
		Json.prettyWriteValueHard(new File(path), examples);
	}
}