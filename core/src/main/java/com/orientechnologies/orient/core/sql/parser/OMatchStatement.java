/* Generated By:JJTree: Do not edit this line. OMatchStatement.java Version 4.3 */
/* JavaCCOptions:MULTI=true,NODE_USES_PARSER=false,VISITOR=true,TRACK_TOKENS=true,NODE_PREFIX=O,NODE_EXTENDS=,NODE_FACTORY=,SUPPORT_CLASS_VISIBILITY_PUBLIC=true */
package com.orientechnologies.orient.core.sql.parser;

import com.orientechnologies.common.listener.OProgressListener;
import com.orientechnologies.common.util.OPair;
import com.orientechnologies.orient.core.command.*;
import com.orientechnologies.orient.core.db.document.ODatabaseDocument;
import com.orientechnologies.orient.core.db.record.OIdentifiable;
import com.orientechnologies.orient.core.exception.OCommandExecutionException;
import com.orientechnologies.orient.core.metadata.schema.OClass;
import com.orientechnologies.orient.core.metadata.schema.OSchema;
import com.orientechnologies.orient.core.metadata.security.ORole;
import com.orientechnologies.orient.core.metadata.security.ORule;
import com.orientechnologies.orient.core.record.ORecord;
import com.orientechnologies.orient.core.record.impl.ODocument;
import com.orientechnologies.orient.core.sql.OCommandSQLParsingException;
import com.orientechnologies.orient.core.sql.OIterableRecordSource;
import com.orientechnologies.orient.core.sql.filter.OSQLTarget;
import com.orientechnologies.orient.core.sql.query.OResultSet;
import com.orientechnologies.orient.core.sql.query.OSQLAsynchQuery;
import com.orientechnologies.orient.core.sql.query.OSQLSynchQuery;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.*;

public class OMatchStatement extends OStatement implements OCommandExecutor, OIterableRecordSource {

  String                             DEFAULT_ALIAS_PREFIX = "$ORIENT_DEFAULT_ALIAS_";

  private OSQLAsynchQuery<ODocument> request;

  long                               threshold            = 5;

  class MatchContext {
    int                        currentEdgeNumber = 0;

    Map<String, Iterable>      candidates        = new LinkedHashMap<String, Iterable>();
    Map<String, OIdentifiable> matched           = new LinkedHashMap<String, OIdentifiable>();
    Map<PatternEdge, Boolean>  matchedEdges      = new IdentityHashMap<PatternEdge, Boolean>();

    public MatchContext copy(String alias, OIdentifiable value) {
      MatchContext result = new MatchContext();

      result.candidates.putAll(candidates);
      result.candidates.remove(alias);

      result.matched.putAll(matched);
      result.matched.put(alias, value);

      result.matchedEdges.putAll(matchedEdges);
      return result;
    }
  }

  public static class EdgeTraversal {
    boolean     out = true;
    PatternEdge edge;

    public EdgeTraversal(PatternEdge edge, boolean out) {
      this.edge = edge;
      this.out = out;
    }
  }

  public static class MatchExecutionPlan {
    public List<EdgeTraversal> sortedEdges;
    public Map<String, Long>   preFetchedAliases = new HashMap<String, Long>();
    public String              rootAlias;
  }

  public static final String       KEYWORD_MATCH    = "MATCH";
  // parsed data
  protected List<OMatchExpression> matchExpressions = new ArrayList<OMatchExpression>();
  protected List<OIdentifier>      returnItems      = new ArrayList<OIdentifier>();

  protected Pattern                pattern;

  // execution data
  private OCommandContext          context;
  private OProgressListener        progressListener;

  public OMatchStatement() {
    super(-1);
  }

  public OMatchStatement(int id) {
    super(id);
  }

  public OMatchStatement(OrientSql p, int id) {
    super(p, id);
  }

  /**
   * Accept the visitor. *
   */
  public Object jjtAccept(OrientSqlVisitor visitor, Object data) {
    return visitor.visit(this, data);
  }

  @Override
  public <RET extends OCommandExecutor> RET parse(OCommandRequest iRequest) {
    final OCommandRequestText textRequest = (OCommandRequestText) iRequest;
    if (iRequest instanceof OSQLSynchQuery) {
      request = (OSQLSynchQuery<ODocument>) iRequest;
    } else if (iRequest instanceof OSQLAsynchQuery) {
      request = (OSQLAsynchQuery<ODocument>) iRequest;
    } else {
      // BUILD A QUERY OBJECT FROM THE COMMAND REQUEST
      request = new OSQLSynchQuery<ODocument>(textRequest.getText());
      if (textRequest.getResultListener() != null) {
        request.setResultListener(textRequest.getResultListener());
      }
    }
    String queryText = textRequest.getText();

    // please, do not look at this... refactor this ASAP with new executor structure
    final InputStream is = new ByteArrayInputStream(queryText.getBytes());
    final OrientSql osql = new OrientSql(is);
    try {
      OMatchStatement result = (OMatchStatement) osql.parse();
      this.matchExpressions = result.matchExpressions;
      this.returnItems = result.returnItems;
    } catch (ParseException e) {
      throw new OCommandSQLParsingException(e.getMessage(), e);
    }

    assignDefaultAliases(this.matchExpressions);
    pattern = new Pattern();
    for (OMatchExpression expr : this.matchExpressions) {
      pattern.addExpression(expr);
    }
    // TODO CHECK CORRECT RETURN STATEMENT!

    return (RET) this;
  }

  private void assignDefaultAliases(List<OMatchExpression> matchExpressions) {

    int counter = 0;
    for (OMatchExpression expression : matchExpressions) {
      if (expression.origin.getAlias() == null) {
        expression.origin.setAlias(DEFAULT_ALIAS_PREFIX + (counter++));
      }

      for (OMatchPathItem item : expression.items) {
        if (item.filter == null) {
          item.filter = new OMatchFilter(-1);
        }
        if (item.filter.getAlias() == null) {
          item.filter.setAlias(DEFAULT_ALIAS_PREFIX + (counter++));
        }
      }
    }
  }

  @Override
  public Object execute(Map<Object, Object> iArgs) {
    this.context.setInputParameters(iArgs);
    return execute(this.request, this.context);
  }

  public Object execute(OSQLAsynchQuery<ODocument> request, OCommandContext context) {
    Map<Object, Object> iArgs = context.getInputParameters();
    try {
      Map<String, OWhereClause> aliasFilters = new LinkedHashMap<String, OWhereClause>();
      Map<String, String> aliasClasses = new LinkedHashMap<String, String>();
      for (OMatchExpression expr : this.matchExpressions) {
        addAliases(expr, aliasFilters, aliasClasses);
      }

      Map<String, Long> estimatedRootEntries = estimateRootEntries(aliasClasses, aliasFilters);
      if (estimatedRootEntries.values().contains(0l)) {
        return new OResultSet();// some aliases do not match on any classes
      }

      List<EdgeTraversal> sortedEdges = sortEdges(estimatedRootEntries, pattern);
      MatchExecutionPlan executionPlan = new MatchExecutionPlan();
      executionPlan.sortedEdges = sortedEdges;

      calculateMatch(estimatedRootEntries, new MatchContext(), aliasClasses, aliasFilters, context, request, executionPlan);
      return getResult(request);
    } finally {

      if (request.getResultListener() != null) {
        request.getResultListener().end();
      }
    }

  }

  /*
   * sort edges in the order they will be matched
   */
  private List<EdgeTraversal> sortEdges(Map<String, Long> estimatedRootEntries, Pattern pattern) {
    List<EdgeTraversal> result = new ArrayList<EdgeTraversal>();

    List<OPair<Long, String>> rootWeights = new ArrayList<OPair<Long, String>>();
    for (Map.Entry<String, Long> root : estimatedRootEntries.entrySet()) {
      rootWeights.add(new OPair<Long, String>(root.getValue(), root.getKey()));
    }
    Collections.sort(rootWeights);

    Set<PatternEdge> traversedEdges = new HashSet<PatternEdge>();
    Set<PatternNode> traversedNodes = new HashSet<PatternNode>();
    List<PatternNode> nextNodes = new ArrayList<PatternNode>();

    while (result.size() < pattern.getNumOfEdges()) {

      for (OPair<Long, String> rootPair : rootWeights) {
        PatternNode root = pattern.get(rootPair.getValue());
        if (!traversedNodes.contains(root)) {
          nextNodes.add(root);
          break;
        }
      }

      while (!nextNodes.isEmpty()) {
        PatternNode node = nextNodes.remove(0);
        traversedNodes.add(node);
        for (PatternEdge edge : node.out) {
          if (!traversedEdges.contains(edge)) {
            result.add(new EdgeTraversal(edge, true));
            traversedEdges.add(edge);
            if (!traversedNodes.contains(edge.in) && !nextNodes.contains(edge.in)) {
              nextNodes.add(edge.in);
            }
          }
        }
        for (PatternEdge edge : node.in) {
          if (!traversedEdges.contains(edge) && edge.item.isBidirectional()) {
            result.add(new EdgeTraversal(edge, false));
            traversedEdges.add(edge);
            if (!traversedNodes.contains(edge.out) && !nextNodes.contains(edge.out)) {
              nextNodes.add(edge.out);
            }
          }
        }
      }
    }

    return result;
  }

  protected Object getResult(OSQLAsynchQuery<ODocument> request) {
    if (request instanceof OSQLSynchQuery)
      return ((OSQLSynchQuery<ODocument>) request).getResult();

    return null;
  }

  private boolean calculateMatch(Map<String, Long> estimatedRootEntries, MatchContext matchContext,
      Map<String, String> aliasClasses, Map<String, OWhereClause> aliasFilters, OCommandContext iCommandContext,
      OSQLAsynchQuery<ODocument> request, MatchExecutionPlan executionPlan) {
    return calculateMatch(pattern, estimatedRootEntries, matchContext, aliasClasses, aliasFilters, iCommandContext, request,
        executionPlan);

  }

  private boolean calculateMatch(Pattern pattern, Map<String, Long> estimatedRootEntries, MatchContext matchContext,
      Map<String, String> aliasClasses, Map<String, OWhereClause> aliasFilters, OCommandContext iCommandContext,
      OSQLAsynchQuery<ODocument> request, MatchExecutionPlan executionPlan) {

    MatchContext rootContext = new MatchContext();

    boolean rootFound = false;
    // find starting nodes with few entries
    for (Map.Entry<String, Long> entryPoint : estimatedRootEntries.entrySet()) {
      if (entryPoint.getValue() < threshold) {
        String nextAlias = entryPoint.getKey();
        Iterable<OIdentifiable> matches = calculateMatches(nextAlias, aliasFilters, iCommandContext, aliasClasses);

        Set<OIdentifiable> ids = new HashSet<OIdentifiable>();
        if (!matches.iterator().hasNext()) {
          return true;
        }

        rootContext.candidates.put(nextAlias, matches);
        executionPlan.preFetchedAliases.put(nextAlias, entryPoint.getValue());
        rootFound = true;
      }
    }
    // no nodes under threshold, guess the smallest one
    if (!rootFound) {
      String nextAlias = getNextAlias(estimatedRootEntries, matchContext);
      Iterable<OIdentifiable> matches = calculateMatches(nextAlias, aliasFilters, iCommandContext, aliasClasses);
      if (!matches.iterator().hasNext()) {
        return true;
      }
      rootContext.candidates.put(nextAlias, matches);
      executionPlan.preFetchedAliases.put(nextAlias, estimatedRootEntries.get(nextAlias));
    }

    EdgeTraversal firstEdge = executionPlan.sortedEdges.size() == 0 ? null : executionPlan.sortedEdges.get(0);
    String smallestAlias = null;
    if (firstEdge != null) {
      smallestAlias = firstEdge.out ? firstEdge.edge.out.alias : firstEdge.edge.in.alias;
    } else {
      smallestAlias = pattern.aliasToNode.values().iterator().next().alias;
    }
    executionPlan.rootAlias = smallestAlias;
    Iterable<OIdentifiable> allCandidates = rootContext.candidates.get(smallestAlias);

    for (OIdentifiable id : allCandidates) {
      MatchContext childContext = rootContext.copy(smallestAlias, id);
      childContext.currentEdgeNumber = 0;
      if (!processContext(pattern, executionPlan, childContext, aliasClasses, aliasFilters, iCommandContext, request)) {
        return false;
      }
    }
    return true;
  }

  private Iterable<OIdentifiable> calculateMatches(String nextAlias, Map<String, OWhereClause> aliasFilters,
      OCommandContext iCommandContext, Map<String, String> aliasClasses) {
    Iterable<OIdentifiable> it = query(aliasClasses.get(nextAlias), aliasFilters.get(nextAlias), iCommandContext);
    Set<OIdentifiable> result = new HashSet<OIdentifiable>();
    // TODO dirty work around, review it. The iterable returned by the query just does not work.
    for (OIdentifiable id : it) {
      result.add(id.getIdentity());
    }

    return result;
  }

  private boolean processContext(Pattern pattern, MatchExecutionPlan executionPlan, MatchContext matchContext,
      Map<String, String> aliasClasses, Map<String, OWhereClause> aliasFilters, OCommandContext iCommandContext,
      OSQLAsynchQuery<ODocument> request) {

    if (pattern.getNumOfEdges() == matchContext.matchedEdges.size() && allNodesCalculated(matchContext, pattern)) {
      addResult(matchContext, request);
      return true;
    }
    if (executionPlan.sortedEdges.size() == matchContext.currentEdgeNumber) {
      expandCartesianProduct(pattern, matchContext, aliasClasses, aliasFilters, iCommandContext, request);
      return true;
    }
    EdgeTraversal currentEdge = executionPlan.sortedEdges.get(matchContext.currentEdgeNumber);
    PatternNode rootNode = currentEdge.out ? currentEdge.edge.out : currentEdge.edge.in;

    if (currentEdge.out) {
      PatternEdge outEdge = currentEdge.edge;

      if (!matchContext.matchedEdges.containsKey(outEdge)) {

        Object rightValues = executeTraversal(matchContext, iCommandContext, outEdge, matchContext.matched.get(outEdge.out.alias),
            0);
        if (!(rightValues instanceof Iterable)) {
          rightValues = Collections.singleton(rightValues);
        }
        for (OIdentifiable rightValue : (Iterable<OIdentifiable>) rightValues) {
          Iterable<OIdentifiable> prevMatchedRightValues = matchContext.candidates.get(outEdge.in.alias);

          if (matchContext.matched.containsKey(outEdge.in.alias)) {
            if (matchContext.matched.get(outEdge.in.alias).getIdentity().equals(rightValue.getIdentity())) {
              MatchContext childContext = matchContext.copy(outEdge.in.alias, rightValue.getIdentity());
              childContext.currentEdgeNumber = matchContext.currentEdgeNumber + 1;
              childContext.matchedEdges.put(outEdge, true);
              if (!processContext(pattern, executionPlan, childContext, aliasClasses, aliasFilters, iCommandContext, request)) {
                return false;
              }
              break;
            }
          } else if (prevMatchedRightValues != null && prevMatchedRightValues.iterator().hasNext()) {// just matching against
                                                                                                     // known
            // values
            for (OIdentifiable id : prevMatchedRightValues) {
              if (id.getIdentity().equals(rightValue.getIdentity())) {
                MatchContext childContext = matchContext.copy(outEdge.in.alias, id);
                childContext.currentEdgeNumber = matchContext.currentEdgeNumber + 1;
                childContext.matchedEdges.put(outEdge, true);
                if (!processContext(pattern, executionPlan, childContext, aliasClasses, aliasFilters, iCommandContext, request)) {
                  return false;
                }
              }
            }
          } else {// searching for neighbors
            MatchContext childContext = matchContext.copy(outEdge.in.alias, rightValue.getIdentity());
            childContext.currentEdgeNumber = matchContext.currentEdgeNumber + 1;
            childContext.matchedEdges.put(outEdge, true);
            if (!processContext(pattern, executionPlan, childContext, aliasClasses, aliasFilters, iCommandContext, request)) {
              return false;
            }
          }
        }
      }
    } else {
      PatternEdge inEdge = currentEdge.edge;
      if (!matchContext.matchedEdges.containsKey(inEdge)) {
        if (!inEdge.item.isBidirectional()) {
          throw new RuntimeException("Invalid pattern to match!");
        }
        if (!matchContext.matchedEdges.containsKey(inEdge)) {
          Object leftValues = inEdge.item.method.executeReverse(matchContext.matched.get(inEdge.in.alias), iCommandContext);
          if (!(leftValues instanceof Iterable)) {
            leftValues = Collections.singleton(leftValues);
          }
          for (OIdentifiable leftValue : (Iterable<OIdentifiable>) leftValues) {
            Iterable<OIdentifiable> prevMatchedRightValues = matchContext.candidates.get(inEdge.out.alias);

            if (matchContext.matched.containsKey(inEdge.out.alias)) {
              if (matchContext.matched.get(inEdge.out.alias).getIdentity().equals(leftValue.getIdentity())) {
                MatchContext childContext = matchContext.copy(inEdge.out.alias, leftValue.getIdentity());
                childContext.currentEdgeNumber = matchContext.currentEdgeNumber + 1;
                childContext.matchedEdges.put(inEdge, true);
                if (!processContext(pattern, executionPlan, childContext, aliasClasses, aliasFilters, iCommandContext, request)) {
                  return false;
                }
                break;
              }
            } else if (prevMatchedRightValues != null && prevMatchedRightValues.iterator().hasNext()) {// just matching against
              // known
              // values
              for (OIdentifiable id : prevMatchedRightValues) {
                if (id.getIdentity().equals(leftValue.getIdentity())) {
                  MatchContext childContext = matchContext.copy(inEdge.out.alias, id);
                  childContext.currentEdgeNumber = matchContext.currentEdgeNumber + 1;
                  childContext.matchedEdges.put(inEdge, true);

                  if (!processContext(pattern, executionPlan, childContext, aliasClasses, aliasFilters, iCommandContext, request)) {
                    return false;
                  }
                }
              }
            } else {// searching for neighbors
              OWhereClause where = aliasFilters.get(inEdge.out.alias);
              if (where == null || where.matchesFilters(leftValue, iCommandContext)) {
                MatchContext childContext = matchContext.copy(inEdge.out.alias, leftValue.getIdentity());
                childContext.currentEdgeNumber = matchContext.currentEdgeNumber + 1;
                childContext.matchedEdges.put(inEdge, true);
                if (!processContext(pattern, executionPlan, childContext, aliasClasses, aliasFilters, iCommandContext, request)) {
                  return false;
                }
              }
            }
          }
        }
      }
    }
    return true;
  }

  private void expandCartesianProduct(Pattern pattern, MatchContext matchContext, Map<String, String> aliasClasses,
      Map<String, OWhereClause> aliasFilters, OCommandContext iCommandContext, OSQLAsynchQuery<ODocument> request) {
    for (String alias : pattern.aliasToNode.keySet()) {
      if (!matchContext.matched.containsKey(alias)) {
        String target = aliasClasses.get(alias);
        if (target == null) {
          throw new OCommandExecutionException("Cannot execute MATCH statement on alias " + alias + ": class not defined");
        }

        Iterable<OIdentifiable> values = calculateMatches(alias, aliasFilters, iCommandContext, aliasClasses);
        for (OIdentifiable id : values) {
          MatchContext childContext = matchContext.copy(alias, id);
          if (allNodesCalculated(childContext, pattern)) {
            addResult(childContext, request);
          } else {
            expandCartesianProduct(pattern, childContext, aliasClasses, aliasFilters, iCommandContext, request);
          }
        }
        break;
      }
    }
  }

  private boolean allNodesCalculated(MatchContext matchContext, Pattern pattern) {
    for (String alias : pattern.aliasToNode.keySet()) {
      if (!matchContext.matched.containsKey(alias)) {
        return false;
      }
    }
    return true;
  }

  private Iterable<OIdentifiable> executeTraversal(MatchContext matchContext, OCommandContext iCommandContext, PatternEdge outEdge,
      OIdentifiable startingPoint, int depth) {

    OWhereClause filter = outEdge.item.filter.getFilter();
    OWhereClause whileCondition = outEdge.item.filter.getWhileCondition();
    Integer maxDepth = outEdge.item.filter.getMaxDepth();

    Set<OIdentifiable> result = new HashSet<OIdentifiable>();

    if (whileCondition == null && maxDepth == null) {// in this case starting point is not returned and only one level depth is
                                                     // evaluated
      Iterable<OIdentifiable> queryResult = traversePatternEdge(startingPoint, outEdge, iCommandContext);

      if (outEdge.item.filter == null || outEdge.item.filter.getFilter() == null) {
        return queryResult;
      }

      for (OIdentifiable origin : queryResult) {
        if (filter == null || filter.matchesFilters(origin, iCommandContext)) {
          result.add(origin);
        }
      }
    } else {// in this case also zero level (starting point) is considered and traversal depth is given by the while condition
      iCommandContext.setVariable("$depth", depth);
      if (filter == null || filter.matchesFilters(startingPoint, iCommandContext)) {
        result.add(startingPoint);
      }
      if ((maxDepth == null || depth < maxDepth)
          && (whileCondition == null || whileCondition.matchesFilters(startingPoint, iCommandContext))) {

        Iterable<OIdentifiable> queryResult = traversePatternEdge(startingPoint, outEdge, iCommandContext);

        for (OIdentifiable origin : queryResult) {
          // TODO consider break strategies (eg. re-traverse nodes)
          Iterable<OIdentifiable> subResult = executeTraversal(matchContext, iCommandContext, outEdge, origin, depth + 1);
          if (subResult instanceof Collection) {
            result.addAll((Collection<? extends OIdentifiable>) subResult);
          } else {
            for (OIdentifiable i : subResult) {
              result.add(i);
            }
          }
        }
      }
    }
    return result;
  }

  private Iterable<OIdentifiable> traversePatternEdge(OIdentifiable startingPoint, PatternEdge outEdge,
      OCommandContext iCommandContext) {
    if (outEdge.subPattern != null) {
      // TODO
    }
    Object qR = outEdge.item.method.execute(startingPoint, iCommandContext);
    return (qR instanceof Iterable) ? (Iterable) qR : Collections.singleton(qR);
  }

  private void addResult(MatchContext matchContext, OSQLAsynchQuery<ODocument> request) {
    if (returnsMatches()) {
      ODocument doc = getDatabase().newInstance();
      // TODO manage duplicates
      for (Map.Entry<String, OIdentifiable> entry : matchContext.matched.entrySet()) {
        if (isExplicitAlias(entry.getKey())) {
          doc.field(entry.getKey(), entry.getValue());
        }
      }
      Object result = getResult(request);

      if (request.getResultListener() != null) {
        request.getResultListener().result(doc);
      }
    } else if (returnsPaths()) {
      ODocument doc = getDatabase().newInstance();
      for (Map.Entry<String, OIdentifiable> entry : matchContext.matched.entrySet()) {
        doc.field(entry.getKey(), entry.getValue());
      }
      Object result = getResult(request);

      if (request.getResultListener() != null) {
        request.getResultListener().result(doc);
      }
    } else {
      ODocument doc = getDatabase().newInstance();
      for (OIdentifier alias : returnItems) {
        doc.field(alias.getValue(), matchContext.matched.get(alias.getValue()));
      }
      Object result = getResult(request);

      if (request.getResultListener() != null) {
        request.getResultListener().result(doc);
      }
    }
  }

  private boolean isExplicitAlias(String key) {
    if (key.startsWith(DEFAULT_ALIAS_PREFIX)) {
      return false;
    }
    return true;
  }

  private boolean returnsMatches() {
    for (OIdentifier item : returnItems) {
      if (item.getValue().equals("$matches")) {
        return true;
      }
    }
    return false;
  }

  private boolean returnsPaths() {
    for (OIdentifier item : returnItems) {
      if (item.getValue().equals("$paths")) {
        return true;
      }
    }
    return false;
  }

  private Iterable<OIdentifiable> query(String className, OWhereClause oWhereClause, OCommandContext ctx) {
    final ODatabaseDocument database = getDatabase();
    OClass schemaClass = database.getMetadata().getSchema().getClass(className);
    database.checkSecurity(ORule.ResourceGeneric.CLASS, ORole.PERMISSION_READ, schemaClass.getName().toLowerCase());

    Iterable<ORecord> baseIterable = fetchFromIndex(schemaClass, oWhereClause);
    // if (baseIterable == null) {
    // baseIterable = new ORecordIteratorClass<ORecord>((ODatabaseDocumentInternal) database, (ODatabaseDocumentInternal) database,
    // className, true, true);
    // }
    // Iterable<OIdentifiable> result = new FilteredIterator(baseIterable, oWhereClause);

    String text;

    if (oWhereClause == null) {
      text = "(select from " + className + ")";
    } else {
      text = "(select from " + className + " where " + oWhereClause.toString() + ")";
    }
    OSQLTarget target = new OSQLTarget(text, ctx, "where");

    return (Iterable) target.getTargetRecords();
  }

  private Iterable<ORecord> fetchFromIndex(OClass schemaClass, OWhereClause oWhereClause) {
    return null;// TODO
  }

  private String getNextAlias(Map<String, Long> estimatedRootEntries, MatchContext matchContext) {
    Map.Entry<String, Long> lowerValue = null;
    for (Map.Entry<String, Long> entry : estimatedRootEntries.entrySet()) {
      if (matchContext.matched.containsKey(entry.getKey())) {
        continue;
      }
      if (lowerValue == null) {
        lowerValue = entry;
      } else if (lowerValue.getValue() > entry.getValue()) {
        lowerValue = entry;
      }
    }

    return lowerValue.getKey();
  }

  private Map<String, Long> estimateRootEntries(Map<String, String> aliasClasses, Map<String, OWhereClause> aliasFilters) {
    Set<String> allAliases = new LinkedHashSet<String>();
    allAliases.addAll(aliasClasses.keySet());
    allAliases.addAll(aliasFilters.keySet());

    OSchema schema = getDatabase().getMetadata().getSchema();

    Map<String, Long> result = new LinkedHashMap<String, Long>();
    for (String alias : allAliases) {
      String className = aliasClasses.get(alias);
      if (className == null) {
        continue;
      }

      if (!schema.existsClass(className)) {
        throw new OCommandExecutionException("class not defined: " + className);
      }
      OClass oClass = schema.getClass(className);
      long upperBound;
      OWhereClause filter = aliasFilters.get(alias);
      if (filter != null) {
        upperBound = filter.estimate(oClass);
      } else {
        upperBound = oClass.count();
      }
      result.put(alias, upperBound);
    }
    return result;
  }

  private void addAliases(OMatchExpression expr, Map<String, OWhereClause> aliasFilters, Map<String, String> aliasClasses) {
    addAliases(expr.origin, aliasFilters, aliasClasses);
    for (OMatchPathItem item : expr.items) {
      if (item.filter != null) {
        addAliases(item.filter, aliasFilters, aliasClasses);
      }
    }
  }

  private void addAliases(OMatchFilter matchFilter, Map<String, OWhereClause> aliasFilters, Map<String, String> aliasClasses) {
    String alias = matchFilter.getAlias();
    OWhereClause filter = matchFilter.getFilter();
    if (alias != null) {
      if (filter != null && filter.baseExpression != null) {
        OWhereClause previousFilter = aliasFilters.get(alias);
        if (previousFilter == null) {
          previousFilter = new OWhereClause(-1);
          previousFilter.baseExpression = new OAndBlock(-1);
          aliasFilters.put(alias, previousFilter);
        }
        OAndBlock filterBlock = (OAndBlock) previousFilter.baseExpression;
        if (filter != null && filter.baseExpression != null) {
          filterBlock.subBlocks.add(filter.baseExpression);
        }
      }

      String clazz = matchFilter.getClassName();
      if (clazz != null) {
        String previousClass = aliasClasses.get(alias);
        if (previousClass == null) {
          aliasClasses.put(alias, clazz);
        } else {
          String lower = getLowerSubclass(clazz, previousClass);
          if (lower == null) {
            throw new OCommandExecutionException("classes defined for alias " + alias + " (" + clazz + ", " + previousClass
                + ") are not in the same hierarchy");
          }
          aliasClasses.put(alias, lower);
        }
      }
    }
  }

  private String getLowerSubclass(String className1, String className2) {
    OSchema schema = getDatabase().getMetadata().getSchema();
    OClass class1 = schema.getClass(className1);
    OClass class2 = schema.getClass(className2);
    if (class1.isSubClassOf(class2)) {
      return class1.getName();
    }
    if (class2.isSubClassOf(class1)) {
      return class2.getName();
    }
    return null;
  }

  @Override
  public <RET extends OCommandExecutor> RET setProgressListener(OProgressListener progressListener) {

    this.progressListener = progressListener;
    return (RET) this;
  }

  @Override
  public <RET extends OCommandExecutor> RET setLimit(int iLimit) {
    // TODO
    return (RET) this;
    // throw new UnsupportedOperationException();
  }

  @Override
  public String getFetchPlan() {
    return null;
  }

  @Override
  public Map<Object, Object> getParameters() {
    return null;
  }

  @Override
  public OCommandContext getContext() {
    return context;
  }

  @Override
  public void setContext(OCommandContext context) {
    this.context = context;
  }

  @Override
  public boolean isIdempotent() {
    return true;
  }

  @Override
  public Set<String> getInvolvedClusters() {
    return Collections.EMPTY_SET;
  }

  @Override
  public int getSecurityOperationType() {
    return ORole.PERMISSION_READ;
  }

  @Override
  public boolean involveSchema() {
    return false;
  }

  @Override
  public long getTimeout() {
    return -1;
  }

  @Override
  public String getSyntax() {
    return "MATCH <match-statement> [, <match-statement] RETURN <alias>[, <alias>]";
  }

  @Override
  public String toString() {
    StringBuilder result = new StringBuilder();
    result.append(KEYWORD_MATCH);
    result.append(" ");
    boolean first = true;
    for (OMatchExpression expr : this.matchExpressions) {
      if (!first) {
        result.append(", ");
      }
      result.append(expr.toString());
      first = false;
    }
    result.append(" RETURN ");
    first = true;
    for (OIdentifier expr : this.returnItems) {
      if (!first) {
        result.append(", ");
      }
      result.append(expr.toString());
      first = false;
    }
    return result.toString();
  }

  @Override
  public Iterator<OIdentifiable> iterator(Map<Object, Object> iArgs) {
    if (context == null) {
      context = new OBasicCommandContext();
    }
    Object result = execute(iArgs);
    return ((Iterable) result).iterator();
  }
}
/* JavaCC - OriginalChecksum=6ff0afbe9d31f08b72159fcf24070c9f (do not edit this line) */
