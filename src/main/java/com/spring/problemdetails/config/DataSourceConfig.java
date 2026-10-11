package com.spring.problemdetails.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.jspecify.annotations.NonNull;
import org.sqlite.SQLiteConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.SQLErrorCodeSQLExceptionTranslator;
import org.springframework.lang.Nullable;

import javax.sql.DataSource;
import java.sql.SQLException;

@Configuration
public class DataSourceConfig {

    @Bean
    public DataSource dataSource(
            @Value("${spring.datasource.url}") String url,
            @Value("${hikari.maximum-pool-size:20}") int maxPoolSize,
            @Value("${hikari.minimum-idle:5}") int minIdle) {

        // SQLiteConfig sets per-connection PRAGMAs via JDBC properties
        SQLiteConfig sqLiteConfig = getSqLiteConfig();

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(url);
        hikariConfig.setMaximumPoolSize(maxPoolSize);
        hikariConfig.setMinimumIdle(minIdle);
        hikariConfig.setConnectionTimeout(3_000);
        hikariConfig.setIdleTimeout(600_000);
        hikariConfig.setMaxLifetime(1_800_000);
        hikariConfig.setLeakDetectionThreshold(30_000);
        // HikariCP passes these as JDBC connection properties → SQLite JDBC converts them to PRAGMAs
        hikariConfig.setDataSourceProperties(sqLiteConfig.toProperties());

        return new HikariDataSource(hikariConfig);
    }

    private static @NonNull SQLiteConfig getSqLiteConfig() {
        SQLiteConfig sqLiteConfig = new SQLiteConfig();
        sqLiteConfig.setJournalMode(SQLiteConfig.JournalMode.WAL);        // concurrent reads
        sqLiteConfig.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL); // safe with WAL, faster than FULL
        sqLiteConfig.setCacheSize(64000);                                  // 64 MB page cache per connection
        sqLiteConfig.setTempStore(SQLiteConfig.TempStore.MEMORY);         // temp tables in RAM
        sqLiteConfig.enforceForeignKeys(true);
        return sqLiteConfig;
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        JdbcTemplate template = new JdbcTemplate(dataSource);
        // Spring has no built-in error-code mappings for SQLite (SQL state is null).
        // This translator maps SQLITE_CONSTRAINT (error code 19) to DataIntegrityViolationException
        // so the service layer catches the right exception type for duplicate-email detection.
        template.setExceptionTranslator(new SQLErrorCodeSQLExceptionTranslator(dataSource) {
            @Override
            protected org.springframework.dao.DataAccessException customTranslate(
                    String task, @Nullable String sql, SQLException sqlEx) {
                if ((sqlEx.getErrorCode() & 0xFF) == 19) { // SQLITE_CONSTRAINT base code
                    return new DataIntegrityViolationException(task + "; " + sqlEx.getMessage(), sqlEx);
                }
                return null; // fall through to default translation chain
            }
        });
        return template;
    }
}
