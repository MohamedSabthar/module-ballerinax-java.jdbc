/*
 * Copyright (c) 2021, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.ballerina.stdlib.java.jdbc.compiler.analyzer;

import io.ballerina.compiler.syntax.tree.BasicLiteralNode;
import io.ballerina.compiler.syntax.tree.ExplicitNewExpressionNode;
import io.ballerina.compiler.syntax.tree.ExpressionNode;
import io.ballerina.compiler.syntax.tree.FunctionArgumentNode;
import io.ballerina.compiler.syntax.tree.ImplicitNewExpressionNode;
import io.ballerina.compiler.syntax.tree.MappingConstructorExpressionNode;
import io.ballerina.compiler.syntax.tree.MappingFieldNode;
import io.ballerina.compiler.syntax.tree.NamedArgumentNode;
import io.ballerina.compiler.syntax.tree.Node;
import io.ballerina.compiler.syntax.tree.PositionalArgumentNode;
import io.ballerina.compiler.syntax.tree.SeparatedNodeList;
import io.ballerina.compiler.syntax.tree.SpecificFieldNode;
import io.ballerina.compiler.syntax.tree.UnaryExpressionNode;
import io.ballerina.projects.plugins.AnalysisTask;
import io.ballerina.projects.plugins.SyntaxNodeAnalysisContext;
import io.ballerina.stdlib.java.jdbc.compiler.Constants;
import io.ballerina.stdlib.java.jdbc.compiler.Utils;
import io.ballerina.tools.diagnostics.Diagnostic;
import io.ballerina.tools.diagnostics.DiagnosticFactory;
import io.ballerina.tools.diagnostics.DiagnosticInfo;
import io.ballerina.tools.diagnostics.DiagnosticSeverity;

import java.util.List;
import java.util.Optional;

import static io.ballerina.stdlib.java.jdbc.compiler.Constants.CONNECTION_POOL_PARM_NAME;
import static io.ballerina.stdlib.java.jdbc.compiler.Constants.UNNECESSARY_CHARS_REGEX;
import static io.ballerina.stdlib.java.jdbc.compiler.JDBCDiagnosticsCode.SQL_101;
import static io.ballerina.stdlib.java.jdbc.compiler.JDBCDiagnosticsCode.SQL_102;
import static io.ballerina.stdlib.java.jdbc.compiler.JDBCDiagnosticsCode.SQL_103;

/**
 * Validate fields of sql:Connection pool fields.
 */
public class InitializerParamAnalyzer implements AnalysisTask<SyntaxNodeAnalysisContext> {
    @Override
    public void perform(SyntaxNodeAnalysisContext ctx) {
        List<Diagnostic> diagnostics = ctx.semanticModel().diagnostics();
        for (Diagnostic diagnostic : diagnostics) {
            if (diagnostic.diagnosticInfo().severity() == DiagnosticSeverity.ERROR) {
                return;
            }
        }

        if (!(Utils.isJDBCClientObject(ctx, ((ExpressionNode) ctx.node())))) {
            return;
        }
        
        SeparatedNodeList<FunctionArgumentNode> arguments;
        if (ctx.node() instanceof ImplicitNewExpressionNode) {
            arguments = ((ImplicitNewExpressionNode) ctx.node()).parenthesizedArgList().get().arguments();
        } else {
            arguments = ((ExplicitNewExpressionNode) ctx.node()).parenthesizedArgList().arguments();
        }

        Optional<NamedArgumentNode> connectionPoolOptional = arguments.stream()
                .filter(argNode -> argNode instanceof NamedArgumentNode)
                .map(argNode -> (NamedArgumentNode) argNode)
                .filter(arg -> arg.argumentName().name().text().equals(CONNECTION_POOL_PARM_NAME))
                .findFirst();
        ExpressionNode connectionPool;
        if (connectionPoolOptional.isPresent()) {
            connectionPool = connectionPoolOptional.get().expression();
        } else if (arguments.size() == 5) {
            // All params are present
            connectionPool = ((PositionalArgumentNode) arguments.get(4)).expression();
        } else {
            return;
        }

        if (!(connectionPool instanceof MappingConstructorExpressionNode)) {
            // connection pool is null scenario
            return;
        }
        SeparatedNodeList<MappingFieldNode> fields =
                ((MappingConstructorExpressionNode) connectionPool).fields();
        for (MappingFieldNode field : fields) {
            String name = ((SpecificFieldNode) field).fieldName().toString()
                    .trim().replaceAll(UNNECESSARY_CHARS_REGEX, "");
            ExpressionNode valueNode = ((SpecificFieldNode) field).valueExpr().get();
            switch (name) {
                case Constants.ConnectionPool.MAX_OPEN_CONNECTIONS:
                    int maxOpenConnections = Integer.parseInt(getTerminalNodeValue(valueNode, "1"));
                    if (maxOpenConnections < 1) {
                        DiagnosticInfo diagnosticInfo = new DiagnosticInfo(SQL_101.getCode(), SQL_101.getMessage(),
                                SQL_101.getSeverity());

                        ctx.reportDiagnostic(
                                DiagnosticFactory.createDiagnostic(diagnosticInfo, valueNode.location()));

                    }
                    break;
                case Constants.ConnectionPool.MIN_IDLE_CONNECTIONS:
                    int minIdleConnection = Integer.parseInt(getTerminalNodeValue(valueNode, "0"));
                    if (minIdleConnection < 0) {
                        DiagnosticInfo diagnosticInfo = new DiagnosticInfo(SQL_102.getCode(), SQL_102.getMessage(),
                                SQL_102.getSeverity());
                        ctx.reportDiagnostic(
                                DiagnosticFactory.createDiagnostic(diagnosticInfo, valueNode.location()));

                    }
                    break;
                case Constants.ConnectionPool.MAX_CONNECTION_LIFE_TIME:
                    float maxConnectionTime = Float.parseFloat(getTerminalNodeValue(valueNode, "30"));
                    if (maxConnectionTime < 30) {
                        DiagnosticInfo diagnosticInfo = new DiagnosticInfo(SQL_103.getCode(), SQL_103.getMessage(),
                                SQL_103.getSeverity());
                        ctx.reportDiagnostic(
                                DiagnosticFactory.createDiagnostic(diagnosticInfo, valueNode.location()));

                    }
                    break;
                default:
                    // Can ignore all other fields
                    continue;
            }
        }
    }

    private String getTerminalNodeValue(Node valueNode, String defaultValue) {
        String value = defaultValue;
        if (valueNode instanceof BasicLiteralNode) {
            value = ((BasicLiteralNode) valueNode).literalToken().text();
        } else if (valueNode instanceof UnaryExpressionNode) {
            UnaryExpressionNode unaryExpressionNode = (UnaryExpressionNode) valueNode;
            value = unaryExpressionNode.unaryOperator() +
                    ((BasicLiteralNode) unaryExpressionNode.expression()).literalToken().text();
        }
        // Currently we cannot process values from variables, this needs code flow analysis
        return value.replaceAll(UNNECESSARY_CHARS_REGEX, "");
    }

}
