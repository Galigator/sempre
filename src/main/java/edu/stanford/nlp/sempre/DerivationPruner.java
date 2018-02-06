package edu.stanford.nlp.sempre;

import fig.basic.LogInfo;
import fig.basic.Option;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Prune derivations during parsing. To add custom pruning criteria, implement a DerivationPruningComputer class, and put the class name in the
 * |pruningComputers| option.
 *
 * @author ppasupat
 */

public class DerivationPruner
{
	public static class Options
	{
		@Option(gloss = "Pruning strategies to use")
		public List<String> pruningStrategies = new ArrayList<>();
		@Option(gloss = "DerivationPruningComputer subclasses to look for pruning strategies")
		public List<String> pruningComputers = new ArrayList<>();
		@Option
		public int pruningVerbosity = 0;
		@Option(gloss = "(for tooManyValues) maximum denotation size of the final formula")
		public int maxNumValues = 10;
	}

	public static Options opts = new Options();

	public final Parser parser;
	public final Example ex;
	private final List<DerivationPruningComputer> pruningComputers = new ArrayList<>();
	// If not null, limit the pruning strategies to this list in addition to opts.pruningStrategies.
	private List<String> customAllowedPruningStrategies;
	private final Set<String> allStrategyNames;

	public DerivationPruner(final ParserState parserState)
	{
		parser = parserState.parser;
		ex = parserState.ex;
		pruningComputers.add(new DefaultDerivationPruningComputer(this));
		for (final String pruningComputer : opts.pruningComputers)
			try
			{
				final Class<?> pruningComputerClass = Class.forName(SempreUtils.resolveClassName(pruningComputer));
				pruningComputers.add((DerivationPruningComputer) pruningComputerClass.getConstructor(this.getClass()).newInstance(this));
			}
			catch (final ClassNotFoundException e1)
			{
				throw new SempreError("Illegal pruning computer: " + pruningComputer, e1);
			}
			catch (final Exception e)
			{
				e.printStackTrace();
				e.getCause().printStackTrace();
				throw new SempreError("Error while instantiating pruning computer: " + pruningComputer);
			}
		// Compile the list of all strategies
		allStrategyNames = new HashSet<>();
		for (final DerivationPruningComputer computer : pruningComputers)
			allStrategyNames.addAll(computer.getAllStrategyNames());
		for (final String strategy : opts.pruningStrategies)
			if (!allStrategyNames.contains(strategy))
				LogInfo.fails("Pruning strategy '%s' not found!", strategy);
	}

	/**
	 * Set additional restrictions on the pruning strategies. If customAllowedPruningStrategies is not null, the pruning strategy must be in both
	 * opts.pruningStrategies and customAllowedPruningStrategies in order to be used. Useful when some pruning strategies can break the parsing mechanism.
	 */
	public void setCustomAllowedPruningStrategies(final List<String> customAllowedPruningStrategies_)
	{
		customAllowedPruningStrategies = customAllowedPruningStrategies_;
	}

	protected boolean containsStrategy(final String name)
	{
		return opts.pruningStrategies.contains(name) && (customAllowedPruningStrategies == null || customAllowedPruningStrategies.contains(name));
	}

	public List<DerivationPruningComputer> getPruningComputers()
	{
		return new ArrayList<>(pruningComputers);
	}

	/**
	 * Return true if the derivation should be pruned. Otherwise, return false.
	 */
	public boolean isPruned(final Derivation deriv)
	{
		if (opts.pruningStrategies.isEmpty() && pruningComputers.isEmpty())
			return false;
		String matchedStrategy;
		for (final DerivationPruningComputer computer : pruningComputers)
			if ((matchedStrategy = computer.isPruned(deriv)) != null)
			{
				if (opts.pruningVerbosity >= 2)
					LogInfo.logs("PRUNED [%s] %s", matchedStrategy, deriv.formula);
				return true;
			}
		return false;
	}

	/**
	 * Run isPruned with a (temporary) custom set of allowed pruning strategies. If customAllowedPruningStrategies is null, all strategies are allowed. If
	 * customAllowedPruningStrategies is empty, no pruning happens.
	 */
	public boolean isPruned(final Derivation deriv, final List<String> customAllowedPruningStategies)
	{
		final List<String> old = customAllowedPruningStrategies;
		customAllowedPruningStrategies = customAllowedPruningStategies;
		final boolean answer = isPruned(deriv);
		customAllowedPruningStrategies = old;
		return answer;
	}

}
