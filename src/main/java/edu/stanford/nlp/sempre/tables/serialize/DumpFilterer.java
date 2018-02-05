package edu.stanford.nlp.sempre.tables.serialize;

import edu.stanford.nlp.sempre.Builder;
import edu.stanford.nlp.sempre.Dataset;
import edu.stanford.nlp.sempre.Example;
import edu.stanford.nlp.sempre.Formula;
import edu.stanford.nlp.sempre.Formulas;
import edu.stanford.nlp.sempre.Master;
import edu.stanford.nlp.sempre.Value;
import fig.basic.IOUtils;
import fig.basic.LispTree;
import fig.basic.LogInfo;
import fig.basic.Option;
import fig.basic.Pair;
import fig.exec.Execution;
import java.io.BufferedReader;
import java.io.File;
import java.io.PrintWriter;
import java.util.Iterator;

public class DumpFilterer implements Runnable
{
	public static class Options
	{
		@Option(gloss = "verbosity")
		public int verbose = 0;
		@Option(gloss = "input dump directory")
		public String filtererInputDumpDirectory;
	}

	public static Options opts = new Options();

	public static void main(final String[] args)
	{
		Execution.run(args, "DumpFiltererMain", new DumpFilterer(), Master.getOptionsParser());
	}

	Builder builder;

	@Override
	public void run()
	{
		builder = new Builder();
		builder.build();
		final String outDir = Execution.getFile("filtered");
		new File(outDir).mkdirs();
		for (final Pair<String, String> pathPair : Dataset.opts.inPaths)
		{
			final String group = pathPair.getFirst();
			final String path = pathPair.getSecond();
			// Read LispTrees
			LogInfo.begin_track("Reading %s", path);
			final int maxExamples = Dataset.getMaxExamplesForGroup(group);
			final Iterator<LispTree> trees = LispTree.proto.parseFromFile(path);
			// Go through the examples
			int n = 0;
			while (n < maxExamples)
			{
				// Format: (example (id ...) (utterance ...) (targetFormula ...) (targetValue ...))
				final LispTree tree = trees.next();
				if (tree == null)
					break;
				if (tree.children.size() < 2 || !"example".equals(tree.child(0).value))
				{
					if ("metadata".equals(tree.child(0).value))
						continue;
					throw new RuntimeException("Invalid example: " + tree);
				}
				final Example ex = Example.fromLispTree(tree, path + ":" + n);
				ex.preprocess();
				LogInfo.logs("Example %s (%d): %s => %s", ex.id, n, ex.getTokens(), ex.targetValue);
				n++;
				processExample(ex);
			}
			LogInfo.end_track();
		}
	}

	private void processExample(final Example ex)
	{
		final File inPath = new File(opts.filtererInputDumpDirectory, ex.id + ".gz");
		final File outPath = new File(Execution.getFile("filtered"), ex.id + ".gz");
		try
		{
			final BufferedReader reader = IOUtils.openInHard(inPath);
			final PrintWriter writer = IOUtils.openOutHard(outPath);
			int inLines = 0, outLines = 0;
			String line;
			while ((line = reader.readLine()) != null)
			{
				inLines++;
				final LispTree tree = LispTree.proto.parseFromString(line);
				if (!"formula".equals(tree.child(1).child(0).value))
					throw new RuntimeException("Invalid tree: " + tree);
				final Formula formula = Formulas.fromLispTree(tree.child(1).child(1));
				final Value value = builder.executor.execute(formula, ex.context).value;
				final double compatibility = builder.valueEvaluator.getCompatibility(ex.targetValue, value);
				if (compatibility == 1.0)
				{
					writer.println(tree);
					outLines++;
				}
				else
					if (opts.verbose >= 2)
						LogInfo.logs("Filtered out %s <= %s", value, formula);
			}
			LogInfo.logs("Filtered %d => %d", inLines, outLines);
			reader.close();
			writer.close();
		}
		catch (final Exception e)
		{
			LogInfo.warnings("Got an error: %s", e);
		}
	}

}
