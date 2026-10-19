package com.rutikrokade.projects.aicartapp.service;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class HealthServiceImpl implements HealthService {

    private final DataSource dataSource;

    @Override
    public boolean isDatabaseAvailable() {
        try (Connection connection = dataSource.getConnection()) {
            boolean valid = connection.isValid(2);
            if (valid) {
                log.debug("Database health check passed");
            } else {
                log.warn("Database health check returned an invalid connection");
            }
            return valid;
        } catch (SQLException exception) {
            log.warn("Database health check failed (SQL state={}, vendor code={})",
                    exception.getSQLState(), exception.getErrorCode());
            return false;
        }
    }
}
