package edu.stanford.nlp.sempre;

import fig.basic.Evaluation;
import fig.basic.IOUtils;
import fig.basic.LispTree;
import fig.basic.LogInfo;
import fig.basic.Pair;
import fig.exec.Execution;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Output examples in various forms.
 *
 * @author Percy Liang
 */
public final class ExampleUtils
{
	private ExampleUtils()
	{
	}

	// Output JSON file with just the basic input/output.
	public static void writeJson(final List<Example> examples)
	{
		try (final PrintWriter out = IOUtils.openOutHard(Execution.getFile("examples.json")))
		{
			for (final Example ex : examples)
				out.println(ex.toJson());
		}
	}

	public static void writeJson(final List<Example> examples, final String outPath)
	{
		try (final PrintWriter out = IOUtils.openOutHard(outPath))
		{
			out.println("[");
			for (int i = 0; i < examples.size(); ++i)
			{
				final Example ex = examples.get(i);
				out.print(ex.toJson());
				out.println(i < examples.size() - 1 ? "," : "");
			}
			out.println("]");
		}
	}

	// Output examples in Simple Dataset Format (Ranking).
	public static void writeSDF(final int iter, final String group, final Evaluation evaluation, final List<Example> examples, final boolean outputPredDerivations)
	{
		final String basePath = "preds-iter" + iter + "-" + group + ".examples";
		final String outPath = Execution.getFile(basePath);
		if (outPath == null || examples.size() == 0)
			return;
		LogInfo.begin_track("Writing examples to %s", basePath);
		try (final PrintWriter out = IOUtils.openOutHard(outPath))
		{

			final LispTree p = LispTree.proto;
			out.println("# SDF version 1.1");
			out.println("# " + p.L(p.L("iter", iter), p.L("group", group), p.L("numExamples", examples.size()), p.L("evaluation", evaluation.toLispTree())));
			for (final Example ex : examples)
			{
				out.println("");
				out.println("example " + ex.id);
				out.println("description " + p.L(p.L("utterance", ex.utterance), p.L("targetValue", ex.targetValue.toLispTree()), p.L("evaluation", ex.evaluation.toLispTree())));

				if (outputPredDerivations)
					for (final Derivation deriv : ex.predDerivations)
					{
						final StringBuilder buf = new StringBuilder();
						buf.append("item");
						final LispTree description = p.newList();
						if (deriv.canonicalUtterance != null)
							description.addChild(p.L("canonicalUtterance", deriv.canonicalUtterance));
						description.addChild(p.L("formula", deriv.formula.toLispTree()));
						description.addChild(p.L("value", deriv.value.toLispTree()));
						buf.append("\t" + description);
						buf.append("\t" + deriv.compatibility);
						final Map<String, Double> features = deriv.getAllFeatureVector();
						buf.append("\t");
						boolean first = true;
						for (final Map.Entry<String, Double> e : features.entrySet())
						{
							if (!first)
								buf.append(' ');
							first = false;
							buf.append(e.getKey() + ":" + e.getValue());
						}
						out.println(buf.toString());
					}
			}
		}
		LogInfo.end_track();
	}

	public static void writeParaphraseSDF(final int iter, final String group, final Example ex, final boolean outputPredDerivations)
	{
		final String basePath = "preds-iter" + iter + "-" + group + ".examples";
		final String outPath = Execution.getFile(basePath);
		if (outPath == null)
			return;
		try (final PrintWriter out = IOUtils.openOutAppendHard(outPath))
		{

			out.println("example " + ex.id);

			if (outputPredDerivations)
			{
				int i = 0;
				for (final Derivation deriv : ex.predDerivations)
				{
					if (deriv.canonicalUtterance != null)
						out.println("Pred@" + i + ":\t" + ex.utterance + "\t" + deriv.canonicalUtterance + "\t" + deriv.compatibility + "\t" + deriv.formula + "\t" + deriv.prob);
					i++;
				}
			}
		}
	}

	public static void writeEvaluationSDF(final int iter, final String group, final Evaluation evaluation, final int numExamples)
	{
		final String basePath = "preds-iter" + iter + "-" + group + ".examples";
		final String outPath = Execution.getFile(basePath);
		if (outPath == null)
			return;
		try (final PrintWriter out = IOUtils.openOutAppendHard(outPath))
		{
			final LispTree p = LispTree.proto;
			out.println("");
			out.println("# SDF version 1.1");
			out.println("# " + p.L(p.L("iter", iter), p.L("group", group), p.L("numExamples", numExamples), p.L("evaluation", evaluation.toLispTree())));
		}
	}

	public static void writePredictionTSV(final int iter, final String group, final Example ex)
	{
		final String basePath = "preds-iter" + iter + "-" + group + ".tsv";
		final String outPath = Execution.getFile(basePath);
		if (outPath == null)
			return;
		try (final PrintWriter out = IOUtils.openOutAppendHard(outPath))
		{

			final List<String> fields = new ArrayList<>();
			fields.add(ex.id);

			if (!ex.predDerivations.isEmpty())
			{
				final Derivation deriv = ex.predDerivations.get(0);
				if (deriv.value instanceof ListValue)
				{
					final List<Value> values = ((ListValue) deriv.value).values;
					for (final Value v : values)
						fields.add(v.pureString().replaceAll("\\s+", " ").trim());
				}
			}

			out.println(String.join("\t", fields));
		}
	}

	//read lisptree and write json
	public static void main(final String[] args)
	{
		final Dataset dataset = new Dataset();
		final Pair<String, String> pair = Pair.newPair("train", args[0]);
		Dataset.opts.splitDevFromTrain = false;
		dataset.readFromPathPairs(Collections.singletonList(pair));
		final List<Example> examples = dataset.examples("train");
		writeJson(examples, args[1]);
	}
}
