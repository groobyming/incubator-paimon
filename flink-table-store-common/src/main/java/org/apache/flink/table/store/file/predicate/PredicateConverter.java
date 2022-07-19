/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.store.file.predicate;

import org.apache.flink.table.data.binary.BinaryStringData;
import org.apache.flink.table.expressions.CallExpression;
import org.apache.flink.table.expressions.Expression;
import org.apache.flink.table.expressions.ExpressionVisitor;
import org.apache.flink.table.expressions.FieldReferenceExpression;
import org.apache.flink.table.expressions.ResolvedExpression;
import org.apache.flink.table.expressions.TypeLiteralExpression;
import org.apache.flink.table.expressions.ValueLiteralExpression;
import org.apache.flink.table.functions.BuiltInFunctionDefinitions;
import org.apache.flink.table.functions.FunctionDefinition;
import org.apache.flink.table.store.utils.TypeUtils;
import org.apache.flink.table.types.DataType;
import org.apache.flink.table.types.logical.LogicalType;
import org.apache.flink.table.types.logical.LogicalTypeFamily;
import org.apache.flink.table.types.logical.RowType;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.apache.flink.table.data.conversion.DataStructureConverters.getConverter;
import static org.apache.flink.table.types.logical.utils.LogicalTypeCasts.supportsImplicitCast;

/** Convert {@link Expression} to {@link Predicate}. */
public class PredicateConverter implements ExpressionVisitor<Predicate> {

    private final PredicateBuilder builder;

    public PredicateConverter(RowType type) {
        this(new PredicateBuilder(type));
    }

    public PredicateConverter(PredicateBuilder builder) {
        this.builder = builder;
    }

    /** Accepts simple LIKE patterns like "abc%". */
    private static final Pattern BEGIN_PATTERN = Pattern.compile("([^%]+)%");

    @Override
    public Predicate visit(CallExpression call) {
        FunctionDefinition func = call.getFunctionDefinition();
        List<Expression> children = call.getChildren();

        if (func == BuiltInFunctionDefinitions.AND) {
            return PredicateBuilder.and(children.get(0).accept(this), children.get(1).accept(this));
        } else if (func == BuiltInFunctionDefinitions.OR) {
            return PredicateBuilder.or(children.get(0).accept(this), children.get(1).accept(this));
        } else if (func == BuiltInFunctionDefinitions.EQUALS) {
            return visitBiFunction(children, builder::equal, builder::equal);
        } else if (func == BuiltInFunctionDefinitions.NOT_EQUALS) {
            return visitBiFunction(children, builder::notEqual, builder::notEqual);
        } else if (func == BuiltInFunctionDefinitions.GREATER_THAN) {
            return visitBiFunction(children, builder::greaterThan, builder::lessThan);
        } else if (func == BuiltInFunctionDefinitions.GREATER_THAN_OR_EQUAL) {
            return visitBiFunction(children, builder::greaterOrEqual, builder::lessOrEqual);
        } else if (func == BuiltInFunctionDefinitions.LESS_THAN) {
            return visitBiFunction(children, builder::lessThan, builder::greaterThan);
        } else if (func == BuiltInFunctionDefinitions.LESS_THAN_OR_EQUAL) {
            return visitBiFunction(children, builder::lessOrEqual, builder::greaterOrEqual);
        } else if (func == BuiltInFunctionDefinitions.IN) {
            FieldReferenceExpression fieldRefExpr =
                    extractFieldReference(children.get(0)).orElseThrow(UnsupportedExpression::new);
            List<Object> literals = new ArrayList<>();
            for (int i = 1; i < children.size(); i++) {
                literals.add(extractLiteral(fieldRefExpr.getOutputDataType(), children.get(i)));
            }
            return builder.in(fieldRefExpr.getInputIndex(), literals);
        } else if (func == BuiltInFunctionDefinitions.IS_NULL) {
            return extractFieldReference(children.get(0))
                    .map(FieldReferenceExpression::getFieldIndex)
                    .map(builder::isNull)
                    .orElseThrow(UnsupportedExpression::new);
        } else if (func == BuiltInFunctionDefinitions.IS_NOT_NULL) {
            return extractFieldReference(children.get(0))
                    .map(FieldReferenceExpression::getFieldIndex)
                    .map(builder::isNotNull)
                    .orElseThrow(UnsupportedExpression::new);
        } else if (func == BuiltInFunctionDefinitions.LIKE) {
            FieldReferenceExpression fieldRefExpr =
                    extractFieldReference(children.get(0)).orElseThrow(UnsupportedExpression::new);
            if (fieldRefExpr
                    .getOutputDataType()
                    .getLogicalType()
                    .getTypeRoot()
                    .getFamilies()
                    .contains(LogicalTypeFamily.CHARACTER_STRING)) {
                String sqlPattern =
                        extractLiteral(fieldRefExpr.getOutputDataType(), children.get(1))
                                .toString();
                String escape =
                        children.size() <= 2
                                ? null
                                : extractLiteral(fieldRefExpr.getOutputDataType(), children.get(2))
                                        .toString();
                String escapedSqlPattern = sqlPattern;
                boolean allowQuick = false;
                if (escape == null && !sqlPattern.contains("_")) {
                    allowQuick = true;
                } else if (escape != null) {
                    if (escape.length() != 1) {
                        throw new RuntimeException("Invalid escape character '" + escape + "'");
                    }
                    char escapeChar = escape.charAt(0);
                    boolean matched = true;
                    int i = 0;
                    StringBuilder sb = new StringBuilder();
                    while (i < sqlPattern.length() && matched) {
                        char c = sqlPattern.charAt(i);
                        if (c == escapeChar) {
                            if (i == (sqlPattern.length() - 1)) {
                                throw new RuntimeException(
                                        "Invalid escape sequence '" + sqlPattern + "', " + i);
                            }
                            char nextChar = sqlPattern.charAt(i + 1);
                            if (nextChar == '%') {
                                matched = false;
                            } else if ((nextChar == '_') || (nextChar == escapeChar)) {
                                sb.append(nextChar);
                                i += 1;
                            } else {
                                throw new RuntimeException(
                                        "Invalid escape sequence '" + sqlPattern + "', " + i);
                            }
                        } else if (c == '_') {
                            matched = false;
                        } else {
                            sb.append(c);
                        }
                        i = i + 1;
                    }
                    if (matched) {
                        allowQuick = true;
                        escapedSqlPattern = sb.toString();
                    }
                }
                if (allowQuick) {
                    Matcher beginMatcher = BEGIN_PATTERN.matcher(escapedSqlPattern);
                    if (beginMatcher.matches()) {
                        return builder.startsWith(
                                fieldRefExpr.getFieldIndex(),
                                BinaryStringData.fromString(beginMatcher.group(1)));
                    }
                }
            }
        }

        // TODO is_xxx, between_xxx, similar, in, not_in, not?

        throw new UnsupportedExpression();
    }

    private Predicate visitBiFunction(
            List<Expression> children,
            BiFunction<Integer, Object, Predicate> visit1,
            BiFunction<Integer, Object, Predicate> visit2) {
        Optional<FieldReferenceExpression> fieldRefExpr = extractFieldReference(children.get(0));
        if (fieldRefExpr.isPresent()) {
            Object literal =
                    extractLiteral(fieldRefExpr.get().getOutputDataType(), children.get(1));
            return visit1.apply(fieldRefExpr.get().getFieldIndex(), literal);
        } else {
            fieldRefExpr = extractFieldReference(children.get(1));
            if (fieldRefExpr.isPresent()) {
                Object literal =
                        extractLiteral(fieldRefExpr.get().getOutputDataType(), children.get(0));
                return visit2.apply(fieldRefExpr.get().getFieldIndex(), literal);
            }
        }

        throw new UnsupportedExpression();
    }

    private Optional<FieldReferenceExpression> extractFieldReference(Expression expression) {
        if (expression instanceof FieldReferenceExpression) {
            return Optional.of((FieldReferenceExpression) expression);
        }
        return Optional.empty();
    }

    private Object extractLiteral(DataType expectedType, Expression expression) {
        LogicalType expectedLogicalType = expectedType.getLogicalType();
        if (!supportsPredicate(expectedLogicalType)) {
            throw new UnsupportedExpression();
        }

        if (expression instanceof ValueLiteralExpression) {
            ValueLiteralExpression valueExpression = (ValueLiteralExpression) expression;
            if (valueExpression.isNull()) {
                return null;
            }

            DataType actualType = valueExpression.getOutputDataType();
            LogicalType actualLogicalType = actualType.getLogicalType();
            Optional<?> valueOpt = valueExpression.getValueAs(actualType.getConversionClass());
            if (valueOpt.isPresent()) {
                Object value = valueOpt.get();
                if (actualLogicalType.getTypeRoot().equals(expectedLogicalType.getTypeRoot())) {
                    return getConverter(expectedType).toInternalOrNull(value);
                } else if (supportsImplicitCast(actualLogicalType, expectedLogicalType)) {
                    try {
                        return TypeUtils.castFromString(value.toString(), expectedLogicalType);
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        throw new UnsupportedExpression();
    }

    private boolean supportsPredicate(LogicalType type) {
        switch (type.getTypeRoot()) {
            case CHAR:
            case VARCHAR:
            case BOOLEAN:
            case BINARY:
            case VARBINARY:
            case DECIMAL:
            case TINYINT:
            case SMALLINT:
            case INTEGER:
            case BIGINT:
            case FLOAT:
            case DOUBLE:
            case DATE:
            case TIME_WITHOUT_TIME_ZONE:
            case TIMESTAMP_WITHOUT_TIME_ZONE:
            case TIMESTAMP_WITH_TIME_ZONE:
            case TIMESTAMP_WITH_LOCAL_TIME_ZONE:
            case INTERVAL_YEAR_MONTH:
            case INTERVAL_DAY_TIME:
                return true;
            default:
                return false;
        }
    }

    @Override
    public Predicate visit(ValueLiteralExpression valueLiteralExpression) {
        throw new RuntimeException("Literal should be resolved in call expression.");
    }

    @Override
    public Predicate visit(FieldReferenceExpression fieldReferenceExpression) {
        throw new RuntimeException("Field reference should be resolved in call expression.");
    }

    @Override
    public Predicate visit(TypeLiteralExpression typeLiteralExpression) {
        throw new RuntimeException(
                "Type literal is unsupported: " + typeLiteralExpression.asSummaryString());
    }

    @Override
    public Predicate visit(Expression expression) {
        throw new RuntimeException("Unsupported expression: " + expression.asSummaryString());
    }

    /**
     * Try best to convert a {@link ResolvedExpression} to {@link Predicate}.
     *
     * @param filter a resolved expression
     * @return {@link Predicate} if no {@link UnsupportedExpression} thrown.
     */
    public static Optional<Predicate> convert(RowType rowType, ResolvedExpression filter) {
        try {
            return Optional.ofNullable(filter.accept(new PredicateConverter(rowType)));
        } catch (UnsupportedExpression e) {
            return Optional.empty();
        }
    }

    @Nullable
    public static Predicate fromMap(Map<String, String> map, RowType rowType) {
        // TODO: It is somewhat misleading that an empty map creates a null predicate filter
        List<String> fieldNames = rowType.getFieldNames();
        Predicate predicate = null;
        PredicateBuilder builder = new PredicateBuilder(rowType);
        for (Map.Entry<String, String> entry : map.entrySet()) {
            int idx = fieldNames.indexOf(entry.getKey());
            LogicalType type = rowType.getTypeAt(idx);
            Object literal = TypeUtils.castFromString(entry.getValue(), type);
            if (predicate == null) {
                predicate = builder.equal(idx, literal);
            } else {
                predicate = PredicateBuilder.and(predicate, builder.equal(idx, literal));
            }
        }
        return predicate;
    }

    /** Encounter an unsupported expression, the caller can choose to ignore this filter branch. */
    public static class UnsupportedExpression extends RuntimeException {}
}