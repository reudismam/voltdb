/* This file is part of VoltDB.
 * Copyright (C) 2008-2013 VoltDB Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.voltdb.planner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.json_voltpatches.JSONException;
import org.voltdb.VoltType;
import org.voltdb.catalog.CatalogMap;
import org.voltdb.catalog.Column;
import org.voltdb.catalog.ColumnRef;
import org.voltdb.catalog.MaterializedViewInfo;
import org.voltdb.catalog.Table;
import org.voltdb.expressions.AbstractExpression;
import org.voltdb.expressions.AggregateExpression;
import org.voltdb.expressions.ExpressionUtil;
import org.voltdb.expressions.TupleValueExpression;
import org.voltdb.planner.ParsedSelectStmt.ParsedColInfo;
import org.voltdb.plannodes.AbstractPlanNode;
import org.voltdb.plannodes.AbstractScanPlanNode;
import org.voltdb.plannodes.HashAggregatePlanNode;
import org.voltdb.plannodes.NodeSchema;
import org.voltdb.plannodes.ProjectionPlanNode;
import org.voltdb.plannodes.SchemaColumn;
import org.voltdb.types.ExpressionType;
import org.voltdb.utils.CatalogUtil;

public class MaterializedViewFixInfo {
    /**
     * This class contain all the information that Materialized view partitioned query need to be fixed.
     */

    // New inlined projection node for the scan node, contain extra group by columns.
    private ProjectionPlanNode m_scanInlinedProjectionNode = null;
    // New re-Aggregation plan node on the coordinator to eliminate the duplicated rows.
    private HashAggregatePlanNode m_reAggNode = null;

    // Does this mv partitioned based query needs to be fixed.
    private boolean m_needed = false;
    // materialized view table
    private Table m_mvTable = null;

    // number of group-by s.
    private int m_numOfGroupByColumns;

    // Scan Node for join query.
    AbstractScanPlanNode m_scanNode = null;

    public boolean needed () {
        return m_needed;
    }

    public void setNeeded (boolean need) {
        m_needed = need;
    }

    public String getMVTableName () {
        assert(m_mvTable != null);
        return m_mvTable.getTypeName();
    }

    public HashAggregatePlanNode getReAggregationPlanNode () {
        return m_reAggNode;
    }

    /**
     * Check whether the table need to be fixed or not.
     * Set the need flag to true, only if it needs to be fixed.
     * @return
     */
    public boolean processMVBasedQueryFix(Table table, Set<SchemaColumn> scanColumns, ParsedSelectStmt stmt) {
        // Check valid cases first
        String mvTableName = table.getTypeName();
        Table srcTable = table.getMaterializer();
        if (srcTable == null) {
            return false;
        }
        Column partitionCol = srcTable.getPartitioncolumn();
        if (partitionCol == null) {
            return false;
        }

        String partitionColName = partitionCol.getName();
        MaterializedViewInfo mvInfo = srcTable.getViews().get(mvTableName);

        // Justify whether partition column is in group by column list or not
        String complexGroupbyJson = mvInfo.getGroupbyexpressionsjson();
        if (complexGroupbyJson.length() > 0) {
            List<AbstractExpression> mvComplexGroupbyCols = null;
            try {
                mvComplexGroupbyCols = AbstractExpression.fromJSONArrayString(complexGroupbyJson);
            } catch (JSONException e) {
                e.printStackTrace();
            }
            m_numOfGroupByColumns = mvComplexGroupbyCols.size();

            for (AbstractExpression expr: mvComplexGroupbyCols) {
                if (expr instanceof TupleValueExpression) {
                    TupleValueExpression tve = (TupleValueExpression) expr;
                    if (tve.getColumnName().equals(partitionColName)) {
                        // If group by columns contain partition column from source table.
                        // Then, query on MV table will have duplicates from each partition.
                        // There is no need to fix this case, so just return.
                        return false;
                    }
                }
            }
        } else {
            CatalogMap<ColumnRef> mvSimpleGroupbyCols = mvInfo.getGroupbycols();
            m_numOfGroupByColumns = mvSimpleGroupbyCols.size();

            for (ColumnRef colRef: mvSimpleGroupbyCols) {
                if (colRef.getColumn().getName().equals(partitionColName)) {
                    // If group by columns contain partition column from source table.
                    // Then, query on MV table will have duplicates from each partition.
                    // There is no need to fix this case, so just return.
                    return false;
                }
            }
        }
        assert(m_numOfGroupByColumns > 0);
        m_mvTable = table;

        Set<TupleValueExpression> mvDDLGroupbyTVEs = new HashSet<TupleValueExpression>();
        List<Column> mvColumnArray =
                CatalogUtil.getSortedCatalogItems(table.getColumns(), "index");


        // Start to do real materialized view processing to fix the duplicates problem.
        // (1) construct new projection columns for scan plan node.
        Set<SchemaColumn> mvDDLGroupbyColumns = new HashSet<SchemaColumn>();
        NodeSchema inlineProjSchema = new NodeSchema();
        for (SchemaColumn scol: scanColumns) {
            inlineProjSchema.addColumn(scol);
        }

        for (int i = 0; i < m_numOfGroupByColumns; i++) {
            Column mvCol = mvColumnArray.get(i);
            String colName = mvCol.getName();

            TupleValueExpression tve = new TupleValueExpression();
            tve.setColumnIndex(i);
            tve.setColumnName(colName);
            tve.setTableName(mvTableName);
            tve.setColumnAlias(colName);
            tve.setValueType(VoltType.get((byte)mvCol.getType()));
            tve.setValueSize(mvCol.getSize());

            mvDDLGroupbyTVEs.add(tve);

            SchemaColumn scol = new SchemaColumn(mvTableName, colName, colName, tve);

            mvDDLGroupbyColumns.add(scol);
            if (!scanColumns.contains(scol)) {
                scanColumns.add(scol);
                // construct new projection columns for scan plan node.
                inlineProjSchema.addColumn(scol);
            }
        }


        // Record the re-aggregation type for each scan columns.
        Map<String, ExpressionType> mvColumnReAggType = new HashMap<String, ExpressionType>();
        for (int i = m_numOfGroupByColumns; i < mvColumnArray.size(); i++) {
            Column mvCol = mvColumnArray.get(i);
            ExpressionType reAggType = ExpressionType.get(mvCol.getAggregatetype());

            if (reAggType == ExpressionType.AGGREGATE_COUNT_STAR ||
                    reAggType == ExpressionType.AGGREGATE_COUNT) {
                reAggType = ExpressionType.AGGREGATE_SUM;
            }
            mvColumnReAggType.put(mvCol.getName(), reAggType);
        }

        m_scanInlinedProjectionNode = new ProjectionPlanNode();
        m_scanInlinedProjectionNode.setOutputSchema(inlineProjSchema);

        // (2) Construct the reAggregation Node.

        // Construct the reAggregation plan node's aggSchema
        m_reAggNode = new HashAggregatePlanNode();
        int outputColumnIndex = 0;
        // inlineProjSchema contains the group by columns, while aggSchema may do not.
        NodeSchema aggSchema = new NodeSchema();

        // Construct reAggregation node's aggregation and group by list.
        for (SchemaColumn scol: scanColumns) {
            if (mvDDLGroupbyColumns.contains(scol)) {
                // Add group by expression.
                m_reAggNode.addGroupByExpression(scol.getExpression());
            } else {
                ExpressionType reAggType = mvColumnReAggType.get(scol.getColumnName());
                assert(reAggType != null);
                AbstractExpression agg_input_expr = scol.getExpression();
                assert(agg_input_expr instanceof TupleValueExpression);
                // Add aggregation information.
                m_reAggNode.addAggregate(reAggType, false, outputColumnIndex, agg_input_expr);
            }
            aggSchema.addColumn(scol);
            outputColumnIndex++;
        }
        m_reAggNode.setOutputSchema(aggSchema);


        // Collect all TVEs that need to be do re-aggregation in coordinator.
        List<TupleValueExpression> needReAggTVEs = new ArrayList<TupleValueExpression>();
        List<AbstractExpression> aggPostExprs = new ArrayList<AbstractExpression>();

        for (int i=m_numOfGroupByColumns; i < mvColumnArray.size(); i++) {
            Column mvCol = mvColumnArray.get(i);
            TupleValueExpression tve = new TupleValueExpression();
            tve.setColumnIndex(i);
            tve.setColumnName(mvCol.getName());
            tve.setTableName(getMVTableName());
            tve.setColumnAlias(mvCol.getName());
            tve.setValueType(VoltType.get((byte)mvCol.getType()));
            tve.setValueSize(mvCol.getSize());

            needReAggTVEs.add(tve);
        }

        collectReAggNodePostExpressions(stmt.joinTree, needReAggTVEs, aggPostExprs);

        AbstractExpression aggPostExpr = ExpressionUtil.combine(aggPostExprs);
        // Add post filters for the reAggregation node.
        m_reAggNode.setPostPredicate(aggPostExpr);


        // ENG-5386
        if (edgeCaseQueryNoFixNeeded(stmt, mvDDLGroupbyTVEs, mvColumnReAggType)) {
            return false;
        }

        m_needed = true;
        return true;
    }

    private boolean m_edgeCaseQueryNoFixNeeded = true;

    // ENG-5386: do not fix some cases in order to get better performance.
    private boolean edgeCaseQueryNoFixNeeded(ParsedSelectStmt stmt, Set<TupleValueExpression> mvDDLGroupbyTVEs,
            Map<String, ExpressionType> mvColumnAggType) {

        if (stmt.hasComplexGroupby()) {
            return false;
        }

        if (stmt.tableList.size() > 2) {
            return false;
        }

        if (m_edgeCaseQueryNoFixNeeded == false) {
            return false;
        }

        // Condition (1): Group by columns must be part of or all from MV DDL group by TVEs.
        for (ParsedColInfo gcol: stmt.groupByColumns()) {
            assert(gcol.expression instanceof TupleValueExpression);
            TupleValueExpression tve = (TupleValueExpression) gcol.expression;
            if (tve.getTableName().equals(getMVTableName()) && !mvDDLGroupbyTVEs.contains(tve)) {
                return false;
            }
        }

        // Condition (2): Aggregation must be:
        for (ParsedColInfo dcol: stmt.displayColumns()) {
            if (stmt.groupByColumns().contains(dcol)) {
                continue;
            }
            if (dcol.expression instanceof AggregateExpression == false) {
                return false;
            }
            AggregateExpression aggExpr = (AggregateExpression) dcol.expression;
            if (aggExpr.getLeft() instanceof TupleValueExpression == false) {
                return false;
            }
            ExpressionType type = aggExpr.getExpressionType();
            TupleValueExpression tve = (TupleValueExpression) aggExpr.getLeft();
            String columnName = tve.getColumnName();

            if (tve.getTableName().equals(getMVTableName())) {
                if (type != ExpressionType.AGGREGATE_SUM && type != ExpressionType.AGGREGATE_MIN
                        && type != ExpressionType.AGGREGATE_MAX) {
                    return false;
                }
                if (mvColumnAggType.get(columnName) != type ) {
                    return false;
                }
            }
        }

        // Edge case query can be optimized with correct answer without MV reAggregation fix.
        return true;
    }


    /**
     * Find the scan node on MV table, replace it with reAggNode for join query.
     * This scan node can not be in-lined, so it should be as a child of a join node.
     * @param node
     */
    public boolean processScanNodeWithReAggNode(AbstractPlanNode node, AbstractPlanNode reAggNode) {
        // MV table scan node can not be in in-lined nodes.
        for (int i = 0; i < node.getChildCount(); i++) {
            AbstractPlanNode child = node.getChild(i);

            if (child instanceof AbstractScanPlanNode) {
                AbstractScanPlanNode scanNode = (AbstractScanPlanNode) child;
                if (!scanNode.getTargetTableName().equals(getMVTableName())) {
                    continue;
                }
                if (reAggNode != null) {
                    // Join query case.
                    node.setAndLinkChild(i, reAggNode);
                }
                // Process scan node.
                // Set up the scan plan node's scan columns. Add in-line projection node for scan node.
                scanNode.addInlinePlanNode(m_scanInlinedProjectionNode);
                m_scanNode = scanNode;
                return true;
            } else {
                boolean replaced = processScanNodeWithReAggNode(child, reAggNode);
                if (replaced) {
                    return true;
                }
            }
        }
        return false;
    }

    private void collectReAggNodePostExpressions(JoinNode joinTree,
            List<TupleValueExpression> needReAggTVEs, List<AbstractExpression> aggPostExprs) {
        if (joinTree.m_leftNode != null) {
            collectReAggNodePostExpressions(joinTree.m_leftNode, needReAggTVEs, aggPostExprs);
        }
        if (joinTree.m_rightNode != null) {
            collectReAggNodePostExpressions(joinTree.m_rightNode, needReAggTVEs, aggPostExprs);
        }
        if (joinTree.m_table != null) {
            joinTree.m_joinExpr = processFilters(joinTree.m_joinExpr, needReAggTVEs, aggPostExprs);

            // For outer join filters. Inner join or single table query will have whereExpr be null.
            joinTree.m_whereExpr = processFilters(joinTree.m_whereExpr, needReAggTVEs, aggPostExprs);
        }
    }

    private boolean fromSingleTableOnly(List<AbstractExpression> tves) {
        String tableName = null;
        for (AbstractExpression tve: tves) {
            assert(tve instanceof TupleValueExpression);
            String tveTableName = ((TupleValueExpression)tve).getTableName();
            if (tableName == null) {
                tableName = tveTableName;
            }
            if (!tableName.equals(tveTableName)) {
                return false;
            }
        }
        return true;
    }

    private AbstractExpression processFilters (AbstractExpression filters,
            List<TupleValueExpression> needReAggTVEs, List<AbstractExpression> aggPostExprs) {
        if (filters != null) {
            // Collect all TVEs that need to be do re-aggregation in coordinator.
            List<AbstractExpression> remaningExprs = new ArrayList<AbstractExpression>();
            // Check where clause.
            List<AbstractExpression> exprs = ExpressionUtil.uncombine(filters);

            for (AbstractExpression expr: exprs) {
                ArrayList<AbstractExpression> tves = expr.findBaseTVEs();

                boolean canPushdown = true;
                // If the expression is built on a join expression referencing two tables,
                // There is no need to process it to extract post filters for ReAggregation node.
                if (fromSingleTableOnly(tves)) {
                    if (tves.size() > 0 && ((TupleValueExpression)tves.get(0)).getTableName().equals(getMVTableName()) ) {
                        for (TupleValueExpression needReAggTVE: needReAggTVEs) {
                            if (tves.contains(needReAggTVE)) {
                                canPushdown = false;
                                m_edgeCaseQueryNoFixNeeded = false;
                                break;
                            }
                        }
                    }
                } else {
                    // Filter references from two tables or more.
                    // m_edgeCaseQueryNoFixNeeded
                    for (TupleValueExpression needReAggTVE: needReAggTVEs) {
                        if (tves.contains(needReAggTVE)) {
                            m_edgeCaseQueryNoFixNeeded = false;
                            break;
                        }
                    }
                }
                if (canPushdown) {
                    remaningExprs.add(expr);
                } else {
                    aggPostExprs.add(expr);
                }
            }
            AbstractExpression remaningFilters = ExpressionUtil.combine(remaningExprs);
            // Update new filters for the scanNode.
            return remaningFilters;
        }
        return null;
    }
}
