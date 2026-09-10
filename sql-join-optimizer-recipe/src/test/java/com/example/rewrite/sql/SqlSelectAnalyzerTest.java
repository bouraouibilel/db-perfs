package com.example.rewrite.sql;

import com.example.rewrite.sql.model.JoinAnalysisResult;
import com.example.rewrite.sql.parser.SqlSelectAnalyzer;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SqlSelectAnalyzerTest {

    @Test
    void shouldExtractJoinsAndProjectedColumns() {
        String sql = "SELECT u.id AS id, u.name AS name, a.city AS city " +
                     "FROM users u " +
                     "LEFT JOIN address a ON u.address_id = a.id " +
                     "WHERE u.active = 1";

        Optional<JoinAnalysisResult> resultOpt = SqlSelectAnalyzer.analyze(sql);
        assertThat(resultOpt).isPresent();

        JoinAnalysisResult result = resultOpt.get();
        assertThat(result.mainTable()).isEqualToIgnoringCase("users");
        assertThat(result.mainAlias()).isEqualToIgnoringCase("u");

        assertThat(result.joins()).hasSize(1);
        JoinAnalysisResult.JoinedTableInfo join = result.joins().get(0);
        assertThat(join.tableName()).isEqualToIgnoringCase("address");
        assertThat(join.alias()).isEqualToIgnoringCase("a");
        assertThat(join.isLeftJoin()).isTrue();

        assertThat(result.projectedColumns()).hasSize(3);
        assertThat(result.projectedColumns()).extracting(JoinAnalysisResult.ProjectedColumn::targetPropertyName)
                .containsExactly("id", "name", "city");

        // u is in WHERE, a is not in WHERE
        assertThat(result.tablesUsedInFiltersOrSorting()).contains("u");
    }

    @Test
    void shouldIdentifyTableUsedInWhereClause() {
        String sql = "SELECT u.id, u.name " +
                     "FROM users u " +
                     "INNER JOIN roles r ON u.role_id = r.id " +
                     "WHERE r.code = 'ADMIN'";

        Optional<JoinAnalysisResult> resultOpt = SqlSelectAnalyzer.analyze(sql);
        assertThat(resultOpt).isPresent();

        JoinAnalysisResult result = resultOpt.get();
        // r is used in WHERE
        assertThat(result.tablesUsedInFiltersOrSorting()).contains("r");
    }
}
