package edu.stanford.nlp.sempre;

import fig.basic.Option;
import fig.exec.Execution;
import java.lang.reflect.Constructor;

/**
 * Entry point for the semantic parser.
 *
 * @author Percy Liang
 */
public class Main implements Runnable
{
	@Option
	public boolean interactive = false;
	@Option
	public boolean server = false;
	@Option
	public String masterType = "edu.stanford.nlp.sempre.Master";

	public void run()
	{
		final Builder builder = new Builder();
		builder.build();

		final Dataset dataset = new Dataset();
		dataset.read();

		final Learner learner = new Learner(builder.parser, builder.params, dataset);
		learner.learn();

		if (server || interactive)
		{
			final Master master = createMaster(masterType, builder);
			if (server)
				master.runServer();
			if (interactive)
				master.runInteractivePrompt();
		}
	}

	public Master createMaster(final String masterType, final Builder builder)
	{
		try
		{
			final Class<?> masterClass = Class.forName(masterType);
			final Constructor<?> constructor = masterClass.getConstructor(Builder.class);
			return (Master) constructor.newInstance(builder);
		}
		catch (final Throwable t)
		{
			t.printStackTrace();
		}
		return null;
	}

	public static void main(final String[] args)
	{
		Execution.run(args, "Main", new Main(), Master.getOptionsParser());
	}
}
