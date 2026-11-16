package com.cexpilot.platform.observability;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.stereotype.Component;
import java.sql.*;
import java.time.*;

/** 历史 DATETIME 按宿主配置的数据库存储时区读取；request_started_at 明确按 UTC 保存和还原。 */
@Component
public final class TraceDatabaseTime {
    private final ZoneId zone;
    public TraceDatabaseTime(@Value("${platform.trace.database-time-zone:UTC}") String zone) {
        this.zone = ZoneId.of(zone);
    }
    public OffsetDateTime offset(LocalDateTime value) {
        return value == null ? null : value.atZone(zone).toOffsetDateTime();
    }
    public ColumnMapRowMapper rowMapper() {
        return new ColumnMapRowMapper() {
            @Override protected Object getColumnValue(ResultSet rs, int index) throws SQLException {
                if (rs.getMetaData().getColumnType(index) == Types.TIMESTAMP) {
                    var raw = rs.getObject(index, LocalDateTime.class);
                    var value = "request_started_at".equalsIgnoreCase(rs.getMetaData().getColumnLabel(index))
                            ? raw == null ? null : raw.atOffset(ZoneOffset.UTC) : offset(raw);
                    return value == null ? null : value.toString();
                }
                return super.getColumnValue(rs, index);
            }
        };
    }
}
