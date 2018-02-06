package edu.stanford.nlp.sempre;

import static fig.basic.LogInfo.logs;

import com.google.common.collect.Lists;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import fig.basic.Fmt;
import fig.basic.IOUtils;
import fig.basic.LispTree;
import fig.basic.ListUtils;
import fig.basic.LogInfo;
import fig.basic.MapUtils;
import fig.basic.Option;
import fig.basic.ValueComparator;
import fig.html.HtmlElement;
import fig.html.HtmlUtils;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.net.HttpCookie;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class SecureIdentifiers
{
	private SecureIdentifiers()
	{
	}

	private static SecureRandom random = new SecureRandom();

	public static String getId()
	{
		return new BigInteger(130, random).toString(32);
	}
}

/**
 * This class implements a simple HTTP server which provides a web interface into SEMPRE just like Master.runInteractivePrompt() exposes a command-line tool.
 * Most of the work is dispatched to Master.processLine(). Cookies are used to store the session ID.
 *
 * @author Percy Liang
 */
public class Server
{
	public static class Options
	{
		@Option
		public int port = 8400;
		@Option
		public int numThreads = 4;
		@Option
		public String title = "SEMPRE Demo";
		@Option
		public String headerPath;
		@Option
		public String basePath = "demo-www";
		@Option
		public int verbose = 1;
		@Option
		public int htmlVerbose = 1;
	}

	public static Options opts = new Options();

	Master master;
	public static final HtmlUtils H = new HtmlUtils();

	class Handler implements HttpHandler
	{
		@Override
		public void handle(final HttpExchange exchange)
		{
			try
			{
				new ExchangeState(exchange);
			}
			catch (final Exception e)
			{
				e.printStackTrace();
			}
		}
	}

	class ExchangeState
	{
		// Input
		HttpExchange exchange;
		Map<String, String> reqParams = new HashMap<>();
		String remoteHost;

		// For header
		HttpCookie cookie;
		boolean isNewSession;
		String format;

		boolean jsonFormat()
		{
			return format.equals("json");
		}

		// For writing main content

		public ExchangeState(final HttpExchange exchange) throws IOException
		{
			this.exchange = exchange;

			final URI uri = exchange.getRequestURI();
			remoteHost = exchange.getRemoteAddress().getHostName();

			// Don't use uri.getQuery: it can't distinguish between '+' and '-'
			final String[] tokens = uri.toString().split("\\?");
			if (tokens.length == 2)
				for (final String s : tokens[1].split("&"))
				{
					final String[] kv = s.split("=", 2);
					try
					{
						final String key = URLDecoder.decode(kv[0], "UTF-8");
						final String value = URLDecoder.decode(kv[1], "UTF-8");
						logs("%s => %s", key, value);
						reqParams.put(key, value);
					}
					catch (final UnsupportedEncodingException e)
					{
						throw new RuntimeException(e);
					}
				}
			format = MapUtils.get(reqParams, "format", "html");

			final String cookieStr = exchange.getRequestHeaders().getFirst("Cookie");
			if (cookieStr != null)
			{ // Cookie already exists
				cookie = HttpCookie.parse(cookieStr).get(0);
				isNewSession = false;
			}
			else
			{
				if (!jsonFormat())
					cookie = new HttpCookie("sessionId", SecureIdentifiers.getId());
				else
					cookie = null;
				isNewSession = true; // Create a new cookie
			}

			String sessionId = null;
			if (cookie != null)
				sessionId = cookie.getValue();
			if (opts.verbose >= 2)
				LogInfo.logs("GET %s from %s (%ssessionId=%s)", uri, remoteHost, isNewSession ? "new " : "", sessionId);

			String uriPath = uri.getPath();
			if (uriPath.equals("/"))
				uriPath += "index.html";
			if (uriPath.equals("/sempre"))
				handleQuery(sessionId);
			else
				getFile(opts.basePath + uriPath);

			exchange.close();
		}

		String getMimeType(final String path)
		{
			final String[] tokens = path.split("\\.");
			final String ext = tokens[tokens.length - 1];
			if (ext.equals("html"))
				return "text/html";
			if (ext.equals("css"))
				return "text/css";
			if (ext.equals("jpeg"))
				return "image/jpeg";
			if (ext.equals("gif"))
				return "image/gif";
			return "text/plain";
		}

		void setHeaders(final String mimeType) throws IOException
		{
			final Headers headers = exchange.getResponseHeaders();
			headers.set("Content-Type", mimeType);
			headers.set("Access-Control-Allow-Origin", "*");
			if (isNewSession && cookie != null)
				headers.set("Set-Cookie", cookie.toString());
			exchange.sendResponseHeaders(200, 0);
		}

		private HtmlElement makeInputBox(final String line, final String action)
		{
			return H.div().child(H.form().action(action).child(H.text(line == null ? "" : line).cls("question").autofocus().size(50).name("q")).child(H.button("Go").cls("ask")).end());
		}

		private HtmlElement makeTooltip(final HtmlElement main, final HtmlElement aux)
		{
			return H.a().cls("info").child(main).child(H.span().cls("tooltip").child(aux));
		}

		private HtmlElement makeTooltip(final HtmlElement main, final HtmlElement aux, final String link)
		{
			return H.a().href(link).cls("info").child(main).child(H.span().cls("tooltip").child(aux));
		}

		public final String freebaseWebsite = "http://www.freebase.com/";

		public String id2website(final String id)
		{
			assert id.startsWith("fb:") : id;
			return freebaseWebsite + id.substring(3).replaceAll("\\.", "/");
		}

		HtmlElement valueToElem(final Value value)
		{
			if (value == null)
				return H.span();
			if (value instanceof NameValue)
			{
				final NameValue nameValue = (NameValue) value;
				return H.a().href(id2website(nameValue._id)).child(nameValue._description == null ? nameValue._id : nameValue._description);
			}
			else
				if (value instanceof NumberValue)
				{
					final NumberValue numberValue = (NumberValue) value;
					return H.span().child(Fmt.D(numberValue._value) + (numberValue._unit.equals(NumberValue.unitless) ? "" : " " + numberValue._unit));
				}
				else
					if (value instanceof UriValue)
					{
						final UriValue uriValue = (UriValue) value;
						return H.a().href(uriValue.value).child(uriValue.value);
					}
					else
						if (value instanceof DateValue)
						{
							final DateValue dateValue = (DateValue) value;
							return H.span().child(dateValue.year + (dateValue.month == -1 ? "" : "-" + dateValue.month + (dateValue.day == -1 ? "" : "-" + dateValue.day)));
						}
						else
							if (value instanceof StringValue)
								return H.span().child(((StringValue) value).value);
							else
								if (value instanceof TableValue)
								{
									final HtmlElement table = H.table().cls("valueTable");
									final HtmlElement header = H.tr();
									boolean first = true;
									for (final String item : ((TableValue) value).header)
									{
										if (!first)
											header.child(H.td("&nbsp;&nbsp;&nbsp;"));
										first = false;
										header.child(H.td(H.b(item)));
									}
									table.child(header);
									for (final List<Value> rowValues : ((TableValue) value).rows)
									{
										final HtmlElement row = H.tr();
										first = true;
										for (final Value x : rowValues)
										{
											// TODO(pliang): add horizontal spacing only using CSS
											if (!first)
												row.child(H.td("&nbsp;&nbsp;&nbsp;"));
											first = false;
											row.child(H.td(valueToElem(x)));
										}
										table.child(row);
									}
									return table;
								}
								else
									// Default rendering
									return H.span().child(value.toString());
		}

		private HtmlElement makeAnswerBox(final Master.Response response, final String uri)
		{
			HtmlElement answer;
			if (response.getExample().getPredDerivations().size() == 0)
				answer = H.span().child("(none)");
			else
				answer = valueToElem(response.getDerivation().getValue());

			return H.table().child(H.tr().child(H.td(makeTooltip(H.span().cls("correctButton").child("[Correct]"), H.div().cls("bubble").child("If this answer is correct, click to add as a new training example!"), uri + "&accept=" + response.getCandidateIndex()))).child(H.td(H.span().cls("answer").child(answer))).end());
		}

		private HtmlElement makeGroup(final List<HtmlElement> items)
		{
			final HtmlElement table = H.table().cls("groupResponse");
			for (final HtmlElement item : items)
				table.child(H.tr().child(H.td(item)));
			return table;
		}

		HtmlElement makeDetails(final Master.Response response, final String uri)
		{
			final Example ex = response.getExample();
			final List<HtmlElement> items = new ArrayList<>();
			if (opts.htmlVerbose >= 1)
				items.add(makeLexical(ex));
			if (ex.getPredDerivations().size() > 0)
			{
				if (opts.htmlVerbose >= 1)
				{
					items.add(makeDerivation(ex, response.getDerivation(), true));
					items.add(makeFeatures(response.getDerivation(), false));
				}
				items.add(makeCandidates(ex, uri));
			}

			return H.div().cls("details").child(makeGroup(items));
		}

		HtmlElement makeDerivation(final Example ex, final Derivation deriv, final boolean moreInfo)
		{
			final HtmlElement table = H.table();

			// Show the derivation
			table.child(H.tr().child(H.td(makeDerivationHelper(ex, deriv, "", moreInfo))));

			final String header = "Derivation";
			return H.div().child(H.span().cls("listHeader").child(header)).child(table);
		}

		HtmlElement makeDerivationHelper(final Example ex, final Derivation deriv, final String indent, final boolean moreInfo)
		{
			// TODO(pliang): make this prettier
			HtmlElement cat;
			if (moreInfo)
			{
				final HtmlElement tooltip = H.div();
				tooltip.child(H.span(deriv.rule.toString()).cls("nowrap"));
				tooltip.child(makeFeatures(deriv, true));
				cat = makeTooltip(H.span(deriv.cat), tooltip);
			}
			else
				cat = H.span(deriv.cat);
			final String description = cat + "[&nbsp;" + H.span().child(ex.phraseString(deriv.start, deriv.end)).cls("word") + "]" + " &rarr; " + deriv.formula;
			final HtmlElement node = H.div().child(indent + description);
			for (final Derivation child : deriv.children)
				node.child(makeDerivationHelper(ex, child, indent + "&nbsp;&nbsp;&nbsp;&nbsp;", moreInfo));
			return node;
		}

		HtmlElement makeFeatures(final Derivation deriv, final boolean local)
		{
			final HtmlElement table = H.table();

			final Params params = master.getParams();
			final Map<String, Double> features = new HashMap<>();
			if (local)
				deriv.incrementLocalFeatureVector(1, features);
			else
				deriv.incrementAllFeatureVector(1, features);

			final List<Map.Entry<String, Double>> entries = Lists.newArrayList();
			double sumValue = 0;
			for (final Map.Entry<String, Double> entry : features.entrySet())
			{
				final String feature = entry.getKey();
				if (entry.getValue() == 0)
					continue;
				final double value = entry.getValue() * params.getWeight(feature);
				sumValue += value;
				entries.add(new java.util.AbstractMap.SimpleEntry<>(feature, value));
			}
			Collections.sort(entries, new ValueComparator<String, Double>(false));
			table.child(H.tr().child(H.td(H.b("Feature"))).child(H.td(H.b("Value"))).child(H.td(H.b("Weight"))));

			for (final Map.Entry<String, Double> entry : entries)
			{
				final String feature = entry.getKey();
				final double value = entry.getValue();
				final double weight = params.getWeight(feature);
				table.child(H.tr().child(H.td(feature)).child(H.td(Fmt.D(MapUtils.getDouble(features, feature, 0)))).child(H.td(Fmt.D(weight))));
			}

			String header;
			if (local)
			{
				final double localScore = deriv.localScore(params);
				final double score = deriv.getScore();
				if (deriv.children == null)
					header = String.format("Local features (score = %s)", Fmt.D(score));
				else
					header = String.format("Local features (score = %s + %s = %s)", Fmt.D(score - localScore), Fmt.D(localScore), Fmt.D(score));
			}
			else
				header = String.format("All features (score=%s, prob=%s)", Fmt.D(deriv.getScore()), Fmt.D(deriv.getProb()));
			return H.div().child(H.span().cls("listHeader").child(header)).child(table);
		}

		HtmlElement linkSelect(final int index, final String uri, final String str)
		{
			return H.a().href(uri + "&select=" + index).child(str);
		}

		private HtmlElement makeCandidates(final Example ex, final String uri)
		{
			final HtmlElement table = H.table().cls("candidateTable");
			final HtmlElement header = H.tr().child(H.td(H.b("Rank"))).child(H.td(H.b("Score"))).child(H.td(H.b("Answer")));
			if (opts.htmlVerbose >= 1)
				header.child(H.td(H.b("Formula")));
			table.child(header);
			for (int i = 0; i < ex.getPredDerivations().size(); i++)
			{
				final Derivation deriv = ex.getPredDerivations().get(i);

				final HtmlElement correct = makeTooltip(H.span().cls("correctButton").child("[Correct]"), H.div().cls("bubble").child("If this answer is correct, click to add as a new training example!"), uri + "&accept=" + i);
				final String value = shorten(deriv.getValue() == null ? "" : deriv.getValue().toString(), 200);
				final HtmlElement formula = makeTooltip(H.span(deriv.getFormula().toString()), H.div().cls("nowrap").child(makeDerivation(ex, deriv, false)), uri + "&select=" + i);
				final HtmlElement row = H.tr().child(H.td(linkSelect(i, uri, i + " " + correct)).cls("nowrap")).child(H.td(Fmt.D(deriv.getScore()))).child(H.td(value)).style("width:250px");
				if (opts.htmlVerbose >= 1)
					row.child(H.td(formula));
				table.child(row);
			}
			return H.div().child(H.span().cls("listHeader").child("Candidates")).child(table);
		}

		private String shorten(final String s, final int n)
		{
			if (s.length() <= n)
				return s;
			return s.substring(0, n / 2) + "..." + s.substring(s.length() - n / 2);
		}

		private void markLexical(final Derivation deriv, final CandidatePredicates[] predicates)
		{
			// TODO(pliang): generalize this to the case where the formula is a
			// NameFormula but the child is a StringFormula?
			if (deriv.getRule() != null && deriv.getRule().getSem() != null && deriv.getRule().getSem().getClass().getSimpleName().equals("LexiconFn"))
				predicates[deriv.getStart()].add(deriv.getFormula(), deriv.getEnd() - deriv.getStart(), deriv.getScore());
			for (final Derivation child : deriv.getChildren())
				markLexical(child, predicates);
		}

		class CandidatePredicates
		{
			// Parallel arrays
			List<Formula> predicates = new ArrayList<>();
			List<Integer> spanLengths = new ArrayList<>();
			List<Double> scores = new ArrayList<>();

			void add(final Formula formula, final int spanLength, final double score)
			{
				predicates.add(formula);
				spanLengths.add(spanLength);
				scores.add(score);
			}

			int size()
			{
				return predicates.size();
			}

			String formatPredicate(final int i)
			{
				return predicates.get(i).toString() + (spanLengths.get(i) == 1 ? "" : " [" + spanLengths.get(i) + "]");
			}
		}

		// Move to fig
		double[] toDoubleArray(final List<Double> l)
		{
			final double[] a = new double[l.size()];
			for (int i = 0; i < l.size(); i++)
				a[i] = l.get(i);
			return a;
		}

		HtmlElement makeLexical(final Example ex)
		{
			final HtmlElement predicatesElem = H.tr();
			final HtmlElement tokensElem = H.tr();

			// Mark all the predicates used in any derivation on the beam.
			// Note: this is not all possible.
			final CandidatePredicates[] predicates = new CandidatePredicates[ex.getTokens().size()];
			for (int i = 0; i < ex.getTokens().size(); i++)
				predicates[i] = new CandidatePredicates();
			for (final Derivation deriv : ex.getPredDerivations())
				markLexical(deriv, predicates);

			// Build up |predicatesElem| and |tokensElem|
			for (int i = 0; i < ex.getTokens().size(); i++)
			{
				tokensElem.child(H.td(makeTooltip(H.span().cls("word").child(ex.getTokens().get(i)), H.span().cls("tag").child("POS: " + ex.languageInfo.posTags.get(i)), "")));

				if (predicates[i].size() == 0)
					predicatesElem.child(H.td(""));
				else
				{
					// Show possible predicates for a word
					final HtmlElement pe = H.table().cls("predInfo");
					final int[] perm = ListUtils.sortedIndices(toDoubleArray(predicates[i].scores), true);
					final Set<String> formulaSet = new HashSet<>();
					for (final int j : perm)
					{
						final String formula = predicates[i].formatPredicate(j);
						if (formulaSet.contains(formula))
							continue; // Dedup
						formulaSet.add(formula);
						final double score = predicates[i].scores.get(j);
						pe.child(H.tr().child(H.td(formula)).child(H.td(Fmt.D(score))));
					}
					predicatesElem.child(H.td(makeTooltip(H.span().child(predicates[i].formatPredicate(perm[0])), pe, "")));
				}
			}

			return H.div().cls("lexicalResponse").child(H.span().cls("listHeader").child("Lexical Triggers")).child(H.table().child(predicatesElem).child(tokensElem));
		}

		String makeJson(final Master.Response response)
		{
			final Map<String, Object> json = new HashMap<>();
			final List<Object> items = new ArrayList<>();
			json.put("candidates", items);
			for (final Derivation deriv : response.getExample().getPredDerivations())
			{
				final Map<String, Object> item = new HashMap<>();
				final Value value = deriv.getValue();
				if (value instanceof UriValue)
					item.put("url", ((UriValue) value).value);
				else
					if (value instanceof TableValue)
					{
						final TableValue tableValue = (TableValue) value;
						item.put("header", tableValue.header);
						final List<List<String>> rowsObj = new ArrayList<>();
						item.put("rows", rowsObj);
						for (final List<Value> row : tableValue.rows)
						{
							final List<String> rowObj = new ArrayList<>();
							for (final Value v : row)
								rowObj.add(v.toString());
							rowsObj.add(rowObj);
						}
					}
					else
						item.put("value", value.toString());
				item.put("score", deriv.score);
				item.put("prob", deriv.prob);
				items.add(item);
			}

			return Json.writeValueAsStringHard(json);
		}

		// Catch exception if any.
		Master.Response processQuery(final Session session, final String query)
		{
			try
			{
				return master.processQuery(session, query);
			}
			catch (final Throwable t)
			{
				t.printStackTrace();
				return null;
			}
		}

		// If query is not already the last query, make it the last query.
		boolean ensureQueryIsLast(final Session session, final String query)
		{
			if (query != null && !query.equals(session.getLastQuery()))
			{
				final Master.Response response = processQuery(session, query);
				if (response == null)
					return false;
			}
			return true;
		}

		void handleQuery(final String sessionId) throws IOException
		{
			String query = reqParams.get("q");

			// If JSON, don't store cookies.
			final Session session = master.getSession(sessionId);
			session.remoteHost = remoteHost;
			session.format = format;

			if (query == null)
				query = session.getLastQuery();
			if (query == null)
				query = "";
			logs("Server.handleQuery %s: %s", session.id, query);

			// Print header
			if (jsonFormat())
				setHeaders("application/json");
			else
				setHeaders("text/html");
			final PrintWriter out = new PrintWriter(new OutputStreamWriter(exchange.getResponseBody()));
			if (!jsonFormat())
			{
				out.println("<meta http-equiv=\"Content-Type\" content=\"text/html; charset=UTF-8\">");
				out.println(H.html().open());
				out.println(H.head().child(H.title(opts.title)).child(H.link().rel("stylesheet").type("text/css").href("main.css")).child(H.script().src("main.js")).end());

				out.println(H.body().open());

				if (opts.headerPath != null)
					for (final String line : IOUtils.readLinesHard(opts.headerPath))
						out.println(line);
			}

			final String uri = exchange.getRequestURI().toString();

			// Encode the URL parameters into the freeform text.
			// A bit backwards, but keeps uniformity.
			final String select = reqParams.get("select");
			if (select != null)
				if (ensureQueryIsLast(session, query))
					query = LispTree.proto.newList("select", select).toString();
				else
					query = null;
			final String accept = reqParams.get("accept");
			if (accept != null)
				if (ensureQueryIsLast(session, query))
					query = LispTree.proto.newList("accept", accept).toString();
				else
					query = null;

			// Handle the request
			Master.Response masterResponse = null;
			if (query != null)
				masterResponse = processQuery(session, query);

			// Print history of exchanges
			if (session.context.exchanges.size() > 0 && !jsonFormat())
			{
				final HtmlElement context = H.table().cls("context");
				for (final ContextValue.Exchange e : session.context.exchanges)
				{
					final HtmlElement row = H.tr().child(H.td(H.span().cls("word").child(e.utterance)));
					row.child(H.td(H.span("&nbsp;&nbsp;&nbsp;&nbsp;"))).child(H.td(e.value.toString()));
					if (opts.htmlVerbose >= 1)
						row.child(H.td(H.span("&nbsp;&nbsp;&nbsp;&nbsp;"))).child(H.td(e.formula.toString()));
					context.child(row);
				}
				out.println(context.toString());
			}

			// Print input box for new utterance
			if (!jsonFormat())
			{
				final String defaultQuery = query != null ? query : session.getLastQuery();
				out.println(makeInputBox(defaultQuery, uri).toString());
			}

			if (masterResponse != null)
			{
				// Render answer
				final Example ex = masterResponse.getExample();
				if (ex != null)
					if (!jsonFormat())
					{
						out.println(makeAnswerBox(masterResponse, uri).toString());
						out.println(makeDetails(masterResponse, uri).toString());
					}
					else
						out.println(makeJson(masterResponse));

				if (!jsonFormat() && opts.htmlVerbose >= 1)
				{
					// Write response to user
					out.println(H.elem("pre").open());
					for (final String outLine : masterResponse.getLines())
						out.println(outLine);
					out.println(H.elem("pre").close());
				}
			}
			else
				if (query != null && !jsonFormat())
					out.println(H.span("Internal error!").cls("error"));

			if (!jsonFormat())
			{
				out.println(H.body().close());
				out.println(H.html().close());
			}

			out.close();
		}

		void getResults() throws IOException
		{
			setHeaders("application/json");
			final Map<String, String> map = new HashMap<>();
			map.put("a", "3");
			map.put("b", "4");

			final PrintWriter writer = new PrintWriter(new OutputStreamWriter(exchange.getResponseBody()));
			writer.println(Json.writeValueAsStringHard(map));
			writer.close();
		}

		void getFile(final String path) throws IOException
		{
			if (!new File(path).exists())
			{
				LogInfo.logs("File doesn't exist: %s", path);
				exchange.sendResponseHeaders(404, 0); // File not found
				return;
			}

			setHeaders(getMimeType(path));
			if (opts.verbose >= 2)
				LogInfo.logs("Sending %s", path);
			final OutputStream out = new BufferedOutputStream(exchange.getResponseBody());
			final InputStream in = new FileInputStream(path);
			IOUtils.copy(in, out);
		}
	}

	public Server(final Master master_)
	{
		master = master_;
	}

	void run()
	{
		try
		{
			final String hostname = fig.basic.SysInfoUtils.getHostName();
			final HttpServer server = HttpServer.create(new InetSocketAddress(opts.port), 10);
			final ExecutorService pool = Executors.newFixedThreadPool(opts.numThreads);
			server.createContext("/", new Handler());
			server.setExecutor(pool);
			server.start();
			LogInfo.logs("Server started at http://%s:%s/sempre", hostname, opts.port);
			LogInfo.log("Press Ctrl-D to terminate.");
			while (LogInfo.stdin.readLine() != null)
			{
			}
			LogInfo.log("Shutting down server...");
			server.stop(0);
			LogInfo.log("Shutting down executor pool...");
			pool.shutdown();
		}
		catch (final IOException e)
		{
			throw new RuntimeException(e);
		}
	}
}
