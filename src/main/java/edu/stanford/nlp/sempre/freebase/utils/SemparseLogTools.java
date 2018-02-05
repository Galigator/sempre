package edu.stanford.nlp.sempre.freebase.utils;

import edu.stanford.nlp.io.IOUtils;
import fig.basic.LispTree;
import fig.basic.LogInfo;
import fig.basic.MapUtils;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SemparseLogTools
{
	private SemparseLogTools()
	{
	}

	public static void main(final String[] args) throws IOException
	{

		LogInfo.begin_track_printAll("analyze");
		if (args[0].equals("compare"))
			compareLogs(args[1], args[2], args[3]);
		else
			if (args[0].equals("diff"))
				printDiff(args[1], args[2]);
			else
				if (args[0].equals("fb_descriptions"))
					getFbDescInTrueDerivations(args[1], args[2]);
		if (args[0].equals("result_list"))
			printResultList(args[1], args[2]);
		LogInfo.end_track();
	}

	private static void printResultList(final String log, final String field)
	{

		boolean start = false;
		int numOfIterations = -1;
		String prob = null;
		final String correct = null;
		final String oracle = null;

		for (final String line : IOUtils.readLines(log))
		{
			if (line.contains("Iteration"))
			{
				final int slashIndex = line.indexOf('/');
				final int openCurlyIndex = line.indexOf('{');
				numOfIterations = Integer.parseInt(line.substring(slashIndex + 1, openCurlyIndex - 1));
			}
			if (line.contains("Processing iter=" + numOfIterations + ".dev"))
				start = true;
			if (start)
			{
				if (line.contains("Pred@0000"))
					prob = line.substring(line.indexOf("prob=") + 5, line.indexOf(", comp="));
				if (line.contains("Example:"))
					prob = "0";
				if (line.contains("Current: correct="))
				{
					final int correctIndex = line.indexOf("correct=");
					final int oracleIndex = line.indexOf("oracle=");
					final int partCorrectIndex = line.indexOf("partCorrect=");
					final int parsedIndex = line.indexOf("parsed=");
					final int numTokensIndex = line.indexOf("numTokens=");

					if (field.equals("oracle"))
						System.out.println(line.substring(oracleIndex + 7, partCorrectIndex - 1));
					if (field.equals("correct"))
						System.out.println(line.substring(correctIndex + 8, oracleIndex - 1) + "\t" + prob);
					if (field.equals("parsed"))
						System.out.println(line.substring(parsedIndex + 7, numTokensIndex - 1));
				}
			}
		}
	}

	private static void getFbDescInTrueDerivations(final String log, final String out) throws IOException
	{

		boolean start = false;
		int numOfIterations = -1;
		final Map<String, Set<String>> exampleToDescriptions = new HashMap<>();

		String currExample = null;

		for (final String line : IOUtils.readLines(log))
		{
			if (line.contains("Iteration"))
			{
				final int slashIndex = line.indexOf('/');
				final int openCurlyIndex = line.indexOf('{');
				numOfIterations = Integer.parseInt(line.substring(slashIndex + 1, openCurlyIndex - 1));
			}
			if (line.contains("Processing iter=" + numOfIterations + "."))
				start = true;
			if (start)
			{
				if (line.contains("Example:"))
				{
					final int end = line.indexOf("{") - 1;
					currExample = line.substring(line.indexOf("Example:") + 9, end);
					exampleToDescriptions.put(currExample, new HashSet<String>());
				}
				if (line.contains("True@"))
				{

					final String formula = line.substring(line.indexOf("(formula"), line.indexOf("(value") - 1);
					final LispTree t = LispTree.proto.parseFromString(formula);
					final Set<String> descriptions = new HashSet<>();
					extractDescriptionsFromTree(t, descriptions);
					for (final String description : descriptions)
						MapUtils.addToSet(exampleToDescriptions, currExample, description);
				}
			}
		}
		final PrintWriter writer = IOUtils.getPrintWriter(out);
		for (final String example : exampleToDescriptions.keySet())
			if (exampleToDescriptions.get(example).size() > 0)
				writer.println(example + "\t" + exampleToDescriptions.get(example));
		writer.close();
	}

	private static void extractDescriptionsFromTree(final LispTree t, final Set<String> descriptions)
	{

		if (t.value != null)
			if (t.value.indexOf('.') != t.value.lastIndexOf('.'))
				descriptions.add(t.value.substring(t.value.lastIndexOf('.') + 1));
		if (!t.isLeaf())
			for (final LispTree child : t.children)
				extractDescriptionsFromTree(child, descriptions);
	}

	private static void printDiff(final String log, final String field) throws IOException
	{

		boolean start = false;
		int numOfIterations = -1;

		String example = null;
		String targetFormula = null;
		String targetValue = null;
		String trueDeriv = null;
		String predDeriv = null;

		boolean print = false;

		for (final String line : IOUtils.readLines(log))
		{
			if (line.contains("Iteration"))
			{
				final int slashIndex = line.indexOf('/');
				final int openCurlyIndex = line.indexOf('{');
				numOfIterations = Integer.parseInt(line.substring(slashIndex + 1, openCurlyIndex - 1));
			}
			if (line.contains("Processing iter=" + numOfIterations + ".dev"))
				start = true;
			if (start)
			{
				if (line.contains("Example:"))
				{
					if (print && example != null)
					{
						LogInfo.log(example);
						LogInfo.log(targetFormula);
						LogInfo.log(targetValue);
						LogInfo.log(trueDeriv);
						LogInfo.log(predDeriv);
					}
					example = line;
					targetFormula = null;
					targetValue = null;
					trueDeriv = null;
					predDeriv = null;
				}
				if (line.contains("targetFormula:"))
					targetFormula = line;
				if (line.contains("targetValue:"))
					targetValue = line;
				if (line.contains("True@") && trueDeriv == null)
					trueDeriv = line;
				if (line.contains("Pred@") && predDeriv == null)
					predDeriv = line;
				if (line.contains("Current:"))
				{
					if (field.equals("correct"))
						if (line.contains("correct=0") && line.contains("oracle=1"))
							print = true;
						else
							print = false;
					if (field.equals("oracle"))
						if (line.contains("oracle=0") && line.contains("parsed=1"))
							print = true;
						else
							print = false;
					if (field.equals("parsed"))
						if (line.contains("parsed=0"))
							print = true;
						else
							print = false;
				}
			}
		}
	}

	public static void compareLogs(final String log1, final String log2, final String field)
	{
		final List<Double> correctnessList1 = computeCorrectnessList(log1, field);
		final List<Double> correctnessList2 = computeCorrectnessList(log2, field);

		if (correctnessList1.size() != correctnessList2.size())
			throw new RuntimeException("lists are not same size");
		LogInfo.logs("Size of correctness: %s", correctnessList1.size());

		for (int i = 0; i < correctnessList1.size(); ++i)
			if (!correctnessList1.get(i).equals(correctnessList2.get(i)))
				LogInfo.log("example: " + i + " log1: " + correctnessList1.get(i) + " log2: " + correctnessList2.get(i));
	}

	private static List<Double> computeCorrectnessList(final String log1, final String field)
	{

		final List<Double> res = new LinkedList<>();
		boolean start = false;
		int numOfIterations = -1;
		for (final String line : IOUtils.readLines(log1))
		{
			if (line.contains("Iteration"))
			{
				final int slashIndex = line.indexOf('/');
				final int openCurlyIndex = line.indexOf('{');
				numOfIterations = Integer.parseInt(line.substring(slashIndex + 1, openCurlyIndex - 1));
				LogInfo.logs("Number of iterations=%s", numOfIterations);
			}
			if (line.contains("Processing iter=" + numOfIterations + ".dev"))
				start = true;
			if (start)
				if (line.contains("Current: parsed="))
				{
					final String[] tokens = line.split("\\s+");
					for (final String token : tokens)
					{
						final String[] tokenParts = token.split("=");
						if (field.equals(tokenParts[0]))
							res.add(Double.parseDouble(tokenParts[1]));
					}
					//          LogInfo.logs()
					//          int correctIndex = line.indexOf(" correct=");
					//          int oracleIndex = line.indexOf(" oracle=");
					//          int partCorrectIndex = line.indexOf(" partCorrect=");
					//          int partOracleIndex= line.indexOf(" partOracle=");
					//          int afterPartOracleIndex = line.indexOf("\\s+",partOracleIndex);
					//          LogInfo.logs("part oracle index=%s, after=%s",partCorrectIndex,afterPartOracleIndex);
					//          if (field.equals("oracle"))
					//            res.add(Double.parseDouble(line.substring(oracleIndex + 8, partCorrectIndex)));
					//          if (field.equals("correct"))
					//            res.add(Double.parseDouble(line.substring(correctIndex + 9, oracleIndex)));
					//          if (field.equals("partCorrect"))
					//            res.add(Double.parseDouble(line.substring(partCorrectIndex + 13, partOracleIndex)));
					//          if (field.equals("partOracle"))
					//            res.add(Double.parseDouble(line.substring(partOracleIndex + 12, afterPartOracleIndex)));
				}
		}
		return res;
	}

}
