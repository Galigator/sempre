package edu.stanford.nlp.sempre;

import javax.xml.ws.WebFault;

@WebFault(name = "SempreError")
public class SempreError extends RuntimeException
{

	private static final long serialVersionUID = -2171271967007041814L;

	public SempreError()
	{
		super();
	}

	public SempreError(final String message)
	{
		super(message);
	}

	public SempreError(final Throwable cause)
	{
		super(cause);
	}

	public SempreError(final String message, final Throwable cause)
	{
		super(message, cause);
	}
}
