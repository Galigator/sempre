package edu.stanford.nlp.sempre;

public class BadFormulaException extends RuntimeException
{
	public static final long serialVersionUID = 86586128316354597L;

	String message;

	public BadFormulaException(final String message)
	{
		this.message = message;
	}

	// Combine multiple exceptions
	public BadFormulaException(final BadFormulaException... exceptions)
	{
		final StringBuilder builder = new StringBuilder();
		for (final BadFormulaException exception : exceptions)
			builder.append(" | ").append(exception.message);
		//builder.append(exception).append("\n");
		message = builder.toString().substring(3);
	}

	@Override
	public String toString()
	{
		return message;
	}
}
