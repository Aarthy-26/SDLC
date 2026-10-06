package com.aava.datapowerai.repository;

import com.aava.datapowerai.model.AppVersionModel;
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
 */
// ASSUMPTION: not found in repo content
@Repository
public class AppVersionRepositoryJdbc {

    private static final Logger log = LoggerFactory.getLogger(AppVersionRepositoryJdbc.class);

    private final JdbcTemplate jdbcTemplate;

    private final RowMapper<AppVersionModel> rowMapper = (rs, rn) -> {
        AppVersionModel v = new AppVersionModel();
        v.setAppId(rs.getString("app_id"));
        v.setVersionNumber(rs.getInt("version_number"));
        v.setConfigJson(rs.getString("config_json"));
        v.setCreatedBy(rs.getString("created_by"));
        v.setCreatedAt(rs.getObject("created_at", java.time.OffsetDateTime.class));
        v.setDeleted(rs.getBoolean("deleted"));
        return v;
    };

    public AppVersionRepositoryJdbc(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Latest first, excludes deleted versions.
     */
    public List<AppVersionModel> findValidVersionsByAppId(String appId) {
        String sql = """
                SELECT app_id, version_number, config_json, created_by, created_at, deleted
                  FROM dpai.app_versions
                 WHERE app_id = ?
                   AND deleted = false
                 ORDER BY version_number DESC
                """;
        try {
            return jdbcTemplate.query(sql, rowMapper, appId);
        } catch (DataAccessException dae) {
            log.error("Error listing versions for appId={}: {}", appId, dae.getMessage(), dae);
            throw dae;
        }
    }

    /**
     * Returns version only if valid (not deleted).
     */
    public Optional<AppVersionModel> findValidByAppIdAndVersion(String appId, int versionNumber) {
        String sql = """
                SELECT app_id, version_number, config_json, created_by, created_at, deleted
                  FROM dpai.app_versions
                 WHERE app_id = ?
                   AND version_number = ?
                   AND deleted = false
                """;
        try {
            List<AppVersionModel> list = jdbcTemplate.query(sql, rowMapper, appId, versionNumber);
            return list.stream().findFirst();
        } catch (DataAccessException dae) {
            log.error("Error finding version for appId={} version={}: {}", appId, versionNumber, dae.getMessage(), dae);
            throw dae;
        }
    }

    /**
     * Ensures concurrent restore operations are handled safely by locking the app row.
     */
    public void lockAppRowForUpdate(String appId) {
        String sql = "SELECT id FROM dpai.apps WHERE id = ? FOR UPDATE";
        try {
            jdbcTemplate.queryForObject(sql, String.class, appId);
        } catch (DataAccessException dae) {
            log.error("Error locking app row appId={}: {}", appId, dae.getMessage(), dae);
            throw dae;
        }
    }
}
