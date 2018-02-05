package edu.stanford.nlp.sempre.interactive;

import edu.stanford.nlp.sempre.Master;

public class QueryStats
{
	Master.Response response;
	QueryType type;

	public enum QueryType
	{
		q, def, accept, reject, other
	};

	public QueryStats(final Master.Response response)
	{
		this.response = response;
	}

	public QueryStats(final Master.Response response, final String command)
	{
		this.response = response;
		put("type", command.substring(1));
	}

	public void put(final String k, final Object v)
	{
		response.stats.put(k, v);
	}

	public void size(final int num)
	{
		put("size", num);
	}

	public void status(final String status)
	{
		put("status", status);
	}

	public void rank(final int r)
	{
		put("rank", r);
	}

	public void error(final String msg)
	{
		put("error", msg);
	}
}
