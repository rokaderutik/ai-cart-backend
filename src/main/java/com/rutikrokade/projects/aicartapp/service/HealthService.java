package com.rutikrokade.projects.aicartapp.service;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class HealthService {

    private final DataSource dataSource;

    public boolean isDatabaseAvailable() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(2);
        } catch (SQLException exception) {
            return false;
        }
    }
}
