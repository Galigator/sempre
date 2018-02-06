package edu.stanford.nlp.sempre.tables.test;

import edu.stanford.nlp.sempre.ContextValue;
import edu.stanford.nlp.sempre.Example;
import edu.stanford.nlp.sempre.Formula;
import edu.stanford.nlp.sempre.ListValue;
import edu.stanford.nlp.sempre.Master;
import edu.stanford.nlp.sempre.NameValue;
import edu.stanford.nlp.sempre.Value;
import edu.stanford.nlp.sempre.ValueEvaluator;
import edu.stanford.nlp.sempre.tables.TableKnowledgeGraph;
import edu.stanford.nlp.sempre.tables.TableValueEvaluator;
import edu.stanford.nlp.sempre.tables.lambdadcs.LambdaDCSExecutor;
import fig.basic.IOUtils;
import fig.basic.LispTree;
import fig.basic.LogInfo;
import fig.basic.Option;
import fig.exec.Execution;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Execute the specified logical forms on the specified WikiTableQuestions context.
 *
 * @author ppasupat
 */
public class BatchTableExecutor implements Runnable
{
	public static class Options
	{
		@Option(gloss = "TSV file containing table contexts and logical forms")
		public String batchInput;
		@Option(gloss = "Datasets for mapping example IDs to contexts")
		public List<String> batchDatasets = Arrays.asList("lib/data/tables/data/training.examples");
	}

	public static Options opts = new Options();

	public static void main(final String[] args)
	{
		Execution.run(args, "BatchTableExecutorMain", new BatchTableExecutor(), Master.getOptionsParser());
	}

	@Override
	public void run()
	{
		if (opts.batchInput == null || opts.batchInput.isEmpty())
		{
			LogInfo.logs("*******************************************************************************");
			LogInfo.logs("USAGE: ./run @mode=tables @class=execute -batchInput <filename>");
			LogInfo.logs("");
			LogInfo.logs("Input file format: Each line has something like");
			LogInfo.logs("  nt-218    [tab]   (count (fb:type.object.type fb:type.row))");
			LogInfo.logs("or");
			LogInfo.logs("  csv/204-csv/23.csv    [tab]   (count (fb:type.object.type fb:type.row))");
			LogInfo.logs("");
			LogInfo.logs("Results will also be printed to state/execs/___.exec/denotations.tsv");
			LogInfo.logs("Output format:");
			LogInfo.logs("  nt-218    [tab]   (count (fb:type.object.type fb:type.row))   [tab]   (list (number 10))   [tab]   false");
			LogInfo.logs("where the last column indicates whether the answer is consistent with the target answer");
			LogInfo.logs("(only available when the first column is nt-___)");
			LogInfo.logs("*******************************************************************************");
			System.exit(1);
		}
		final LambdaDCSExecutor executor = new LambdaDCSExecutor();
		final ValueEvaluator evaluator = new TableValueEvaluator();
		try
		{
			final BufferedReader reader = IOUtils.openIn(opts.batchInput);
			final PrintWriter output = IOUtils.openOut(Execution.getFile("denotations.tsv"));
			String line;
			while ((line = reader.readLine()) != null)
			{
				final String[] tokens = line.split("\t");
				String answer;
				try
				{
					final Formula formula = Formula.fromString(tokens[1]);
					if (tokens[0].startsWith("csv"))
					{
						final TableKnowledgeGraph graph = TableKnowledgeGraph.fromFilename(tokens[0]);
						final ContextValue context = new ContextValue(graph);
						Value denotation = executor.execute(formula, context).value;
						if (denotation instanceof ListValue)
							denotation = addOriginalStrings((ListValue) denotation, graph);
						answer = denotation.toString();
					}
					else
					{
						final Example ex = exIdToExample(tokens[0]);
						Value denotation = executor.execute(formula, ex.context).value;
						if (denotation instanceof ListValue)
							denotation = addOriginalStrings((ListValue) denotation, (TableKnowledgeGraph) ex.context.graph);
						answer = denotation.toString();
						final boolean correct = evaluator.getCompatibility(ex.targetValue, denotation) == 1.;
						answer = denotation.toString() + "\t" + correct;
					}
				}
				catch (final Exception e)
				{
					answer = "ERROR: " + e;
				}
				System.out.printf("%s\t%s\t%s\n", tokens[0], tokens[1], answer);
				output.printf("%s\t%s\t%s\n", tokens[0], tokens[1], answer);
			}
			reader.close();
			output.close();
		}
		catch (final IOException e)
		{
			throw new RuntimeException(e);
		}
	}

	private Map<String, Object> exIdToExampleMap;

	private Example exIdToExample(final String exId)
	{
		if (exIdToExampleMap == null)
		{
			exIdToExampleMap = new HashMap<>();
			try
			{
				for (final String filename : opts.batchDatasets)
				{
					final BufferedReader reader = IOUtils.openIn(filename);
					String line;
					while ((line = reader.readLine()) != null)
					{
						final LispTree tree = LispTree.proto.parseFromString(line);
						if (!"id".equals(tree.child(1).child(0).value))
							throw new RuntimeException("Malformed example: " + line);
						exIdToExampleMap.put(tree.child(1).child(1).value, tree);
					}
				}
			}
			catch (final IOException e)
			{
				throw new RuntimeException(e);
			}
		}
		final Object obj = exIdToExampleMap.get(exId);
		if (obj == null)
			return null;
		Example ex;
		if (obj instanceof LispTree)
		{
			ex = Example.fromLispTree((LispTree) obj, exId);
			ex.preprocess();
			exIdToExampleMap.put(exId, ex);
		}
		else
			ex = (Example) obj;
		return ex;
	}

	ListValue addOriginalStrings(final ListValue answers, final TableKnowledgeGraph graph)
	{
		final List<Value> values = new ArrayList<>();
		for (Value value : answers.values)
		{
			if (value instanceof NameValue)
			{
				final NameValue name = (NameValue) value;
				if (name._description == null)
					value = new NameValue(name._id, graph.getOriginalString(((NameValue) value)._id));
			}
			values.add(value);
		}
		return new ListValue(values);
	}

}
