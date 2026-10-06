package com.aava.datapowerai.model;

import java.time.OffsetDateTime;

/**
 * Story 104470: App Version Management / Version History – Backend
 *
 * Model representing a single stored application version.
 */
// ASSUMPTION: not found in repo content
public class AppVersionModel {

    private String appId;
    private int versionNumber;
    private String configJson;
    private String createdBy;
    private OffsetDateTime createdAt;

    /** Whether this version is deleted/invalid according to business rules. */
    private boolean deleted;

    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }

    public int getVersionNumber() {
        return versionNumber;
    }

    public void setVersionNumber(int versionNumber) {
        this.versionNumber = versionNumber;
    }

    public String getConfigJson() {
        return configJson;
    }

    public void setConfigJson(String configJson) {
        this.configJson = configJson;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }
}
