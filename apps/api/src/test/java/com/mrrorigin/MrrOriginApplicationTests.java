package com.mrrorigin;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest
class MrrOriginApplicationTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    @Autowired
    private DataSource dataSource;

    @Test
    void contextLoadsAndFlywayCreatesTenantRoots() {
        var jdbc = new JdbcTemplate(dataSource);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM workspaces", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM projects", Long.class)).isZero();
    }
}
