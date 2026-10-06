package com.aava.datapowerai.repository;

import com.aava.datapowerai.model.AppModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Story 104470: App Version Management / Version History – Backend
 *
 * Repository for app current state.
 */
// ASSUMPTION: not found in repo content
@Repository
public class AppRepositoryJdbc {

    private static final Logger log = LoggerFactory.getLogger(AppRepositoryJdbc.class);

    private final JdbcTemplate jdbcTemplate;

    private final RowMapper<AppModel> rowMapper = (rs, rn) -> {
        AppModel app = new AppModel();
        app.setId(rs.getString("id"));
        app.setName(rs.getString("name"));
        app.setCurrentVersion(rs.getObject("current_version", Integer.class));
        app.setConfigJson(rs.getString("config_json"));
        app.setCreatedAt(rs.getObject("created_at", java.time.OffsetDateTime.class));
        app.setUpdatedAt(rs.getObject("updated_at", java.time.OffsetDateTime.class));
        return app;
    };

    public AppRepositoryJdbc(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<AppModel> findById(String appId) {
        String sql = """
                SELECT id, name, current_version, config_json, created_at, updated_at
                  FROM dpai.apps
                 WHERE id = ?
                """;
        try {
            List<AppModel> list = jdbcTemplate.query(sql, rowMapper, appId);
            return list.stream().findFirst();
        } catch (DataAccessException dae) {
            log.error("Error finding app by id={}: {}", appId, dae.getMessage(), dae);
            throw dae;
        }
    }

    public Optional<AppModel> updateCurrentState(String appId, int currentVersion, String configJson) {
        String sql = """
                UPDATE dpai.apps
                   SET current_version = ?,
                       config_json = ?,
                       updated_at = now()
                 WHERE id = ?
                 RETURNING id, name, current_version, config_json, created_at, updated_at
                """;
        try {
            List<AppModel> list = jdbcTemplate.query(sql, rowMapper, currentVersion, configJson, appId);
            return list.stream().findFirst();
        } catch (DataAccessException dae) {
            log.error("Error updating app id={}: {}", appId, dae.getMessage(), dae);
            throw dae;
        }
    }
}
