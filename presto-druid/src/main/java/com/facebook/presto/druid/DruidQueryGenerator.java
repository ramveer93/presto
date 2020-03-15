/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.druid;

import com.facebook.airlift.log.Logger;
import com.facebook.presto.druid.DruidQueryGeneratorContext.Selection;
import com.facebook.presto.spi.ConnectorSession;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.function.FunctionMetadataManager;
import com.facebook.presto.spi.function.StandardFunctionResolution;
import com.facebook.presto.spi.plan.FilterNode;
import com.facebook.presto.spi.plan.LimitNode;
import com.facebook.presto.spi.plan.PlanNode;
import com.facebook.presto.spi.plan.PlanVisitor;
import com.facebook.presto.spi.plan.ProjectNode;
import com.facebook.presto.spi.plan.TableScanNode;
import com.facebook.presto.spi.relation.RowExpression;
import com.facebook.presto.spi.relation.VariableReferenceExpression;
import com.facebook.presto.spi.type.TypeManager;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import javax.inject.Inject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static com.facebook.presto.druid.DruidErrorCode.DRUID_PUSHDOWN_UNSUPPORTED_EXPRESSION;
import static com.facebook.presto.druid.DruidQueryGeneratorContext.Origin.TABLE_COLUMN;
import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

public class DruidQueryGenerator
{
    private static final Logger log = Logger.get(DruidQueryGenerator.class);

    private final TypeManager typeManager;
    private final FunctionMetadataManager functionMetadataManager;
    private final StandardFunctionResolution standardFunctionResolution;
    private final DruidProjectExpressionConverter druidProjectExpressionConverter;

    @Inject
    public DruidQueryGenerator(
            TypeManager typeManager,
            FunctionMetadataManager functionMetadataManager,
            StandardFunctionResolution standardFunctionResolution)
    {
        this.typeManager = requireNonNull(typeManager, "type manager is null");
        this.functionMetadataManager = requireNonNull(functionMetadataManager, "function metadata manager is null");
        this.standardFunctionResolution = requireNonNull(standardFunctionResolution, "standardFunctionResolution is null");
        this.druidProjectExpressionConverter = new DruidProjectExpressionConverter(typeManager, standardFunctionResolution);
    }

    public static class DruidQueryGeneratorResult
    {
        private final GeneratedDql generateddql;
        private final DruidQueryGeneratorContext context;

        public DruidQueryGeneratorResult(
                GeneratedDql generateddql,
                DruidQueryGeneratorContext context)
        {
            this.generateddql = requireNonNull(generateddql, "generateddql is null");
            this.context = requireNonNull(context, "context is null");
        }

        public GeneratedDql getGeneratedDql()
        {
            return generateddql;
        }

        public DruidQueryGeneratorContext getContext()
        {
            return context;
        }
    }

    public Optional<DruidQueryGeneratorResult> generate(PlanNode plan, ConnectorSession session)
    {
        try {
            DruidQueryGeneratorContext context = requireNonNull(plan.accept(
                    new DruidQueryPlanVisitor(session),
                    new DruidQueryGeneratorContext()),
                    "Resulting context is null");
            return Optional.of(new DruidQueryGeneratorResult(context.toQuery(), context));
        }
        catch (PrestoException e) {
            log.debug(e, "Possibly benign error when pushing plan into scan node %s", plan);
            return Optional.empty();
        }
    }

    public static class GeneratedDql
    {
        final String table;
        final String dql;
        final boolean pushdown;

        @JsonCreator
        public GeneratedDql(
                @JsonProperty("table") String table,
                @JsonProperty("dql") String dql,
                @JsonProperty("pushdown") boolean pushdown)
        {
            this.table = table;
            this.dql = dql;
            this.pushdown = pushdown;
        }

        @JsonProperty("dql")
        public String getDql()
        {
            return dql;
        }

        @JsonProperty("table")
        public String getTable()
        {
            return table;
        }

        @JsonProperty("pushdown")
        public boolean getPushdown()
        {
            return pushdown;
        }

        @Override
        public String toString()
        {
            return toStringHelper(this)
                    .add("dql", dql)
                    .add("table", table)
                    .add("pushdown", pushdown)
                    .toString();
        }
    }

    private class DruidQueryPlanVisitor
            extends PlanVisitor<DruidQueryGeneratorContext, DruidQueryGeneratorContext>
    {
        private final ConnectorSession session;

        protected DruidQueryPlanVisitor(ConnectorSession session)
        {
            this.session = session;
        }

        @Override
        public DruidQueryGeneratorContext visitPlan(PlanNode node, DruidQueryGeneratorContext context)
        {
            throw new PrestoException(DRUID_PUSHDOWN_UNSUPPORTED_EXPRESSION, "Unsupported pushdown for Druid connector with plan node of type " + node);
        }

        protected VariableReferenceExpression getVariableReference(RowExpression expression)
        {
            if (expression instanceof VariableReferenceExpression) {
                return ((VariableReferenceExpression) expression);
            }
            throw new PrestoException(DRUID_PUSHDOWN_UNSUPPORTED_EXPRESSION, "Unsupported pushdown for Druid connector. Expect variable reference, but get: " + expression);
        }

        @Override
        public DruidQueryGeneratorContext visitFilter(FilterNode node, DruidQueryGeneratorContext context)
        {
            context = node.getSource().accept(this, context);
            requireNonNull(context, "context is null");
            Map<VariableReferenceExpression, Selection> selections = context.getSelections();
            DruidFilterExpressionConverter druidFilterExpressionConverter = new DruidFilterExpressionConverter(typeManager, functionMetadataManager, standardFunctionResolution, session);
            String filter = node.getPredicate().accept(druidFilterExpressionConverter, selections::get).getDefinition();
            return context.withFilter(filter).withOutputColumns(node.getOutputVariables());
        }

        @Override
        public DruidQueryGeneratorContext visitProject(ProjectNode node, DruidQueryGeneratorContext contextIn)
        {
            DruidQueryGeneratorContext context = node.getSource().accept(this, contextIn);
            requireNonNull(context, "context is null");
            Map<VariableReferenceExpression, Selection> newSelections = new LinkedHashMap<>();

            node.getOutputVariables().forEach(variable -> {
                RowExpression expression = node.getAssignments().get(variable);
                DruidExpression druidExpression = expression.accept(
                        druidProjectExpressionConverter,
                        context.getSelections());
                newSelections.put(
                        variable,
                        new Selection(druidExpression.getDefinition(), druidExpression.getOrigin()));
            });
            return context.withProject(newSelections);
        }

        @Override
        public DruidQueryGeneratorContext visitLimit(LimitNode node, DruidQueryGeneratorContext context)
        {
            checkArgument(!node.isPartial(), "Druid query generator cannot handle partial limit");
            context = node.getSource().accept(this, context);
            requireNonNull(context, "context is null");
            return context.withLimit(node.getCount()).withOutputColumns(node.getOutputVariables());
        }

        @Override
        public DruidQueryGeneratorContext visitTableScan(TableScanNode node, DruidQueryGeneratorContext contextIn)
        {
            DruidTableHandle tableHandle = (DruidTableHandle) node.getTable().getConnectorHandle();
            checkArgument(!tableHandle.getDql().isPresent(), "Druid tableHandle should not have dql before pushdown");
            Map<VariableReferenceExpression, Selection> selections = new LinkedHashMap<>();
            node.getOutputVariables().forEach(outputColumn -> {
                DruidColumnHandle druidColumn = (DruidColumnHandle) (node.getAssignments().get(outputColumn));
                selections.put(outputColumn, new Selection(druidColumn.getColumnName(), TABLE_COLUMN));
            });
            return new DruidQueryGeneratorContext(selections, tableHandle.getTableName());
        }
    }
}
